package com.zeroverload.seckillbench;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * 量化描述：
 * “高并发秒杀系统重构：Redis 生成分布式 ID 解决分库分表 ID 唯一问题；Redis+Lua 脚本原子校验秒杀资格，
 * 乐观锁避免超卖；RabbitMQ 异步处理订单，响应时间降至 50ms 内；Redisson 分布式锁（带看门狗、连锁方案）
 * 保障集群下一人一单的线程安全。”
 *
 * 运行（需要 Redis；RabbitMQ/Redisson 部分按可用性自动跳过或降级输出）：
 * `mvn -q -Dtest=HighConcurrencySeckillRebuildBenchmarkTest test`
 *
 * 可调参数（System Properties）：
 * Redis:
 * -Dbench.redis.host=127.0.0.1 -Dbench.redis.port=6379
 * 负载:
 * -Dbench.threads=16 -Dbench.requests=50000 -Dbench.users=20000
 * 秒杀:
 * -Dbench.voucherId=1 -Dbench.stock=2000
 * 同步/异步对比:
 * -Dbench.syncWorkMs=20 -Dbench.asyncWorkMs=20
 * RabbitMQ:
 * -Dbench.rabbit.host=localhost -Dbench.rabbit.port=5672 -Dbench.rabbit.username=guest -Dbench.rabbit.password=guest
 */
public class HighConcurrencySeckillRebuildBenchmarkTest {
    private static final String NS = "bench:seckill:";
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    private static final String LUA_SECKILL =
            "local stockKey = KEYS[1]\n" +
                    "local orderKey = KEYS[2]\n" +
                    "local userId = ARGV[1]\n" +
                    "local orderId = ARGV[2]\n" +
                    "local stock = tonumber(redis.call('GET', stockKey))\n" +
                    "if (stock == nil) then return 3 end\n" +
                    "if (stock <= 0) then return 1 end\n" +
                    "if (redis.call('SISMEMBER', orderKey, userId) == 1) then return 2 end\n" +
                    "redis.call('DECR', stockKey)\n" +
                    "redis.call('SADD', orderKey, userId)\n" +
                    "redis.call('XADD', KEYS[3], '*', 'userId', userId, 'orderId', orderId)\n" +
                    "return 0\n";

    @Test
    void quantify_seckill_rebuild_metrics() throws Exception {
        suppressNoisyLogging();
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();

        RedisURI redisUri = RedisURI.builder()
                .withHost(config.redisHost)
                .withPort(config.redisPort)
                .withDatabase(0)
                .withTimeout(Duration.ofSeconds(2))
                .build();
        RedisClient redisClient = RedisClient.create(redisUri);

        try (StatefulRedisConnection<String, String> conn = redisClient.connect()) {
            RedisCommands<String, String> redis = conn.sync();
            Assumptions.assumeTrue(canPing(redis), "Redis 未就绪（默认期望 127.0.0.1:6379），跳过该量化测试");

            System.out.println();
            System.out.println("=== Seckill Rebuild Benchmark ===");
            System.out.println(config);

            DistributedIdResult idResult = benchmarkDistributedId(redis, config);
            System.out.println(idResult.toHumanString());

            SeckillLuaResult luaResult = benchmarkRedisLuaSeckill(redis, config);
            System.out.println(luaResult.toHumanString());

            OversellResult oversellResult = benchmarkOptimisticLockOversell(config);
            System.out.println(oversellResult.toHumanString());

            RedissonResult redissonResult = benchmarkRedissonLocksIfPossible(config);
            if (redissonResult != null) System.out.println(redissonResult.toHumanString());

            RabbitResult rabbitResult = benchmarkRabbitMqAsyncIfPossible(redis, config);
            if (rabbitResult != null) System.out.println(rabbitResult.toHumanString());
        } finally {
            redisClient.shutdown();
        }
    }

    // ----------------------- 1) Redis 分布式 ID -----------------------

    private static DistributedIdResult benchmarkDistributedId(RedisCommands<String, String> redis, BenchmarkConfig config) throws Exception {
        String day = DAY_FMT.format(LocalDate.now(ZoneOffset.UTC));
        String key = NS + "id:" + day;
        redis.del(key);

        IdWorker worker = new IdWorker(redis, key);
        BenchmarkResult r = runConcurrent("RedisId(INCR)", config.threads, config.requests, (ignored) -> worker.nextId());

        // 统计唯一性：采样一部分到本地 set 可能爆内存；这里用“序列号单调递增且无重复”的方式验证：
        // INCR 保证全局唯一；再额外校验最后的 INCR 值是否等于 requests（无丢失）。
        long last = Long.parseLong(redis.get(key));
        boolean noLoss = (last == config.requests);

        return new DistributedIdResult(r, last, noLoss);
    }

    private static final class IdWorker {
        private final RedisCommands<String, String> redis;
        private final String incrKey;
        private final long epochSecond;

        private IdWorker(RedisCommands<String, String> redis, String incrKey) {
            this.redis = Objects.requireNonNull(redis, "redis");
            this.incrKey = Objects.requireNonNull(incrKey, "incrKey");
            // 2024-01-01T00:00:00Z
            this.epochSecond = Instant.parse("2024-01-01T00:00:00Z").getEpochSecond();
        }

        long nextId() {
            long nowSecond = Instant.now().getEpochSecond();
            long timestampPart = (nowSecond - epochSecond) & 0x7FFFFFFFL;
            long seq = redis.incr(incrKey) & 0xFFFFFL; // 20 bits
            return (timestampPart << 20) | seq;
        }
    }

    private static final class DistributedIdResult {
        final BenchmarkResult bench;
        final long redisIncrLast;
        final boolean noLoss;

        private DistributedIdResult(BenchmarkResult bench, long redisIncrLast, boolean noLoss) {
            this.bench = bench;
            this.redisIncrLast = redisIncrLast;
            this.noLoss = noLoss;
        }

        String toHumanString() {
            return String.format(Locale.ROOT,
                    "[ID] %s, redisINCRLast=%d, noLoss=%s",
                    bench.toHumanString(), redisIncrLast, noLoss);
        }
    }

    // ----------------------- 2) Redis+Lua 原子校验秒杀资格 -----------------------

    private static SeckillLuaResult benchmarkRedisLuaSeckill(RedisCommands<String, String> redis, BenchmarkConfig config) throws Exception {
        String stockKey = NS + "stock:" + config.voucherId;
        String orderKey = NS + "orderUsers:" + config.voucherId;
        String streamKey = NS + "orders:" + config.voucherId;

        redis.del(stockKey, orderKey, streamKey);
        redis.set(stockKey, String.valueOf(config.stock));

        // 预创建 stream group 不强依赖；只用于证明“把订单请求写入队列/流”是异步链路的入口
        try {
            redis.xgroupCreate(io.lettuce.core.XReadArgs.StreamOffset.from(streamKey, "0-0"), "g1");
        } catch (Exception ignored) {
            // stream 不存在时会失败；后续 LUA XADD 会创建
        }

        AtomicLong ok = new AtomicLong();
        AtomicLong outOfStock = new AtomicLong();
        AtomicLong duplicate = new AtomicLong();
        AtomicLong missing = new AtomicLong();

        BenchmarkResult bench = runConcurrent("RedisLuaSeckill", config.threads, config.requests, (i) -> {
            long userId = ThreadLocalRandom.current().nextLong(config.users) + 1;
            long orderId = userId; // 只为产生一个“请求中的订单号”，真正唯一性在 ID Benchmark 中量化

            Object res = redis.eval(
                    LUA_SECKILL,
                    ScriptOutputType.INTEGER,
                    new String[]{stockKey, orderKey, streamKey},
                    String.valueOf(userId),
                    String.valueOf(orderId)
            );
            long code = (res instanceof Number) ? ((Number) res).longValue() : Long.parseLong(String.valueOf(res));
            if (code == 0) ok.incrementAndGet();
            else if (code == 1) outOfStock.incrementAndGet();
            else if (code == 2) duplicate.incrementAndGet();
            else missing.incrementAndGet();
            return code;
        });

        int finalStock = Integer.parseInt(redis.get(stockKey));
        long orderUsers = redis.scard(orderKey);
        boolean noOversell = (finalStock >= 0) && (ok.get() <= config.stock);
        boolean onePersonOneOrder = (orderUsers == ok.get());

        return new SeckillLuaResult(bench, ok.get(), outOfStock.get(), duplicate.get(), missing.get(), finalStock, orderUsers, noOversell, onePersonOneOrder);
    }

    private static final class SeckillLuaResult {
        final BenchmarkResult bench;
        final long ok;
        final long outOfStock;
        final long duplicate;
        final long missing;
        final int finalStock;
        final long orderUsers;
        final boolean noOversell;
        final boolean onePersonOneOrder;

        private SeckillLuaResult(BenchmarkResult bench, long ok, long outOfStock, long duplicate, long missing, int finalStock, long orderUsers, boolean noOversell, boolean onePersonOneOrder) {
            this.bench = bench;
            this.ok = ok;
            this.outOfStock = outOfStock;
            this.duplicate = duplicate;
            this.missing = missing;
            this.finalStock = finalStock;
            this.orderUsers = orderUsers;
            this.noOversell = noOversell;
            this.onePersonOneOrder = onePersonOneOrder;
        }

        String toHumanString() {
            return String.format(Locale.ROOT,
                    "[Lua] %s, ok=%d, outOfStock=%d, duplicate=%d, missing=%d, finalStock=%d, orderUsers=%d, noOversell=%s, onePersonOneOrder=%s",
                    bench.toHumanString(), ok, outOfStock, duplicate, missing, finalStock, orderUsers, noOversell, onePersonOneOrder);
        }
    }

    // ----------------------- 3) 乐观锁避免超卖（对比错误写法） -----------------------

    private static OversellResult benchmarkOptimisticLockOversell(BenchmarkConfig config) throws Exception {
        int stock = config.stock;

        AtomicInteger naiveStock = new AtomicInteger(stock);
        AtomicLong naiveOk = new AtomicLong();
        BenchmarkResult naive = runConcurrent("NaiveUpdate", config.threads, config.requests, (ignored) -> {
            // 错误写法：读-改-写不是原子，可能出现 ok>stock 或 stock 变成负数
            int current = naiveStock.get();
            if (current <= 0) return 1;
            naiveStock.set(current - 1);
            naiveOk.incrementAndGet();
            return 0;
        });
        int naiveFinal = naiveStock.get();
        boolean naiveOversold = naiveOk.get() > stock || naiveFinal < 0;

        AtomicInteger casStock = new AtomicInteger(stock);
        AtomicLong casOk = new AtomicLong();
        BenchmarkResult cas = runConcurrent("OptimisticCAS", config.threads, config.requests, (ignored) -> {
            while (true) {
                int current = casStock.get();
                if (current <= 0) return 1;
                if (casStock.compareAndSet(current, current - 1)) {
                    casOk.incrementAndGet();
                    return 0;
                }
            }
        });
        int casFinal = casStock.get();
        boolean casOversold = casOk.get() > stock || casFinal < 0;

        return new OversellResult(naive, naiveOk.get(), naiveFinal, naiveOversold, cas, casOk.get(), casFinal, casOversold);
    }

    private static final class OversellResult {
        final BenchmarkResult naive;
        final long naiveOk;
        final int naiveFinalStock;
        final boolean naiveOversold;
        final BenchmarkResult cas;
        final long casOk;
        final int casFinalStock;
        final boolean casOversold;

        private OversellResult(BenchmarkResult naive, long naiveOk, int naiveFinalStock, boolean naiveOversold, BenchmarkResult cas, long casOk, int casFinalStock, boolean casOversold) {
            this.naive = naive;
            this.naiveOk = naiveOk;
            this.naiveFinalStock = naiveFinalStock;
            this.naiveOversold = naiveOversold;
            this.cas = cas;
            this.casOk = casOk;
            this.casFinalStock = casFinalStock;
            this.casOversold = casOversold;
        }

        String toHumanString() {
            return String.format(Locale.ROOT,
                    "[OptLock] naive=%s, naiveOk=%d, naiveFinalStock=%d, naiveOversold=%s | cas=%s, casOk=%d, casFinalStock=%d, casOversold=%s",
                    naive.toHumanString(), naiveOk, naiveFinalStock, naiveOversold,
                    cas.toHumanString(), casOk, casFinalStock, casOversold
            );
        }
    }

    // ----------------------- 4) Redisson 分布式锁（看门狗 + 连锁方案） -----------------------

    private static RedissonResult benchmarkRedissonLocksIfPossible(BenchmarkConfig config) {
        // Redisson 使用 Redis；如果 Redis 可用则可跑（连接失败则跳过）
        try {
            Config redissonCfg = new Config();
            redissonCfg.setLockWatchdogTimeout(config.redissonWatchdogMs);
            redissonCfg.useSingleServer().setAddress("redis://" + config.redisHost + ":" + config.redisPort);
            RedissonClient redisson = Redisson.create(redissonCfg);

            try {
                boolean watchdogOk = verifyWatchdog(redisson, config);
                MultiLockOverhead overhead = measureMultiLockOverhead(redisson, config);
                OnePersonOneOrder o1o1 = quantifyOnePersonOneOrderSafety(redisson, config);
                return new RedissonResult(watchdogOk, overhead, o1o1);
            } finally {
                redisson.shutdown();
            }
        } catch (Exception e) {
            System.out.println("[Redisson] skipped: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean verifyWatchdog(RedissonClient redisson, BenchmarkConfig config) throws Exception {
        String lockName = NS + "lock:watchdog";
        RLock lock = redisson.getLock(lockName);
        lock.lock();
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Boolean> acquired = new AtomicReference<>(null);
            Thread t = new Thread(() -> {
                try {
                    // 尝试在 watchdog 超时之后获取，若 watchdog 生效则应获取不到
                    sleepMs(config.redissonWatchdogMs + 200L);
                    boolean ok = lock.tryLock(200, TimeUnit.MILLISECONDS);
                    acquired.set(ok);
                    if (ok) lock.unlock();
                } catch (Exception ex) {
                    acquired.set(false);
                } finally {
                    latch.countDown();
                }
            }, "watchdog-probe");
            t.setDaemon(true);
            t.start();
            latch.await(5, TimeUnit.SECONDS);

            Boolean probe = acquired.get();
            // probe==false 代表“锁仍被持有”，即 watchdog 续期生效
            return Boolean.FALSE.equals(probe);
        } finally {
            lock.unlock();
        }
    }

    private static MultiLockOverhead measureMultiLockOverhead(RedissonClient redisson, BenchmarkConfig config) throws Exception {
        // 连锁方案：同时锁住 user + voucher 两个粒度，避免跨资源的竞态
        String userLockName = NS + "lock:user:1";
        String voucherLockName = NS + "lock:voucher:" + config.voucherId;
        RLock userLock = redisson.getLock(userLockName);
        RLock voucherLock = redisson.getLock(voucherLockName);
        org.redisson.RedissonMultiLock multi = new org.redisson.RedissonMultiLock(userLock, voucherLock);

        BenchmarkResult single = runConcurrent("RedissonSingleLock", Math.min(4, config.threads), Math.min(5000, config.requests), (ignored) -> {
            RLock l = redisson.getLock(voucherLockName);
            l.lock();
            try {
                return 0;
            } finally {
                l.unlock();
            }
        });

        BenchmarkResult chained = runConcurrent("RedissonMultiLock", Math.min(4, config.threads), Math.min(5000, config.requests), (ignored) -> {
            multi.lock();
            try {
                return 0;
            } finally {
                multi.unlock();
            }
        });

        return new MultiLockOverhead(single, chained);
    }

    private static OnePersonOneOrder quantifyOnePersonOneOrderSafety(RedissonClient redisson, BenchmarkConfig config) throws Exception {
        // 用 Redis SET 模拟“用户是否已下单”。这里故意用非原子两步（GET+SET）来制造并发漏洞；
        // 然后用 Redisson 锁住 userId 来修复，量化“重复下单”数量。
        RedisURI redisUri = RedisURI.builder()
                .withHost(config.redisHost)
                .withPort(config.redisPort)
                .withDatabase(0)
                .withTimeout(Duration.ofSeconds(2))
                .build();
        RedisClient client = RedisClient.create(redisUri);
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            RedisCommands<String, String> redis = conn.sync();
            String orderedKeyPrefix = NS + "ordered:user:";
            // 清理本次基准涉及的少量 key（避免遍历删除）
            for (int i = 1; i <= Math.min(5000, config.users); i++) {
                redis.del(orderedKeyPrefix + i);
            }

            AtomicLong dupWithoutLock = new AtomicLong();
            AtomicLong okWithoutLock = new AtomicLong();
            BenchmarkResult withoutLock = runConcurrent("O1O1-noLock", config.threads, config.requests, (ignored) -> {
                long userId = ThreadLocalRandom.current().nextLong(Math.min(5000, config.users)) + 1;
                String k = orderedKeyPrefix + userId;
                String existed = redis.get(k);
                if (existed != null) {
                    dupWithoutLock.incrementAndGet();
                    return 2;
                }
                // 并发窗口：制造更明显的竞态
                if ((userId & 15) == 0) Thread.yield();
                redis.set(k, "1");
                okWithoutLock.incrementAndGet();
                return 0;
            });
            long uniqueWithoutLock = countExisting(redis, orderedKeyPrefix, Math.min(5000, config.users));
            long duplicatedCreatesWithoutLock = Math.max(0L, okWithoutLock.get() - uniqueWithoutLock);

            // reset
            for (int i = 1; i <= Math.min(5000, config.users); i++) {
                redis.del(orderedKeyPrefix + i);
            }

            AtomicLong dupWithLock = new AtomicLong();
            AtomicLong okWithLock = new AtomicLong();
            BenchmarkResult withLock = runConcurrent("O1O1-RedissonLock", config.threads, config.requests, (ignored) -> {
                long userId = ThreadLocalRandom.current().nextLong(Math.min(5000, config.users)) + 1;
                RLock lock = redisson.getLock(NS + "lock:o1o1:user:" + userId);
                lock.lock();
                try {
                    String k = orderedKeyPrefix + userId;
                    String existed = redis.get(k);
                    if (existed != null) {
                        dupWithLock.incrementAndGet();
                        return 2;
                    }
                    if ((userId & 15) == 0) Thread.yield();
                    redis.set(k, "1");
                    okWithLock.incrementAndGet();
                    return 0;
                } finally {
                    lock.unlock();
                }
            });
            long uniqueWithLock = countExisting(redis, orderedKeyPrefix, Math.min(5000, config.users));
            long duplicatedCreatesWithLock = Math.max(0L, okWithLock.get() - uniqueWithLock);

            return new OnePersonOneOrder(
                    withoutLock, okWithoutLock.get(), uniqueWithoutLock, duplicatedCreatesWithoutLock, dupWithoutLock.get(),
                    withLock, okWithLock.get(), uniqueWithLock, duplicatedCreatesWithLock, dupWithLock.get()
            );
        } finally {
            client.shutdown();
        }
    }

    private static long countExisting(RedisCommands<String, String> redis, String keyPrefix, long users) {
        long exists = 0;
        for (long i = 1; i <= users; i++) {
            exists += redis.exists(keyPrefix + i);
        }
        return exists;
    }

    private static final class RedissonResult {
        final boolean watchdogRenewOk;
        final MultiLockOverhead overhead;
        final OnePersonOneOrder o1o1;

        private RedissonResult(boolean watchdogRenewOk, MultiLockOverhead overhead, OnePersonOneOrder o1o1) {
            this.watchdogRenewOk = watchdogRenewOk;
            this.overhead = overhead;
            this.o1o1 = o1o1;
        }

        String toHumanString() {
            return String.format(Locale.ROOT,
                    "[Redisson] watchdogRenewOk=%s | overhead: %s | onePersonOneOrder: %s",
                    watchdogRenewOk, overhead.toHumanString(), o1o1.toHumanString());
        }
    }

    private static final class MultiLockOverhead {
        final BenchmarkResult single;
        final BenchmarkResult multi;

        private MultiLockOverhead(BenchmarkResult single, BenchmarkResult multi) {
            this.single = single;
            this.multi = multi;
        }

        String toHumanString() {
            return String.format(Locale.ROOT, "single=%s, multi=%s", single.toHumanString(), multi.toHumanString());
        }
    }

    private static final class OnePersonOneOrder {
        final BenchmarkResult withoutLock;
        final long okWithoutLock;
        final long uniqueWithoutLock;
        final long duplicatedCreatesWithoutLock;
        final long dupHitWithoutLock;
        final BenchmarkResult withLock;
        final long okWithLock;
        final long uniqueWithLock;
        final long duplicatedCreatesWithLock;
        final long dupHitWithLock;

        private OnePersonOneOrder(
                BenchmarkResult withoutLock,
                long okWithoutLock,
                long uniqueWithoutLock,
                long duplicatedCreatesWithoutLock,
                long dupHitWithoutLock,
                BenchmarkResult withLock,
                long okWithLock,
                long uniqueWithLock,
                long duplicatedCreatesWithLock,
                long dupHitWithLock
        ) {
            this.withoutLock = withoutLock;
            this.okWithoutLock = okWithoutLock;
            this.uniqueWithoutLock = uniqueWithoutLock;
            this.duplicatedCreatesWithoutLock = duplicatedCreatesWithoutLock;
            this.dupHitWithoutLock = dupHitWithoutLock;
            this.withLock = withLock;
            this.okWithLock = okWithLock;
            this.uniqueWithLock = uniqueWithLock;
            this.duplicatedCreatesWithLock = duplicatedCreatesWithLock;
            this.dupHitWithLock = dupHitWithLock;
        }

        String toHumanString() {
            return String.format(Locale.ROOT,
                    "noLock(%s, ok=%d, unique=%d, duplicatedCreates=%d, dupHit=%d) vs lock(%s, ok=%d, unique=%d, duplicatedCreates=%d, dupHit=%d)",
                    withoutLock.toHumanString(), okWithoutLock, uniqueWithoutLock, duplicatedCreatesWithoutLock, dupHitWithoutLock,
                    withLock.toHumanString(), okWithLock, uniqueWithLock, duplicatedCreatesWithLock, dupHitWithLock
            );
        }
    }

    // ----------------------- 5) RabbitMQ 异步处理订单，响应时间指标 -----------------------

    private static RabbitResult benchmarkRabbitMqAsyncIfPossible(RedisCommands<String, String> redis, BenchmarkConfig config) throws Exception {
        RabbitClient rabbit = RabbitClient.tryConnect(config);
        if (rabbit == null) {
            System.out.println("[RabbitMQ] skipped: broker not reachable");
            return null;
        }

        String ex = NS + "ex";
        String queue = NS + "q";
        String routingKey = "order";
        rabbit.init(ex, queue, routingKey);

        try {
            // 同步基线：Lua + 模拟“下单落库”
            SeckillHandler sync = new SyncSeckillHandler(redis, config, config.syncWorkMs);
            // 异步链路：Lua + publish，消费者异步处理（模拟落库）
            AsyncSeckillHandler async = new AsyncSeckillHandler(redis, config, rabbit, ex, routingKey);

            ConsumerMetrics consumerMetrics = new ConsumerMetrics();
            rabbit.startConsumer(queue, consumerMetrics, config.asyncWorkMs);

            BenchmarkResult syncBench = runConcurrent("SeckillSync", config.threads, config.requests, (ignored) -> sync.trySeckill());
            BenchmarkResult asyncBench = runConcurrent("SeckillAsync(Publish)", config.threads, config.requests, (ignored) -> async.trySeckill());

            // 等待消费者把成功消息尽量消费完（有上限，避免卡死）
            consumerMetrics.awaitAtLeastOk(async.ok.get(), 10_000);

            return new RabbitResult(syncBench, asyncBench, async.ok.get(), consumerMetrics.snapshot());
        } finally {
            rabbit.close();
        }
    }

    private interface SeckillHandler {
        int trySeckill();
    }

    private static final class SyncSeckillHandler implements SeckillHandler {
        private final RedisCommands<String, String> redis;
        private final BenchmarkConfig config;
        private final int workMs;
        private final String stockKey;
        private final String orderKey;
        private final String streamKey;

        private SyncSeckillHandler(RedisCommands<String, String> redis, BenchmarkConfig config, int workMs) {
            this.redis = Objects.requireNonNull(redis, "redis");
            this.config = Objects.requireNonNull(config, "config");
            this.workMs = workMs;
            this.stockKey = NS + "mq:stock:" + config.voucherId;
            this.orderKey = NS + "mq:users:" + config.voucherId;
            this.streamKey = NS + "mq:stream:" + config.voucherId;
            redis.del(stockKey, orderKey, streamKey);
            redis.set(stockKey, String.valueOf(config.stock));
        }

        @Override
        public int trySeckill() {
            long userId = ThreadLocalRandom.current().nextLong(config.users) + 1;
            long orderId = userId;
            Object res = redis.eval(
                    LUA_SECKILL,
                    ScriptOutputType.INTEGER,
                    new String[]{stockKey, orderKey, streamKey},
                    String.valueOf(userId),
                    String.valueOf(orderId)
            );
            long code = (res instanceof Number) ? ((Number) res).longValue() : Long.parseLong(String.valueOf(res));
            if (code != 0) return (int) code;
            if (workMs > 0) sleepMs(workMs);
            return 0;
        }
    }

    private static final class AsyncSeckillHandler implements SeckillHandler {
        private final RedisCommands<String, String> redis;
        private final BenchmarkConfig config;
        private final RabbitClient rabbit;
        private final String exchange;
        private final String routingKey;
        private final String stockKey;
        private final String orderKey;
        private final String streamKey;
        final AtomicLong ok = new AtomicLong();

        private AsyncSeckillHandler(RedisCommands<String, String> redis, BenchmarkConfig config, RabbitClient rabbit, String exchange, String routingKey) {
            this.redis = Objects.requireNonNull(redis, "redis");
            this.config = Objects.requireNonNull(config, "config");
            this.rabbit = Objects.requireNonNull(rabbit, "rabbit");
            this.exchange = Objects.requireNonNull(exchange, "exchange");
            this.routingKey = Objects.requireNonNull(routingKey, "routingKey");
            this.stockKey = NS + "mq2:stock:" + config.voucherId;
            this.orderKey = NS + "mq2:users:" + config.voucherId;
            this.streamKey = NS + "mq2:stream:" + config.voucherId;
            redis.del(stockKey, orderKey, streamKey);
            redis.set(stockKey, String.valueOf(config.stock));
        }

        @Override
        public int trySeckill() {
            long userId = ThreadLocalRandom.current().nextLong(config.users) + 1;
            long orderId = userId;
            Object res = redis.eval(
                    LUA_SECKILL,
                    ScriptOutputType.INTEGER,
                    new String[]{stockKey, orderKey, streamKey},
                    String.valueOf(userId),
                    String.valueOf(orderId)
            );
            long code = (res instanceof Number) ? ((Number) res).longValue() : Long.parseLong(String.valueOf(res));
            if (code != 0) return (int) code;

            long t0 = System.nanoTime();
            byte[] body = (userId + "," + orderId + "," + t0).getBytes(StandardCharsets.UTF_8);
            rabbit.publish(exchange, routingKey, body);
            ok.incrementAndGet();
            return 0;
        }
    }

    private static final class RabbitResult {
        final BenchmarkResult syncBench;
        final BenchmarkResult asyncBench;
        final long asyncOk;
        final ConsumerSnapshot consumer;

        private RabbitResult(BenchmarkResult syncBench, BenchmarkResult asyncBench, long asyncOk, ConsumerSnapshot consumer) {
            this.syncBench = syncBench;
            this.asyncBench = asyncBench;
            this.asyncOk = asyncOk;
            this.consumer = consumer;
        }

        String toHumanString() {
            return String.format(Locale.ROOT,
                    "[RabbitMQ] sync=%s | async=%s, ok=%d, consumeOk=%d, end2endAvg=%.2f ms, end2endP95=%.2f ms, end2endP99=%.2f ms",
                    syncBench.toHumanString(),
                    asyncBench.toHumanString(),
                    asyncOk,
                    consumer.ok,
                    consumer.avgMs,
                    consumer.p95Ms,
                    consumer.p99Ms
            );
        }
    }

    private static final class RabbitClient {
        private final Connection connection;
        private final ThreadLocal<Channel> channelTl;

        private RabbitClient(Connection connection) {
            this.connection = connection;
            this.channelTl = ThreadLocal.withInitial(() -> {
                try {
                    return connection.createChannel();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        static RabbitClient tryConnect(BenchmarkConfig config) {
            try {
                ConnectionFactory factory = new ConnectionFactory();
                factory.setHost(config.rabbitHost);
                factory.setPort(config.rabbitPort);
                factory.setUsername(config.rabbitUsername);
                factory.setPassword(config.rabbitPassword);
                factory.setConnectionTimeout(1500);
                factory.setHandshakeTimeout(1500);
                factory.setRequestedHeartbeat(10);
                Connection connection = factory.newConnection("seckill-bench");
                return new RabbitClient(connection);
            } catch (Exception ignored) {
                return null;
            }
        }

        void init(String exchange, String queue, String routingKey) throws Exception {
            Channel ch = channelTl.get();
            ch.exchangeDeclare(exchange, BuiltinExchangeType.DIRECT, false, true, null);
            ch.queueDeclare(queue, false, false, true, null);
            ch.queueBind(queue, exchange, routingKey);
            ch.queuePurge(queue);
        }

        void publish(String exchange, String routingKey, byte[] body) {
            try {
                Channel ch = channelTl.get();
                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                        .deliveryMode(1)
                        .build();
                ch.basicPublish(exchange, routingKey, props, body);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        void startConsumer(String queue, ConsumerMetrics metrics, int workMs) throws Exception {
            Channel ch = connection.createChannel();
            ch.basicQos(100);
            DeliverCallback cb = (consumerTag, delivery) -> {
                long now = System.nanoTime();
                String s = new String(delivery.getBody(), StandardCharsets.UTF_8);
                // userId,orderId,tsNanos
                int lastComma = s.lastIndexOf(',');
                long t0 = Long.parseLong(s.substring(lastComma + 1));
                metrics.record(now - t0);
                if (workMs > 0) sleepMs(workMs);
                ch.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            };
            ch.basicConsume(queue, false, cb, consumerTag -> {});
        }

        void close() {
            try {
                Channel ch = channelTl.get();
                try {
                    ch.close();
                } catch (Exception ignored) {
                }
            } finally {
                try {
                    connection.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static final class ConsumerMetrics {
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong ok = new AtomicLong();
        private final long[] samples = new long[intProp("bench.rabbit.samples", 20_000)];
        private final AtomicInteger sampleIdx = new AtomicInteger();

        void record(long nanos) {
            totalNanos.add(nanos);
            long count = ok.incrementAndGet();
            int idx = sampleIdx.getAndIncrement();
            if (idx < samples.length) samples[idx] = nanos;
        }

        void awaitAtLeastOk(long expected, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (ok.get() >= expected) return;
                Thread.sleep(20);
            }
        }

        ConsumerSnapshot snapshot() {
            long c = ok.get();
            double avgMs = (totalNanos.sum() * 1.0 / Math.max(1L, c)) / 1_000_000.0;
            int n = Math.min(sampleIdx.get(), samples.length);
            long[] arr = Arrays.copyOf(samples, n);
            Arrays.sort(arr);
            double p95 = n == 0 ? Double.NaN : arr[(int) Math.min(n - 1L, Math.round(0.95 * (n - 1L)))] / 1_000_000.0;
            double p99 = n == 0 ? Double.NaN : arr[(int) Math.min(n - 1L, Math.round(0.99 * (n - 1L)))] / 1_000_000.0;
            return new ConsumerSnapshot(c, avgMs, p95, p99);
        }
    }

    private static final class ConsumerSnapshot {
        final long ok;
        final double avgMs;
        final double p95Ms;
        final double p99Ms;

        private ConsumerSnapshot(long ok, double avgMs, double p95Ms, double p99Ms) {
            this.ok = ok;
            this.avgMs = avgMs;
            this.p95Ms = p95Ms;
            this.p99Ms = p99Ms;
        }
    }

    // ----------------------- 通用并发基准框架 -----------------------

    private interface Task {
        Object run(int i);
    }

    private static BenchmarkResult runConcurrent(String name, int threads, int requests, Task task) throws Exception {
        int t = Math.max(1, threads);
        ExecutorService pool = Executors.newFixedThreadPool(t);
        CountDownLatch ready = new CountDownLatch(t);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(t);
        LongAdder totalNanos = new LongAdder();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        int sampleEvery = Math.max(1, intProp("bench.sampleEvery", 200));
        int perThread = requests / t;
        int remainder = requests % t;
        int maxTotalSamples = intProp("bench.maxTotalSamples", 20_000);
        int maxPerThread = Math.max(1, maxTotalSamples / t);

        long[][] samples = new long[t][];
        int[] sampleSizes = new int[t];

        for (int i = 0; i < t; i++) {
            int threadIndex = i;
            int count = perThread + (i < remainder ? 1 : 0);
            int expectedSamples = Math.max(1, count / sampleEvery);
            samples[threadIndex] = new long[Math.min(expectedSamples + 1, maxPerThread)];

            pool.execute(() -> {
                try {
                    ready.countDown();
                    start.await();
                    int localSampleSize = 0;
                    for (int j = 0; j < count; j++) {
                        if (failure.get() != null) return;
                        int globalIndex = threadIndex * perThread + j;
                        long t0 = System.nanoTime();
                        task.run(globalIndex);
                        long dt = System.nanoTime() - t0;
                        totalNanos.add(dt);
                        if (j % sampleEvery == 0) {
                            long[] arr = samples[threadIndex];
                            if (localSampleSize < arr.length) arr[localSampleSize++] = dt;
                        }
                    }
                    sampleSizes[threadIndex] = localSampleSize;
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        long startNs = System.nanoTime();
        start.countDown();
        done.await();
        long elapsed = System.nanoTime() - startNs;
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        Throwable th = failure.get();
        if (th != null) {
            if (th instanceof RuntimeException) throw (RuntimeException) th;
            if (th instanceof Error) throw (Error) th;
            throw new RuntimeException(th);
        }

        long[] merged = mergeSamples(samples, sampleSizes);
        Percentiles p = Percentiles.fromNanos(merged);
        double avgUs = (totalNanos.sum() * 1.0 / Math.max(1L, requests)) / 1000.0;
        double ops = requests / (elapsed / 1_000_000_000.0);
        return new BenchmarkResult(name, requests, elapsed, avgUs, ops, p);
    }

    private static long[] mergeSamples(long[][] samples, int[] sizes) {
        int total = 0;
        for (int s : sizes) total += s;
        long[] merged = new long[total];
        int p = 0;
        for (int i = 0; i < samples.length; i++) {
            int n = sizes[i];
            System.arraycopy(samples[i], 0, merged, p, n);
            p += n;
        }
        return merged;
    }

    private static final class BenchmarkResult {
        final String name;
        final long requests;
        final long elapsedNanos;
        final double avgUs;
        final double opsPerSec;
        final Percentiles p;

        private BenchmarkResult(String name, long requests, long elapsedNanos, double avgUs, double opsPerSec, Percentiles p) {
            this.name = name;
            this.requests = requests;
            this.elapsedNanos = elapsedNanos;
            this.avgUs = avgUs;
            this.opsPerSec = opsPerSec;
            this.p = p;
        }

        String toHumanString() {
            return String.format(Locale.ROOT,
                    "%s: ops/s=%.0f, avg=%.2f us, p50=%.2f us, p95=%.2f us, p99=%.2f us, elapsed=%.1f ms",
                    name, opsPerSec, avgUs, p.p50Us, p.p95Us, p.p99Us, elapsedNanos / 1_000_000.0);
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

        static Percentiles fromNanos(long[] samples) {
            if (samples.length == 0) return new Percentiles(Double.NaN, Double.NaN, Double.NaN);
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

    // ----------------------- 基础工具 -----------------------

    private static boolean canPing(RedisCommands<String, String> commands) {
        try {
            String pong = commands.ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void suppressNoisyLogging() {
        trySetLogbackLevel("io.lettuce", Level.WARN);
        trySetLogbackLevel("io.lettuce.core.protocol", Level.WARN);
        trySetLogbackLevel("io.lettuce.core.protocol.RedisStateMachine", Level.WARN);
        trySetLogbackLevel("com.rabbitmq", Level.WARN);
        trySetLogbackLevel("org.redisson", Level.WARN);
    }

    private static void trySetLogbackLevel(String loggerName, Level level) {
        try {
            org.slf4j.Logger slf4jLogger = LoggerFactory.getLogger(loggerName);
            if (slf4jLogger instanceof Logger) {
                ((Logger) slf4jLogger).setLevel(level);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int intProp(String key, int def) {
        String v = System.getProperty(key);
        if (v == null || v.trim().isEmpty()) return def;
        return Integer.parseInt(v.trim());
    }

    private static final class BenchmarkConfig {
        final String redisHost;
        final int redisPort;
        final int threads;
        final int requests;
        final long users;
        final long voucherId;
        final int stock;
        final int syncWorkMs;
        final int asyncWorkMs;
        final String rabbitHost;
        final int rabbitPort;
        final String rabbitUsername;
        final String rabbitPassword;
        final int redissonWatchdogMs;

        private BenchmarkConfig(
                String redisHost,
                int redisPort,
                int threads,
                int requests,
                long users,
                long voucherId,
                int stock,
                int syncWorkMs,
                int asyncWorkMs,
                String rabbitHost,
                int rabbitPort,
                String rabbitUsername,
                String rabbitPassword,
                int redissonWatchdogMs
        ) {
            this.redisHost = redisHost;
            this.redisPort = redisPort;
            this.threads = threads;
            this.requests = requests;
            this.users = users;
            this.voucherId = voucherId;
            this.stock = stock;
            this.syncWorkMs = syncWorkMs;
            this.asyncWorkMs = asyncWorkMs;
            this.rabbitHost = rabbitHost;
            this.rabbitPort = rabbitPort;
            this.rabbitUsername = rabbitUsername;
            this.rabbitPassword = rabbitPassword;
            this.redissonWatchdogMs = redissonWatchdogMs;
        }

        static BenchmarkConfig fromSystemProperties() {
            String redisHost = sysPropOrEnv("bench.redis.host", "REDIS_HOST", "127.0.0.1");
            int redisPort = intPropOrEnv("bench.redis.port", "REDIS_PORT", 6379);

            int threads = intProp("bench.threads", Math.max(2, Runtime.getRuntime().availableProcessors()));
            int requests = intProp("bench.requests", 50_000);
            long users = longProp("bench.users", 20_000);

            long voucherId = longProp("bench.voucherId", 1);
            int stock = intProp("bench.stock", 2_000);

            int syncWorkMs = intProp("bench.syncWorkMs", 20);
            int asyncWorkMs = intProp("bench.asyncWorkMs", 20);

            String rabbitHost = sysPropOrEnv("bench.rabbit.host", "RABBIT_HOST", "localhost");
            int rabbitPort = intPropOrEnv("bench.rabbit.port", "RABBIT_PORT", 5672);
            String rabbitUsername = sysPropOrEnv("bench.rabbit.username", "RABBIT_USERNAME", "guest");
            String rabbitPassword = sysPropOrEnv("bench.rabbit.password", "RABBIT_PASSWORD", "guest");

            int redissonWatchdogMs = intProp("bench.redisson.watchdogMs", 2000);

            return new BenchmarkConfig(
                    redisHost, redisPort,
                    threads, requests, users,
                    voucherId, stock,
                    syncWorkMs, asyncWorkMs,
                    rabbitHost, rabbitPort, rabbitUsername, rabbitPassword,
                    redissonWatchdogMs
            );
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "Redis=%s:%d, threads=%d, requests=%d, users=%d, voucherId=%d, stock=%d, syncWorkMs=%d, asyncWorkMs=%d, Rabbit=%s:%d, watchdogMs=%d",
                    redisHost, redisPort, threads, requests, users, voucherId, stock, syncWorkMs, asyncWorkMs, rabbitHost, rabbitPort, redissonWatchdogMs
            );
        }

        private static int intProp(String key, int def) {
            String v = System.getProperty(key);
            if (v == null || v.trim().isEmpty()) return def;
            return Integer.parseInt(v.trim());
        }

        private static long longProp(String key, long def) {
            String v = System.getProperty(key);
            if (v == null || v.trim().isEmpty()) return def;
            return Long.parseLong(v.trim());
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
}
