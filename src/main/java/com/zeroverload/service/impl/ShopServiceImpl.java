package com.zeroverload.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zeroverload.dto.Result;
import com.zeroverload.entity.Shop;
import com.zeroverload.mapper.ShopMapper;
import com.zeroverload.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zeroverload.utils.CacheClient;
import com.zeroverload.utils.RedisData;
import com.zeroverload.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.zeroverload.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient clientClient;
    @Override
    public Result queryById(Long id){
        /*缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //Shop shop = clientClient
               // .queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        if(shop==null){
//            return Result.fail("店铺不存在！");
//        }
         */

        //用逻辑过期解决缓存击穿
        // Shop shop = queryWithLogicalExpire(id);
        Shop shop = clientClient.
                queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if(shop==null){

            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);

    }


    @PostConstruct // 关键注解：项目启动时自动执行
    public void preloadShopCache() {
        // 1. 查询MySQL中所有店铺数据
        List<Shop> shopList = this.list(); // mybatis-plus的list()方法，查全表

        // 2. 遍历每个店铺，写入Redis（逻辑过期格式）
        for (Shop shop : shopList) {
            RedisData redisData = new RedisData();
            redisData.setData(shop);
            // 设置30分钟逻辑过期（和原有逻辑一致）
            redisData.setExpireTime(LocalDateTime.now().plusMinutes(CACHE_SHOP_TTL));
            // 写入Redis：key=shop:1，value=RedisData的JSON字符串
            stringRedisTemplate.opsForValue().set(
                    CACHE_SHOP_KEY + shop.getId(),
                    JSONUtil.toJsonStr(redisData)
            );
        }

        // 3. 预热地理位置数据
        Set<Integer> typeIds = new HashSet<>();
        for (Shop shop : shopList) {
            if (shop.getTypeId() != null) {
                typeIds.add(shop.getTypeId().intValue());
            }
        }

        for (Integer typeId : typeIds) {
            syncShopGeoData(typeId);
        }

        System.out.println("✅ 店铺缓存预热完成！Redis中已初始化所有店铺数据");
    }

    // 同步指定类型的商铺数据到Redis Geo结构中
    public void syncShopGeoData(Integer typeId) {
        // 1. 查询该类型下的所有商铺（包含经纬度）
        List<Shop> shops = query().eq("type_id", typeId).list();
        if (shops.isEmpty()) {
            System.out.println("typeId=" + typeId + "无商铺数据，无需同步");
            return;
        }

        // 2. 写入Redis Geo
        String key = SHOP_GEO_KEY + typeId;
        // 先清空旧数据（避免重复）
        stringRedisTemplate.delete(key);
        // 批量写入
        for (Shop shop : shops) {
            // 确保经纬度非空
            if (shop.getX() == null || shop.getY() == null) {
                System.err.println("商铺ID=" + shop.getId() + "经纬度为空，跳过");
                continue;
            }
            // GEOADD key 经度 纬度 成员
            stringRedisTemplate.opsForGeo().add(
                    key,
                    new RedisGeoCommands.GeoLocation<>(
                            shop.getId().toString(),
                            new Point(shop.getX(), shop.getY())
                    )
            );
        }
        System.out.println("同步typeId=" + typeId + "的商铺Geo数据完成，数量=" + shops.size());
    }
//    // 原有代码不变，在类的任意位置（比如queryById方法下方）添加这个方法
//    @PostConstruct // 关键注解：项目启动时自动执行
//    public void preloadShopCache() {
//        // 1. 查询MySQL中所有店铺数据
//        List<Shop> shopList = this.list(); // mybatis-plus的list()方法，查全表
//        // 2. 遍历每个店铺，写入Redis（逻辑过期格式）
//        for (Shop shop : shopList) {
//            RedisData redisData = new RedisData();
//            redisData.setData(shop);
//            // 设置30分钟逻辑过期（和你原有逻辑一致）
//            redisData.setExpireTime(LocalDateTime.now().plusMinutes(CACHE_SHOP_TTL));
//            // 写入Redis：key=shop:1，value=RedisData的JSON字符串
//            stringRedisTemplate.opsForValue().set(
//                    CACHE_SHOP_KEY + shop.getId(),
//                    JSONUtil.toJsonStr(redisData)
//            );
//        }
//        System.out.println("✅ 店铺缓存预热完成！Redis中已初始化所有店铺数据");
//    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
      /*   public Shop queryWithLogicalExpire(Long id)   {
//        //1.尝试从Redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
//        //2.判断缓存是否存在
//        if(StrUtil.isBlank(shopJson)) { //判断字符串既不为null，也不是空字符串(""),且也不是空白字符
//            //3.不存在，返回商铺信息
//            return null;
//
//        }
//
//        //4.存在，将json反序列化为对象
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        //5.判断是否过期
//        if(expireTime.isAfter(LocalDateTime.now())) {
//            //5.1.未过期，直接返回店铺信息
//            return shop;
//        }
//        //5.2.已过期，需要返回缓存重建
//        //6.缓存重建
//        //6.1.获取互斥锁
//        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
//        boolean isLock = tryLock(lockKey);
//        //6.2.判断是否获取锁成功
//        if(isLock){
//            //  6.3.成功，开启独立线程实现缓存重建
//            CACHE_REBUILD_EXECUTOR.submit(()->{
//                try {
//                    //重建缓存
//                    this.saveShop2Redis(id,20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }finally {
//                    //释放锁
//                    unLock(lockKey);
//                }
//            });
//
//        }
//
//        //6.4.返回过期的商铺信息
//        return shop;
//
//    }
    /**
     * 互斥锁解决缓存穿透
     * @param id
     * @return
     * @throws InterruptedException
     */
    /* public Shop queryWithMutex(Long id) throws InterruptedException {
        //1.尝试从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //2.判断缓存是否存在
        if(StrUtil.isNotBlank(shopJson)) { //判断字符串既不为null，也不是空字符串(""),且也不是空白字符
            //3.存在，返回商铺信息
            return JSONUtil.toBean(shopJson, Shop.class);

        }
        //判断是否为空值
        if(shopJson!=null){
            return null;
        }
        //4.实现缓存重建
        //4.1获取互斥锁
        String lockKey="lock:shop:"+id;
        Shop shop=null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            if(!isLock) {
                //4.3失败，则休眠重试
               Thread.sleep(50);
                return queryWithMutex(id);
            }

            //4.4.成功，根据id查询数据库
            shop = getById(id);
            //模拟重建的延迟
            Thread.sleep(200);
            //5.判断数据库中是否存在
            if(shop==null){
                //6.不存在，返回错误状态码
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //7.存在，写入redis，返回商铺信息
            String newShopJson = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,newShopJson,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            unLock(lockKey);
        }

        //9.返回
        return shop;

    }*/
    /**
     * 缓存穿透
     * @param id
     * @return
     */
    /*
//        //1.尝试从Redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
//        //2.判断缓存是否存在
//        if(StrUtil.isNotBlank(shopJson)) { //判断字符串既不为null，也不是空字符串(""),且也不是空白字符
//            //3.存在，返回商铺信息
//            return JSONUtil.toBean(shopJson, Shop.class);
//
//        }
//        //判断是否为空值
//        if(shopJson!=null){
//            return null;
//        }
//        //4.不存在，根据id查询数据库
//        Shop shop = getById(id);
//        //5.判断数据库中是否存在
//        if(shop==null){
//            //6.不存在，返回错误状态码
//            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
//            return null;
//        }
//        //7.存在，写入redis，返回商铺信息
//        String newShopJson = JSONUtil.toJsonStr(shop);
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,newShopJson,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//
//        return shop;
//
//    }

     */


    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.封装成逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        //1.先修改数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.是否根据坐标查询
        if(x==null||y==null){
            //不需要坐标查询，该数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        //2.计算分页参数
        int from=(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end=current*SystemConstants.DEFAULT_PAGE_SIZE;

        //3.查询redis，按照距离排序，分页。结果：shopId,distance
        String key = SHOP_GEO_KEY + typeId;
        //在 Redis 中按地理坐标（x, y）查询距离当前用户位置 5000 米内的商店
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        if(results==null){
            return Result.ok(Collections.emptyList());
        }
        //4.解析出id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size()<=from){
            return Result.ok(Collections.emptyList());
        }
        //4.1.截取从from到end部分  跳过前 from 个结果，实现分页
        List<Long> ids=new ArrayList<>(list.size());
        Map<String,Distance> distanceMap=new HashMap<>(list.size());
        list.stream().skip(from).forEach(result->{
            //4.2.获取店铺id
            String shopIdStr=result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        //5.根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query()
                .in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //6.返回
        return Result.ok(shops);
    }
}
