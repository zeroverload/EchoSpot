package com.zeroverload;

import com.zeroverload.entity.Shop;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.geo.Metrics;
import javax.annotation.Resource;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@SpringBootTest
public class GeoPerformanceTest {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private DataSource dataSource;

    // 简单配置：表名与redis key 前缀（根据项目常量可调整）
    private static final String REDIS_GEO_KEY_PREFIX = "shop:geo:"; // 注意：仓库中使用 RedisConstants.SHOP_GEO_KEY
    private static final String SHOP_TABLE = "tb_shop"; // 根据实际表名调整（hmdp 中可能为 shop）

    private JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(dataSource);
    }

    // 代表一次查询参数
    static class QueryParams {
        double lon;
        double lat;
        double radiusKm;
        int limit;
        int offset;

        QueryParams(double lon, double lat, double radiusKm, int limit, int offset) {
            this.lon = lon;
            this.lat = lat;
            this.radiusKm = radiusKm;
            this.limit = limit;
            this.offset = offset;
        }
    }

    static class PerRequestRecord {
        String engine; // redis/mysql
        String scenario;
        long latencyMs;
        int resultCount;
        boolean error;

        PerRequestRecord(String engine, String scenario, long latencyMs, int resultCount, boolean error) {
            this.engine = engine;
            this.scenario = scenario;
            this.latencyMs = latencyMs;
            this.resultCount = resultCount;
            this.error = error;
        }
    }

    // Redis 查询实现（按距离升序，返回 limit 页）
    List<Map<String, Object>> redisQuery(String geoKey, QueryParams p) {
        String key = geoKey;
        // Redis Geo: GeoReference.fromCoordinate(lon, lat)
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(p.lon, p.lat),
                        new Distance(p.radiusKm, Metrics.KILOMETERS),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(p.offset + p.limit));
        if (results == null) return Collections.emptyList();
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        int from = p.offset;
        if (list.size() <= from) return Collections.emptyList();
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> sub = list.stream().skip(from).limit(p.limit).collect(Collectors.toList());
        List<Map<String, Object>> out = new ArrayList<>();
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> r : sub) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", r.getContent().getName());
            m.put("distance_km", r.getDistance() == null ? null : r.getDistance().getValue());
            out.add(m);
        }
        return out;
    }

    // MySQL 查询实现（Haversine + bounding box），要求表含 latitude, longitude 字段
    List<Map<String, Object>> mysqlQuery(QueryParams p, int limit, int offset) {
        // bounding box approximation
        double lat = p.lat;
        double lon = p.lon;
        double radiusKm = p.radiusKm;
        double latDelta = radiusKm / 111.0; // approx degrees
        double lonDelta = radiusKm / (111.320 * Math.cos(Math.toRadians(lat)));
        double latMin = lat - latDelta;
        double latMax = lat + latDelta;
        double lonMin = lon - lonDelta;
        double lonMax = lon + lonDelta;

        // Haversine formula in SQL (distance in km)
        String sql = "SELECT id, name, x AS longitude, y AS latitude, (6371 * acos(cos(radians(?)) * cos(radians(y)) * cos(radians(x) - radians(?)) + sin(radians(?)) * sin(radians(y)))) AS distance_km " +
                "FROM " + SHOP_TABLE + " WHERE y BETWEEN ? AND ? AND x BETWEEN ? AND ? " +
                "HAVING distance_km <= ? ORDER BY distance_km ASC LIMIT ? OFFSET ?";

        List<Map<String, Object>> rows = jdbcTemplate().query(sql, new Object[]{p.lat, p.lon, p.lat, latMin, latMax, lonMin, lonMax, radiusKm, limit, offset}, (ResultSet rs, int rowNum) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", rs.getString("id"));
            m.put("distance_km", rs.getDouble("distance_km"));
            return m;
        });
        return rows;
    }

    // 简单的并发基准 loop：对同一 query 参数集合并发执行多次
    Map<String, Object> benchmarkLoop(String engine, String redisKey, List<QueryParams> queries, int concurrency, int iterations) throws InterruptedException {
        List<PerRequestRecord> records = Collections.synchronizedList(new ArrayList<>());
        ExecutorService es = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(concurrency);

        // Split total iterations among threads
        int perThread = Math.max(1, iterations / concurrency);
        for (int t = 0; t < concurrency; t++) {
            es.submit(() -> {
                try {
                    Random rnd = new Random();
                    for (int i = 0; i < perThread; i++) {
                        QueryParams q = queries.get(rnd.nextInt(queries.size()));
                        long start = System.nanoTime();
                        boolean err = false;
                        List<Map<String, Object>> res = null;
                        try {
                            if ("redis".equals(engine)) {
                                res = redisQuery(redisKey, q);
                            } else {
                                res = mysqlQuery(q, q.limit, q.offset);
                            }
                        } catch (Exception ex) {
                            err = true;
                        }
                        long end = System.nanoTime();
                        long latencyMs = TimeUnit.NANOSECONDS.toMillis(end - start);
                        int rc = res == null ? 0 : res.size();
                        records.add(new PerRequestRecord(engine, engine + "-bench", latencyMs, rc, err));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        es.shutdownNow();

        // compute percentiles
        List<Long> latencies = records.stream().filter(r -> !r.error).map(r -> r.latencyMs).sorted().collect(Collectors.toList());
        double p50 = percentile(latencies, 50);
        double p95 = percentile(latencies, 95);
        double p99 = percentile(latencies, 99);
        double avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        Map<String, Object> summary = new HashMap<>();
        summary.put("engine", engine);
        summary.put("requests", records.size());
        summary.put("errors", records.stream().filter(r -> r.error).count());
        summary.put("p50_ms", p50);
        summary.put("p95_ms", p95);
        summary.put("p99_ms", p99);
        summary.put("avg_ms", avg);
        // write CSV of per-request records to target directory for later analysis
        try {
            writeRecordsCsv(engine + "-records.csv", records);
        } catch (IOException ignored) {
        }
        return summary;
    }

    static double percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil((p / 100.0) * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    void writeRecordsCsv(String fileName, List<PerRequestRecord> records) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter("target/" + fileName))) {
            w.write("engine,scenario,latency_ms,result_count,error\n");
            for (PerRequestRecord r : records) {
                w.write(String.format("%s,%s,%d,%d,%b\n", r.engine, r.scenario, r.latencyMs, r.resultCount, r.error));
            }
        }
    }

    // 一个示例测试场景：从 DB 同步 Redis 后做简单对比
    @Test
    public void runBasicGeoPerformance() throws InterruptedException {
        // 配置：根据你本地环境调整
        String redisKey = "shop:geo:1"; // 示例 typeId=1 的 key，或者使用全体数据的 key

        // prepare queries: center point + radii
        List<QueryParams> queries = new ArrayList<>();
        // 请根据目标区域设置合适的中心点（示例：北京天安门附近）
        double lon = 116.3974;
        double lat = 39.9087;
        queries.add(new QueryParams(lon, lat, 1.0, 20, 0));
        queries.add(new QueryParams(lon, lat, 5.0, 20, 0));
        queries.add(new QueryParams(lon, lat, 20.0, 50, 0));

        int concurrency = 8;
        int iterations = 200; // 总请求数大致为 concurrency * perThread

        System.out.println("=== Running Redis benchmark ===");
        Map<String, Object> redisSummary = null;
        try {
            redisSummary = benchmarkLoop("redis", redisKey, queries, concurrency, iterations);
            System.out.println(redisSummary);
        } catch (Exception e) {
            System.err.println("redis benchmark failed: " + e.getMessage());
        }

        System.out.println("=== Running MySQL benchmark ===");
        Map<String, Object> mysqlSummary = null;
        try {
            mysqlSummary = benchmarkLoop("mysql", redisKey, queries, concurrency, iterations);
            System.out.println(mysqlSummary);
        } catch (Exception e) {
            System.err.println("mysql benchmark failed: " + e.getMessage());
        }

        if (redisSummary != null && mysqlSummary != null) {
            double p95Redis = ((Number) redisSummary.getOrDefault("p95_ms", 0)).doubleValue();
            double p95Mysql = ((Number) mysqlSummary.getOrDefault("p95_ms", 0)).doubleValue();
            if (p95Mysql > 0) {
                double improve = (p95Mysql - p95Redis) / p95Mysql * 100.0;
                System.out.printf("P95 MySQL=%.2f ms, Redis=%.2f ms, improve=%.2f%%\n", p95Mysql, p95Redis, improve);
            }
        }
    }

}
