package com.zeroverload.controller;

import com.zeroverload.dto.Result;
import com.zeroverload.entity.Device;
import com.zeroverload.service.IDeviceService;
import com.zeroverload.service.impl.RentalOrderServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/station")
public class StationDeviceController {
    @Resource
    private IDeviceService deviceService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping("/{stationId}/devices")
    public Result devices(@PathVariable("stationId") Long stationId) {
        List<Device> devices = deviceService.lambdaQuery()
                .eq(Device::getStationId, stationId)
                .eq(Device::getStatus, 1)
                .list();
        List<String> keys = new ArrayList<>(devices.size());
        for (Device device : devices) {
            keys.add(RentalOrderServiceImpl.stockKey(stationId, device.getId()));
        }

        List<String> stocks = keys.isEmpty() ? new ArrayList<>() : stringRedisTemplate.opsForValue().multiGet(keys);
        for (int i = 0; i < devices.size(); i++) {
            Device device = devices.get(i);
            String key = keys.get(i);
            String stock = (stocks == null || stocks.size() <= i) ? null : stocks.get(i);
            if (stock != null) {
                try {
                    device.setStock(Integer.parseInt(stock));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            if (device.getStock() != null) {
                // 初始化 Redis 库存（仅当 key 不存在时）
                stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(device.getStock()));
            }
        }
        return Result.ok(devices);
    }
}
