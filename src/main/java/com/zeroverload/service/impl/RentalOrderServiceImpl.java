package com.zeroverload.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zeroverload.config.RentalMqConfig;
import com.zeroverload.dto.RentalActiveDTO;
import com.zeroverload.dto.RentalRentMessage;
import com.zeroverload.dto.RentalReturnMessage;
import com.zeroverload.dto.Result;
import com.zeroverload.entity.RentalOrder;
import com.zeroverload.mapper.RentalOrderMapper;
import com.zeroverload.service.IDeviceService;
import com.zeroverload.service.IRentalOrderService;
import com.zeroverload.utils.RedisIdWorker;
import com.zeroverload.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Collections;

@Slf4j
@Service
public class RentalOrderServiceImpl extends ServiceImpl<RentalOrderMapper, RentalOrder> implements IRentalOrderService {
    private static final DefaultRedisScript<Long> RENT_SCRIPT;
    private static final DefaultRedisScript<Long> RETURN_SCRIPT;

    static {
        RENT_SCRIPT = new DefaultRedisScript<>();
        RENT_SCRIPT.setLocation(new ClassPathResource("rental_rent.lua"));
        RENT_SCRIPT.setResultType(Long.class);

        RETURN_SCRIPT = new DefaultRedisScript<>();
        RETURN_SCRIPT.setLocation(new ClassPathResource("rental_return.lua"));
        RETURN_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private IDeviceService deviceService;

    @Resource
    private RedissonClient redissonClient;

    @Value("${rental.mq.enabled:true}")
    private boolean mqEnabled;

    @Value("${rental.mq.fallback-sync:true}")
    private boolean mqFallbackSync;

    @Override
    public Result rentDevice(Long stationId, Long deviceId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("rental");
        long rentTimeMillis = Instant.now().toEpochMilli();

        String orderKey = orderKey(orderId);
        String deviceName = resolveDeviceName(stationId, deviceId);
        if (deviceName == null || deviceName.trim().isEmpty()) {
            return Result.fail("设备不存在或已下线");
        }
        String activeTypeKey = activeTypeKey(userId, deviceName);
        String activeSetKey = activeSetKey(userId);
        List<String> keys = Arrays.asList(activeTypeKey, activeSetKey, orderKey);

        Long result = stringRedisTemplate.execute(
                RENT_SCRIPT,
                keys,
                String.valueOf(stationId),
                String.valueOf(deviceId),
                String.valueOf(userId),
                String.valueOf(orderId),
                String.valueOf(rentTimeMillis),
                deviceName
        );
        int r = result == null ? -1 : result.intValue();
        if (r != 0) {
            return Result.fail(rentFailMessage(r));
        }

        RentalRentMessage msg = new RentalRentMessage();
        msg.setOrderId(orderId);
        msg.setUserId(userId);
        msg.setStationId(stationId);
        msg.setDeviceId(deviceId);
        msg.setRentTimeMillis(rentTimeMillis);

        // mq.enabled=false 时不要尝试连接 RabbitMQ，否则每次请求都会触发连接超时导致“卡顿”
        if (mqEnabled) {
            try {
                rabbitTemplate.convertAndSend(RentalMqConfig.EXCHANGE, RentalMqConfig.RENT_ROUTING_KEY, JSONUtil.toJsonStr(msg));
            } catch (Exception e) {
                log.error("发送租借消息失败，orderId={}", orderId, e);
                if (!mqFallbackSync) {
                    throw new RuntimeException("发送消息失败");
                }
                // MQ 不可用时，降级为同步落库（仍然保留 Redis+Lua 原子校验作为高并发入口）
                persistRentFromMq(msg);
            }
        } else {
            persistRentFromMq(msg);
        }

        // orderId 由时间戳<<32 拼接得到，可能超过 JS Number 安全整数范围，前端需使用字符串避免精度丢失
        return Result.ok(String.valueOf(orderId));
    }

    @Override
    public Result returnDevice(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        long returnTimeMillis = Instant.now().toEpochMilli();

        String orderKey = orderKey(orderId);
        String deviceName = resolveDeviceNameFromOrder(orderKey);
        if (deviceName == null || deviceName.trim().isEmpty()) {
            return Result.fail("订单信息不完整");
        }
        String activeTypeKey = activeTypeKey(userId, deviceName);
        String activeSetKey = activeSetKey(userId);

        // 兼容旧版“一人一租”键：rental:active:{userId}
        // 如果用户仍持有旧键，先迁移到按类型维度的 activeKey + activeSetKey，避免归还失败
        migrateLegacyActiveKeyIfNeeded(userId, orderId, deviceName);

        List<String> keys = Arrays.asList(activeTypeKey, activeSetKey, orderKey);

        Long result = stringRedisTemplate.execute(
                RETURN_SCRIPT,
                keys,
                String.valueOf(userId),
                String.valueOf(orderId),
                String.valueOf(returnTimeMillis)
        );
        int r = result == null ? -1 : result.intValue();
        if (r != 0) {
            return Result.fail(returnFailMessage(r));
        }

        RentalReturnMessage msg = new RentalReturnMessage();
        msg.setOrderId(orderId);
        msg.setUserId(userId);
        msg.setReturnTimeMillis(returnTimeMillis);

        if (mqEnabled) {
            try {
                rabbitTemplate.convertAndSend(RentalMqConfig.EXCHANGE, RentalMqConfig.RETURN_ROUTING_KEY, JSONUtil.toJsonStr(msg));
            } catch (Exception e) {
                log.error("发送归还消息失败，orderId={}", orderId, e);
                if (!mqFallbackSync) {
                    throw new RuntimeException("发送消息失败");
                }
                persistReturnFromMq(msg);
            }
        } else {
            persistReturnFromMq(msg);
        }

        return Result.ok(String.valueOf(orderId));
    }

    /**
     * RabbitMQ 消费端落库（也用于 MQ 不可用时的同步降级）。
     */
    @Transactional
    public void persistRentFromMq(RentalRentMessage msg) {
        if (msg == null || msg.getOrderId() == null || msg.getUserId() == null) return;
        RLock lock = redissonClient.getLock("lock:rental:user:" + msg.getUserId());
        lock.lock();
        try {
            RentalOrder existed = getById(msg.getOrderId());
            if (existed != null) return;

            RentalOrder order = new RentalOrder();
            order.setId(msg.getOrderId());
            order.setUserId(msg.getUserId());
            order.setStationId(msg.getStationId());
            order.setDeviceId(msg.getDeviceId());
            order.setStatus(1);
            order.setRentTime(toLocalDateTime(msg.getRentTimeMillis()));
            order.setCreateTime(LocalDateTime.now());
            order.setUpdateTime(LocalDateTime.now());
            save(order);

            if (msg.getDeviceId() != null && msg.getStationId() != null) {
                deviceService.update()
                        .setSql("stock = stock - 1")
                        .eq("id", msg.getDeviceId())
                        .eq("station_id", msg.getStationId())
                        .gt("stock", 0)
                        .update();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * RabbitMQ 消费端落库（也用于 MQ 不可用时的同步降级）。
     */
    @Transactional
    public void persistReturnFromMq(RentalReturnMessage msg) {
        if (msg == null || msg.getOrderId() == null || msg.getUserId() == null) return;
        RLock lock = redissonClient.getLock("lock:rental:user:" + msg.getUserId());
        lock.lock();
        try {
            RentalOrder order = getById(msg.getOrderId());
            if (order == null) return;
            if (order.getStatus() != null && order.getStatus() == 2) return;

            update()
                    .set("status", 2)
                    .set("return_time", toLocalDateTime(msg.getReturnTimeMillis()))
                    .set("update_time", LocalDateTime.now())
                    .eq("id", msg.getOrderId())
                    .eq("user_id", msg.getUserId())
                    .eq("status", 1)
                    .update();

            if (order.getDeviceId() != null && order.getStationId() != null) {
                deviceService.update()
                        .setSql("stock = stock + 1")
                        .eq("id", order.getDeviceId())
                        .eq("station_id", order.getStationId())
                        .update();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result activeRental() {
        Long userId = UserHolder.getUser().getId();
        String activeSetKey = activeSetKey(userId);
        String orderIdStr = stringRedisTemplate.opsForSet().randomMember(activeSetKey);
        if (orderIdStr == null || orderIdStr.trim().isEmpty()) {
            // 兼容旧版键
            orderIdStr = stringRedisTemplate.opsForValue().get(legacyActiveKey(userId));
        }
        if (orderIdStr == null || orderIdStr.trim().isEmpty()) {
            return Result.ok(null);
        }
        Long orderId;
        try {
            orderId = Long.parseLong(orderIdStr.trim());
        } catch (NumberFormatException e) {
            return Result.ok(null);
        }
        String orderKey = orderKey(orderId);
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(orderKey);
        if (map == null || map.isEmpty()) {
            RentalActiveDTO dto = new RentalActiveDTO();
            dto.setOrderId(orderId);
            return Result.ok(dto);
        }
        RentalActiveDTO dto = new RentalActiveDTO();
        dto.setOrderId(orderId);
        dto.setStationId(parseLong(map.get("stationId")));
        dto.setDeviceId(parseLong(map.get("deviceId")));
        dto.setDeviceName(parseString(map.get("deviceName")));
        dto.setStatus(parseInt(map.get("status")));
        return Result.ok(dto);
    }

    private static String rentFailMessage(int code) {
        if (code == 1) return "设备库存不足";
        if (code == 2) return "当前用户存在同类未归还租借";
        if (code == 3) return "设备库存未初始化";
        return "租借失败(code=" + code + ")";
    }

    private static String returnFailMessage(int code) {
        if (code == 2) return "无可归还的租借或订单不匹配";
        if (code == 4) return "订单已归还/已关闭";
        if (code == 5) return "订单不存在或用户不匹配";
        if (code == 6) return "订单信息不完整";
        return "归还失败(code=" + code + ")";
    }

    public static String stockKey(Long stationId, Long deviceId) {
        return "rental:stock:" + stationId + ":" + deviceId;
    }

    public static String activeTypeKey(Long userId, String deviceName) {
        return "rental:active:" + userId + ":" + (deviceName == null ? "" : deviceName.trim());
    }

    public static String activeSetKey(Long userId) {
        return "rental:active:set:" + userId;
    }

    public static String legacyActiveKey(Long userId) {
        return "rental:active:" + userId;
    }

    public static String orderKey(Long orderId) {
        return "rental:order:" + orderId;
    }

    private static LocalDateTime toLocalDateTime(Long epochMillis) {
        if (epochMillis == null) return LocalDateTime.now();
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private static Long parseLong(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String parseString(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 设备“同类”维度：当前使用 tb_device.name 作为分类键（如：共享充电宝 / 共享雨伞）。
     */
    private String resolveDeviceName(Long stationId, Long deviceId) {
        if (stationId == null || deviceId == null) return null;
        com.zeroverload.entity.Device device = deviceService.lambdaQuery()
                .select(com.zeroverload.entity.Device::getName)
                .eq(com.zeroverload.entity.Device::getId, deviceId)
                .eq(com.zeroverload.entity.Device::getStationId, stationId)
                .eq(com.zeroverload.entity.Device::getStatus, 1)
                .one();
        return device == null ? null : device.getName();
    }

    private String resolveDeviceNameFromOrder(String orderKey) {
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(orderKey);
        if (map != null && !map.isEmpty()) {
            String name = parseString(map.get("deviceName"));
            if (name != null) return name;
            Long stationId = parseLong(map.get("stationId"));
            Long deviceId = parseLong(map.get("deviceId"));
            if (stationId != null && deviceId != null) {
                return resolveDeviceName(stationId, deviceId);
            }
        }
        return null;
    }

    private void migrateLegacyActiveKeyIfNeeded(Long userId, Long orderId, String deviceName) {
        if (userId == null || orderId == null || deviceName == null) return;
        String legacyKey = legacyActiveKey(userId);
        String legacyOrderId = stringRedisTemplate.opsForValue().get(legacyKey);
        if (legacyOrderId == null || !legacyOrderId.trim().equals(String.valueOf(orderId))) {
            return;
        }

        String activeTypeKey = activeTypeKey(userId, deviceName);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(activeTypeKey))) {
            stringRedisTemplate.delete(legacyKey);
            return;
        }

        // 把旧键迁移到新键（并补齐订单里的 deviceName 字段）
        stringRedisTemplate.opsForValue().set(activeTypeKey, String.valueOf(orderId));
        stringRedisTemplate.opsForSet().add(activeSetKey(userId), String.valueOf(orderId));
        String orderKey = orderKey(orderId);
        if (deviceName != null && !deviceName.trim().isEmpty()) {
            stringRedisTemplate.opsForHash().putIfAbsent(orderKey, "deviceName", deviceName.trim());
        }
        stringRedisTemplate.delete(legacyKey);
    }

    @Override
    public Result activeRentals() {
        Long userId = UserHolder.getUser().getId();
        String activeSetKey = activeSetKey(userId);
        Set<String> orderIdSet = stringRedisTemplate.opsForSet().members(activeSetKey);
        if (orderIdSet == null || orderIdSet.isEmpty()) {
            // 兼容旧版键：尽量迁移到新结构
            String legacyOrderId = stringRedisTemplate.opsForValue().get(legacyActiveKey(userId));
            if (legacyOrderId != null && !legacyOrderId.trim().isEmpty()) {
                try {
                    Long orderId = Long.parseLong(legacyOrderId.trim());
                    String orderKey = orderKey(orderId);
                    String deviceName = resolveDeviceNameFromOrder(orderKey);
                    if (deviceName != null) {
                        migrateLegacyActiveKeyIfNeeded(userId, orderId, deviceName);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            orderIdSet = stringRedisTemplate.opsForSet().members(activeSetKey);
        }
        if (orderIdSet == null || orderIdSet.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<RentalActiveDTO> list = new ArrayList<>(orderIdSet.size());
        for (String orderIdStr : orderIdSet) {
            if (orderIdStr == null || orderIdStr.trim().isEmpty()) continue;
            Long orderId;
            try {
                orderId = Long.parseLong(orderIdStr.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            String orderKey = orderKey(orderId);
            Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(orderKey);
            RentalActiveDTO dto = new RentalActiveDTO();
            dto.setOrderId(orderId);
            if (map != null && !map.isEmpty()) {
                dto.setStationId(parseLong(map.get("stationId")));
                dto.setDeviceId(parseLong(map.get("deviceId")));
                dto.setDeviceName(parseString(map.get("deviceName")));
                dto.setStatus(parseInt(map.get("status")));
            }
            list.add(dto);
        }
        return Result.ok(list);
    }
}
