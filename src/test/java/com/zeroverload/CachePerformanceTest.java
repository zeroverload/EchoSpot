package com.zeroverload;

import com.zeroverload.dto.Result;
import com.zeroverload.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
public class CachePerformanceTest {

    @Autowired
    private IShopService shopService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void testCachePerformance() throws InterruptedException {
        // 确保删除缓存
        String cacheKey = "cache:shop:1"; // 注意实际的缓存键可能不同
        stringRedisTemplate.delete(cacheKey);

        // 等待一段时间确保缓存真正被删除
        Thread.sleep(100);

        // 验证缓存确实被删除了
        String cachedValue = stringRedisTemplate.opsForValue().get(cacheKey);
        System.out.println("缓存是否已被清除：" + (cachedValue == null));

        // 测试首次查询（应该从数据库加载）
        long startFirst = System.currentTimeMillis();
        Result firstResult = shopService.queryById(1L);
        long firstQueryTime = System.currentTimeMillis() - startFirst;

        System.out.println("首次查询耗时（缓存未命中，从数据库加载）：" + firstQueryTime + "ms");

        // 等待缓存写入完成
        Thread.sleep(100);

        // 验证缓存是否已写入
        cachedValue = stringRedisTemplate.opsForValue().get(cacheKey);
        System.out.println("缓存是否已写入：" + (cachedValue != null));

        // 测试第二次查询（应该从缓存加载）
        long startSecond = System.currentTimeMillis();
        Result secondResult = shopService.queryById(1L);
        long secondQueryTime = System.currentTimeMillis() - startSecond;

        System.out.println("第二次查询耗时（缓存命中）：" + secondQueryTime + "ms");

        // 计算性能提升百分比
        if(firstQueryTime > 0) {
            double improvement = ((double)(firstQueryTime - secondQueryTime) / firstQueryTime) * 100;
            System.out.println("性能提升：" + improvement + "%");
        }
    }

}