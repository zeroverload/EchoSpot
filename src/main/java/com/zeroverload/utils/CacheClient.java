package com.zeroverload.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    private final Cache<String, String> l1Cache;
    private final long redisTtlJitterSeconds;


    public CacheClient(
            StringRedisTemplate stringRedisTemplate,
            @Value("${cache.l1.enabled:true}") boolean l1Enabled,
            @Value("${cache.l1.maximum-size:10000}") long l1MaximumSize,
            @Value("${cache.l1.expire-seconds:60}") long l1ExpireSeconds,
            @Value("${cache.redis.ttl-jitter-seconds:0}") long redisTtlJitterSeconds
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisTtlJitterSeconds = Math.max(0L, redisTtlJitterSeconds);
        if (!l1Enabled) {
            this.l1Cache = null;
        } else {
            this.l1Cache = Caffeine.newBuilder()
                    .maximumSize(Math.max(1L, l1MaximumSize))
                    .expireAfterWrite(Math.max(1L, l1ExpireSeconds), TimeUnit.SECONDS)
                    .build();
        }
    }


    public void set(String key, Object value, Long time, TimeUnit unit){
        String json = JSONUtil.toJsonStr(value);
        long seconds = unit.toSeconds(time);
        seconds = withJitterSeconds(seconds);
        stringRedisTemplate.opsForValue().set(key, json, seconds, TimeUnit.SECONDS);
        l1Put(key, json);
    }

    public void setWithLogicalExpire(String key,Object value,Long time,TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        String json = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key, json);
        l1Put(key, json);
    }

    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key=keyPrefix+id;

        String l1 = l1Get(key);
        if (l1 != null) {
            if (l1.isEmpty()) {
                return null;
            }
            return JSONUtil.toBean(l1, type);
        }
        //1.尝试从Redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存是否存在
        if(StrUtil.isNotBlank(json)) { //判断字符串既不为null，也不是空字符串(""),且也不是空白字符
            //3.存在，返回商铺信息
            l1Put(key, json);
            return JSONUtil.toBean(json, type);

        }
        //判断是否为空值
        if(json!=null){
            l1Put(key, "");
            return null;
        }
        //4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //5.判断数据库中是否存在
        if(r==null){
            //6.不存在，返回错误状态码
            long seconds = withJitterSeconds(TimeUnit.MINUTES.toSeconds(RedisConstants.CACHE_NULL_TTL));
            stringRedisTemplate.opsForValue().set(key,"",seconds,TimeUnit.SECONDS);
            l1Put(key, "");
            return null;
        }
        //7.存在，写入redis，返回商铺信息
       this.set(key,r,time,unit);

        return r;

    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key=keyPrefix+id;

        String l1 = l1Get(key);
        String json;
        if (StrUtil.isNotBlank(l1)) {
            json = l1;
        } else if (l1 != null) {
            return null;
        } else {
            //1.尝试从Redis查询商铺缓存
            json = stringRedisTemplate.opsForValue().get(key);
        }
        //2.判断缓存是否存在
        if(StrUtil.isBlank(json)) { //判断字符串既不为null，也不是空字符串(""),且也不是空白字符
            //3.不存在，返回商铺信息
            return null;

        }

        l1Put(key, json);

        //4.存在，将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R shop = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            //5.1.未过期，直接返回店铺信息
            return shop;
        }
        //5.2.已过期，需要返回缓存重建
        //6.缓存重建
        //6.1.获取互斥锁
        String lockKey="lock:cache:" + key;
        boolean isLock = tryLock(lockKey);
        //6.2.判断是否获取锁成功
        if(isLock){
            //  6.3.成功，开启独立线程实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                   //查询数据库
                    R r1= dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });

        }

        //6.4.返回过期的商铺信息
        return shop;

    }
    /**
     * 创建锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 封闭锁
     * @param key
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    private String l1Get(String key) {
        return l1Cache == null ? null : l1Cache.getIfPresent(key);
    }

    private void l1Put(String key, String value) {
        if (l1Cache == null) return;
        l1Cache.put(key, value == null ? "" : value);
    }

    private long withJitterSeconds(long baseSeconds) {
        if (baseSeconds <= 0) return 1L;
        if (redisTtlJitterSeconds <= 0) return baseSeconds;
        long jitter = ThreadLocalRandom.current().nextLong(redisTtlJitterSeconds + 1);
        return baseSeconds + jitter;
    }
}
