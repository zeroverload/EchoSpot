# 多级缓存（二级缓存）量化测试

本目录下的 `MultiLevelCacheBenchmarkTest` 用来量化：

> 使用 Caffeine 本地缓存 + Redis 缓存搭建二级缓存架构，提高热点数据访问速度、降低 Redis 压力。

## 运行前提

- JDK 8+（本项目 `pom.xml` 里是 `java.version=1.8`）
- 本机有可访问的 Redis（默认 `127.0.0.1:6379`）

## 运行

在项目根目录执行：

```bash
mvn -q -Dtest=MultiLevelCacheBenchmarkTest test
```

该测试会往 Redis 写入 `bench:mlc:*` 前缀的 key（`SET` 覆盖写），不会执行 `FLUSHDB` 之类的破坏性命令。

如果你看到类似 `io.lettuce.core.protocol.RedisStateMachine` 的 DEBUG 刷屏日志，通常是因为某处把 `io.lettuce` 日志级别设成了 DEBUG；该测试已在运行时尝试把 `io.lettuce*` 降到 WARN，以避免刷屏和内存占用飙升。

## 秒杀重构量化测试

对应 `HighConcurrencySeckillRebuildBenchmarkTest`（量化：Redis 分布式 ID、Redis+Lua 原子校验、乐观锁避免超卖、RabbitMQ 异步响应时间、Redisson 锁看门狗/连锁方案）。

运行：

```bash
mvn -q -Dtest=HighConcurrencySeckillRebuildBenchmarkTest test
```

- 需要 Redis（默认 `127.0.0.1:6379`）
- RabbitMQ/Redisson 部分：若本机未启动 RabbitMQ（默认 `localhost:5672`）或连接失败，会自动 `skipped`，其余 Redis/乐观锁部分仍会输出量化结果

## 可调参数

通过 JVM System Properties 调参（示例）：

```bash
mvn -q -Dtest=MultiLevelCacheBenchmarkTest test \
  -Dbench.redis.host=127.0.0.1 -Dbench.redis.port=6379 \
  -Dbench.keys=5000 -Dbench.hotKeys=100 -Dbench.hotProb=0.9 \
  -Dbench.requests=200000 -Dbench.warmupRequests=20000 -Dbench.threads=8 \
  -Dbench.payloadBytes=64 -Dbench.sampleEvery=100
```

输出会包含：

- `ops/s`：吞吐
- `avg/p50/p95/p99`：延迟（微秒）
- `redisGets`：测量窗口内 Redis `GET` 次数（可视为 Redis 压力指标）
