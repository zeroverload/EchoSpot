package com.zeroverload.rentalbench;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需要本地 Redis（默认 127.0.0.1:6379）。Redis 不可用时自动跳过。
 *
 * 验证点：
 * - 不超卖：ok <= stock 且最终 stock >= 0
 * - 不重复租借：同一 user 只允许存在一个 active
 */
public class DeviceRentalLuaAtomicityTest {
    @Test
    void rent_script_is_atomic_no_oversell_no_duplicate() throws Exception {
        String host = System.getProperty("bench.redis.host", "127.0.0.1");
        int port = Integer.parseInt(System.getProperty("bench.redis.port", "6379"));

        RedisURI redisUri = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withDatabase(0)
                .withTimeout(Duration.ofSeconds(2))
                .build();

        RedisClient client = RedisClient.create(redisUri);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            RedisCommands<String, String> redis = connection.sync();
            Assumptions.assumeTrue(canPing(redis), "Redis 未就绪，跳过 DeviceRentalLuaAtomicityTest");

            String rentLua = readClasspath("rental_rent.lua");
            String stationId = "1";
            String deviceId = "1";
            String stockKey = "rental:stock:" + stationId + ":" + deviceId;
            redis.set(stockKey, "50");

            long users = 200;
            int threads = 16;
            int requests = 5000;

            AtomicLong ok = new AtomicLong();
            AtomicLong outOfStock = new AtomicLong();
            AtomicLong duplicate = new AtomicLong();
            AtomicLong other = new AtomicLong();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            int perThread = requests / threads;
            int remainder = requests % threads;
            for (int t = 0; t < threads; t++) {
                int count = perThread + (t < remainder ? 1 : 0);
                pool.execute(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        ThreadLocalRandom rnd = ThreadLocalRandom.current();
                        for (int i = 0; i < count; i++) {
                            long userId = rnd.nextLong(users) + 1;
                            long orderId = rnd.nextLong(Long.MAX_VALUE);
                            String activeKey = "rental:active:" + userId;
                            String orderKey = "rental:order:" + orderId;
                            Object res = redis.eval(
                                    rentLua,
                                    ScriptOutputType.INTEGER,
                                    new String[]{activeKey, orderKey},
                                    stationId, deviceId, String.valueOf(userId), String.valueOf(orderId), "0"
                            );
                            long code = (res instanceof Number) ? ((Number) res).longValue() : Long.parseLong(String.valueOf(res));
                            if (code == 0) ok.incrementAndGet();
                            else if (code == 1) outOfStock.incrementAndGet();
                            else if (code == 2) duplicate.incrementAndGet();
                            else other.incrementAndGet();
                        }
                    } catch (Throwable ex) {
                        failure.compareAndSet(null, ex);
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();
            done.await();
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);

            Throwable th = failure.get();
            if (th != null) throw new RuntimeException(th);

            long finalStock = Long.parseLong(Objects.requireNonNull(redis.get(stockKey)));
            assertTrue(ok.get() <= 50, "ok should be <= initial stock");
            assertTrue(finalStock >= 0, "final stock should be >= 0");

            // 进一步：active key 数量 <= users 且不会超过 ok（每个 ok 会占用一个 active）
            long activeCount = 0;
            for (int i = 1; i <= users; i++) {
                activeCount += redis.exists("rental:active:" + i);
            }
            assertTrue(activeCount <= users, "activeCount <= users");
            assertTrue(activeCount <= ok.get(), "activeCount <= ok");

            System.out.println("ok=" + ok.get() + ", outOfStock=" + outOfStock.get() + ", duplicate=" + duplicate.get() + ", other=" + other.get() + ", finalStock=" + finalStock);
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

    private static String readClasspath(String name) throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("missing classpath resource: " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
