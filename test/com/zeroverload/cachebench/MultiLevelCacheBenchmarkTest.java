package com.zeroverload.cachebench;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 量化“多级缓存：使用 Caffeine 本地缓存 + Redis 缓存搭建二级缓存架构，提高热点数据访问速度，降低 Redis 压力”。
 *
 * 运行方式：
 * - 直接执行 `mvn -q -Dtest=MultiLevelCacheBenchmarkTest test`
 *
 * 可选参数（System Properties）：
 * -Dbench.redis.host=127.0.0.1 -Dbench.redis.port=6379
 * -Dbench.keys=5000 -Dbench.hotKeys=100 -Dbench.hotProb=0.9
 * -Dbench.requests=50000 -Dbench.warmupRequests=5000 -Dbench.threads=8
 * -Dbench.payloadBytes=64 -Dbench.sampleEvery=200
 */
public class MultiLevelCacheBenchmarkTest {
    private static final String KEY_PREFIX = "bench:mlc:";

    @Test
    void quantify_two_level_cache_improves_hotspot_access_and_reduces_redis_load() throws Exception {
        suppressNoisyLogging();
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();

        RedisURI redisUri = RedisURI.builder()
                .withHost(config.redisHost)
                .withPort(config.redisPort)
                .withDatabase(0)
                .withTimeout(Duration.ofSeconds(2))
                .build();

        RedisClient client = RedisClient.create(redisUri);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            RedisCommands<String, String> commands = connection.sync();
            boolean redisReady = canPing(commands);
            Assumptions.assumeTrue(redisReady, "Redis 未就绪（默认期望 127.0.0.1:6379），跳过该性能量化测试");

            prepareData(commands, config);

            CountingRedis countingRedis = new CountingRedis(commands);
            RedisOnlyCache redisOnly = new RedisOnlyCache(countingRedis);

            Cache<String, String> local = Caffeine.newBuilder()
                    .maximumSize(Math.max(config.keys, config.hotKeys) * 2L)
                    .expireAfterWrite(Duration.ofMinutes(5))
                    .build();
            TwoLevelCache twoLevel = new TwoLevelCache(local, countingRedis);

            System.out.println();
            System.out.println("=== Multi-Level Cache Benchmark (Caffeine + Redis) ===");
            System.out.println(config);

            // Redis-only
            countingRedis.resetGetCount();
            runWarmup("RedisOnly warmup", redisOnly, config);
            countingRedis.resetGetCount();
            BenchmarkResult redisOnlyResult = runMeasured("RedisOnly", redisOnly, config);

            // Two-level
            local.invalidateAll();
            countingRedis.resetGetCount();
            runWarmup("TwoLevel warmup", twoLevel, config);
            countingRedis.resetGetCount();
            BenchmarkResult twoLevelResult = runMeasured("TwoLevel(Caffeine+Redis)", twoLevel, config);

            System.out.println();
            System.out.println(redisOnlyResult.toHumanString());
            System.out.println(twoLevelResult.toHumanString());
            System.out.println();
            System.out.printf(Locale.ROOT,
                    "Redis GET 降幅: %.2f%% (RedisOnly=%d, TwoLevel=%d)%n",
                    100.0 * (1.0 - (twoLevelResult.redisGets * 1.0 / Math.max(1L, redisOnlyResult.redisGets))),
                    redisOnlyResult.redisGets,
                    twoLevelResult.redisGets
            );
        } finally {
            client.shutdown();
        }
    }

    private static boolean canPing(RedisCommands<String, String> commands) {
        try {
            String pong = commands.ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void prepareData(RedisCommands<String, String> commands, BenchmarkConfig config) {
        String payload = fixedPayload(config.payloadBytes);
        for (int i = 0; i < config.keys; i++) {
            commands.set(keyOf(i), payload);
        }
        // 轻量校验：确保热点 key 存在
        String v = commands.get(keyOf(0));
        if (v == null) {
            throw new IllegalStateException("Redis 数据准备失败：GET 返回 null");
        }
    }

    private static void runWarmup(String name, CacheClient cache, BenchmarkConfig config) throws Exception {
        if (config.warmupRequests <= 0) return;
        BenchmarkConfig warm = config.withRequests(config.warmupRequests);
        BenchmarkResult ignored = run(name, cache, warm, false);
        // warmup 不打印指标，避免干扰主输出
    }

    private static BenchmarkResult runMeasured(String name, CacheClient cache, BenchmarkConfig config) throws Exception {
        return run(name, cache, config, true);
    }

    private static BenchmarkResult run(String name, CacheClient cache, BenchmarkConfig config, boolean sampleLatencies) throws Exception {
        int threads = Math.max(1, config.threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        LongAdder totalNanos = new LongAdder();
        LongAdder checksum = new LongAdder();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        int sampleEvery = Math.max(1, config.sampleEvery);
        int perThread = config.requests / threads;
        int remainder = config.requests % threads;
        long[][] samples = sampleLatencies ? new long[threads][] : null;
        int[] sampleSizes = sampleLatencies ? new int[threads] : null;

        // 保护：避免 sample 数组过大导致内存压力（尤其是 requests/threads 很大时）
        final int maxTotalSamples = intProp("bench.maxTotalSamples", 20_000);
        final int maxSamplesPerThread = Math.max(1, maxTotalSamples / threads);

        long benchStartWall = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int threadIndex = t;
            final int requests = perThread + (t < remainder ? 1 : 0);
            final int expectedSamples = sampleLatencies ? Math.max(1, requests / sampleEvery) : 0;
            if (sampleLatencies) {
                samples[threadIndex] = new long[Math.min(expectedSamples + 1, maxSamplesPerThread)];
            }

            pool.execute(() -> {
                try {
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    ready.countDown();
                    start.await();

                    int localSampleSize = 0;
                    for (int i = 0; i < requests; i++) {
                        if (failure.get() != null) return;
                        String key = chooseKey(rnd, config);
                        long t0 = System.nanoTime();
                        String value = cache.get(key);
                        long dt = System.nanoTime() - t0;

                        if (value == null) {
                            throw new IllegalStateException("缓存返回 null，key=" + key);
                        }
                        checksum.add(value.length());
                        totalNanos.add(dt);

                        if (sampleLatencies && (i % sampleEvery == 0)) {
                            long[] arr = samples[threadIndex];
                            if (localSampleSize < arr.length) {
                                arr[localSampleSize++] = dt;
                            }
                        }
                    }
                    if (sampleLatencies) sampleSizes[threadIndex] = localSampleSize;
                } catch (Throwable t1) {
                    failure.compareAndSet(null, t1);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        long startWall = System.nanoTime();
        start.countDown();
        done.await();
        long elapsedWall = System.nanoTime() - startWall;
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        Throwable t = failure.get();
        if (t != null) {
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            if (t instanceof Error) throw (Error) t;
            throw new RuntimeException(t);
        }

        long measuredRequests = config.requests;
        long totalNs = totalNanos.sum();
        double avgUs = (totalNs * 1.0 / Math.max(1L, measuredRequests)) / 1000.0;
        double opsPerSec = measuredRequests / (elapsedWall / 1_000_000_000.0);

        long[] merged = sampleLatencies ? mergeSamples(samples, sampleSizes) : new long[0];
        Percentiles p = sampleLatencies ? Percentiles.fromNanos(merged) : Percentiles.empty();

        long redisGets = cache.redisGetCount();
        long benchEndWall = System.nanoTime();
        return new BenchmarkResult(
                name,
                measuredRequests,
                elapsedWall,
                avgUs,
                opsPerSec,
                p,
                redisGets,
                checksum.sum(),
                benchEndWall - benchStartWall
        );
    }

    private static int intProp(String key, int def) {
        String v = System.getProperty(key);
        if (v == null || v.trim().isEmpty()) return def;
        return Integer.parseInt(v.trim());
    }

    private static String chooseKey(ThreadLocalRandom rnd, BenchmarkConfig config) {
        boolean hot = rnd.nextDouble() < config.hotProb;
        if (hot) {
            int hotIndex = rnd.nextInt(config.hotKeys);
            return keyOf(hotIndex);
        }
        int coldIndex = config.hotKeys + rnd.nextInt(Math.max(1, config.keys - config.hotKeys));
        return keyOf(coldIndex);
    }

    private static long[] mergeSamples(long[][] samples, int[] sizes) {
        int total = 0;
        for (int i = 0; i < sizes.length; i++) total += sizes[i];
        long[] merged = new long[total];
        int p = 0;
        for (int i = 0; i < samples.length; i++) {
            int size = sizes[i];
            System.arraycopy(samples[i], 0, merged, p, size);
            p += size;
        }
        return merged;
    }

    private static String keyOf(int i) {
        return KEY_PREFIX + i;
    }

    private static String fixedPayload(int bytes) {
        int n = Math.max(1, bytes);
        char[] chars = new char[n];
        Arrays.fill(chars, 'a');
        return new String(chars);
    }

    private static void suppressNoisyLogging() {
        // 某些环境下会把 io.lettuce 打到 DEBUG，200k+ 次请求会刷屏且可能导致 surefire/控制台内存暴涨。
        trySetLogbackLevel("io.lettuce", Level.WARN);
        trySetLogbackLevel("io.lettuce.core.protocol", Level.WARN);
        trySetLogbackLevel("io.lettuce.core.protocol.RedisStateMachine", Level.WARN);
    }

    private static void trySetLogbackLevel(String loggerName, Level level) {
        try {
            org.slf4j.Logger slf4jLogger = LoggerFactory.getLogger(loggerName);
            if (slf4jLogger instanceof Logger) {
                ((Logger) slf4jLogger).setLevel(level);
            }
        } catch (Throwable ignored) {
            // ignore when logback isn't present
        }
    }

    private interface CacheClient {
        String get(String key);

        long redisGetCount();
    }

    private static final class RedisOnlyCache implements CacheClient {
        private final CountingRedis redis;

        private RedisOnlyCache(CountingRedis redis) {
            this.redis = Objects.requireNonNull(redis, "redis");
        }

        @Override
        public String get(String key) {
            return redis.get(key);
        }

        @Override
        public long redisGetCount() {
            return redis.getGetCount();
        }
    }

    private static final class TwoLevelCache implements CacheClient {
        private final Cache<String, String> local;
        private final CountingRedis redis;

        private TwoLevelCache(Cache<String, String> local, CountingRedis redis) {
            this.local = Objects.requireNonNull(local, "local");
            this.redis = Objects.requireNonNull(redis, "redis");
        }

        @Override
        public String get(String key) {
            String v = local.getIfPresent(key);
            if (v != null) return v;
            v = redis.get(key);
            if (v != null) local.put(key, v);
            return v;
        }

        @Override
        public long redisGetCount() {
            return redis.getGetCount();
        }
    }

    private static final class CountingRedis {
        private final RedisCommands<String, String> commands;
        private final AtomicLong getCount = new AtomicLong();

        private CountingRedis(RedisCommands<String, String> commands) {
            this.commands = Objects.requireNonNull(commands, "commands");
        }

        String get(String key) {
            getCount.incrementAndGet();
            return commands.get(key);
        }

        long getGetCount() {
            return getCount.get();
        }

        void resetGetCount() {
            getCount.set(0);
        }
    }

    private static final class BenchmarkConfig {
        final String redisHost;
        final int redisPort;
        final int keys;
        final int hotKeys;
        final double hotProb;
        final int requests;
        final int warmupRequests;
        final int threads;
        final int payloadBytes;
        final int sampleEvery;

        private BenchmarkConfig(
                String redisHost,
                int redisPort,
                int keys,
                int hotKeys,
                double hotProb,
                int requests,
                int warmupRequests,
                int threads,
                int payloadBytes,
                int sampleEvery
        ) {
            this.redisHost = redisHost;
            this.redisPort = redisPort;
            this.keys = keys;
            this.hotKeys = hotKeys;
            this.hotProb = hotProb;
            this.requests = requests;
            this.warmupRequests = warmupRequests;
            this.threads = threads;
            this.payloadBytes = payloadBytes;
            this.sampleEvery = sampleEvery;
        }

        static BenchmarkConfig fromSystemProperties() {
            String host = sysPropOrEnv("bench.redis.host", "REDIS_HOST", "127.0.0.1");
            int port = intPropOrEnv("bench.redis.port", "REDIS_PORT", 6379);
            int keys = intProp("bench.keys", 5000);
            int hotKeys = intProp("bench.hotKeys", 100);
            double hotProb = doubleProp("bench.hotProb", 0.90);
            int requests = intProp("bench.requests", 50_000);
            int warmupRequests = intProp("bench.warmupRequests", Math.max(5_000, requests / 10));
            int threads = intProp("bench.threads", Math.max(2, Runtime.getRuntime().availableProcessors()));
            int payloadBytes = intProp("bench.payloadBytes", 64);
            int sampleEvery = intProp("bench.sampleEvery", 200);

            if (hotKeys > keys) {
                hotKeys = keys;
            }
            if (hotProb < 0.0 || hotProb > 1.0) {
                throw new IllegalArgumentException("bench.hotProb must be in [0,1]");
            }
            return new BenchmarkConfig(host, port, keys, hotKeys, hotProb, requests, warmupRequests, threads, payloadBytes, sampleEvery);
        }

        BenchmarkConfig withRequests(int requests) {
            return new BenchmarkConfig(redisHost, redisPort, keys, hotKeys, hotProb, requests, warmupRequests, threads, payloadBytes, sampleEvery);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "Redis=%s:%d, keys=%d, hotKeys=%d, hotProb=%.2f, threads=%d, requests=%d, warmup=%d, payloadBytes=%d, sampleEvery=%d",
                    redisHost, redisPort, keys, hotKeys, hotProb, threads, requests, warmupRequests, payloadBytes, sampleEvery
            );
        }

        private static int intProp(String key, int def) {
            String v = System.getProperty(key);
            if (v == null || v.trim().isEmpty()) return def;
            return Integer.parseInt(v.trim());
        }

        private static double doubleProp(String key, double def) {
            String v = System.getProperty(key);
            if (v == null || v.trim().isEmpty()) return def;
            return Double.parseDouble(v.trim());
        }

        private static String sysPropOrEnv(String sysProp, String env, String def) {
            String v = System.getProperty(sysProp);
            if (v != null && !v.trim().isEmpty()) return v.trim();
            v = System.getenv(env);
            if (v != null && !v.trim().isEmpty()) return v.trim();
            return def;
        }

        private static int intPropOrEnv(String sysProp, String env, int def) {
            String v = System.getProperty(sysProp);
            if (v != null && !v.trim().isEmpty()) return Integer.parseInt(v.trim());
            v = System.getenv(env);
            if (v != null && !v.trim().isEmpty()) return Integer.parseInt(v.trim());
            return def;
        }
    }

    private static final class BenchmarkResult {
        final String name;
        final long requests;
        final long elapsedNanos;
        final double avgLatencyUs;
        final double opsPerSec;
        final Percentiles p;
        final long redisGets;
        final long checksum;
        final long totalWallNanos;

        private BenchmarkResult(
                String name,
                long requests,
                long elapsedNanos,
                double avgLatencyUs,
                double opsPerSec,
                Percentiles p,
                long redisGets,
                long checksum,
                long totalWallNanos
        ) {
            this.name = name;
            this.requests = requests;
            this.elapsedNanos = elapsedNanos;
            this.avgLatencyUs = avgLatencyUs;
            this.opsPerSec = opsPerSec;
            this.p = p;
            this.redisGets = redisGets;
            this.checksum = checksum;
            this.totalWallNanos = totalWallNanos;
        }

        String toHumanString() {
            return String.format(Locale.ROOT,
                    "%s: ops/s=%.0f, avg=%.2f us, p50=%.2f us, p95=%.2f us, p99=%.2f us, redisGets=%d (%.2f%%), elapsed=%.1f ms, checksum=%d",
                    name,
                    opsPerSec,
                    avgLatencyUs,
                    p.p50Us,
                    p.p95Us,
                    p.p99Us,
                    redisGets,
                    100.0 * (redisGets * 1.0 / Math.max(1L, requests)),
                    elapsedNanos / 1_000_000.0,
                    checksum
            );
        }
    }

    private static final class Percentiles {
        final double p50Us;
        final double p95Us;
        final double p99Us;

        private Percentiles(double p50Us, double p95Us, double p99Us) {
            this.p50Us = p50Us;
            this.p95Us = p95Us;
            this.p99Us = p99Us;
        }

        static Percentiles empty() {
            return new Percentiles(Double.NaN, Double.NaN, Double.NaN);
        }

        static Percentiles fromNanos(long[] samples) {
            if (samples.length == 0) return empty();
            Arrays.sort(samples);
            return new Percentiles(us(at(samples, 0.50)), us(at(samples, 0.95)), us(at(samples, 0.99)));
        }

        private static long at(long[] sorted, double q) {
            int idx = (int) Math.min(sorted.length - 1L, Math.round(q * (sorted.length - 1L)));
            return sorted[Math.max(0, idx)];
        }

        private static double us(long nanos) {
            return nanos / 1000.0;
        }
    }
}
