package com.zeroverload.config;

import com.zeroverload.entity.Device;
import com.zeroverload.service.IDeviceService;
import com.zeroverload.service.impl.RentalOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

@Component
@Slf4j
public class RentalStockWarmupRunner implements CommandLineRunner {
    @Resource
    private IDeviceService deviceService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(String... args) {
        try {
            List<Device> devices = deviceService.lambdaQuery()
                    .eq(Device::getStatus, 1)
                    .list();
            for (Device device : devices) {
                if (device.getStationId() == null || device.getId() == null || device.getStock() == null) continue;
                String key = RentalOrderServiceImpl.stockKey(device.getStationId(), device.getId());
                stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(device.getStock()));
            }
            log.info("Rental stock warmup done, devices={}", devices.size());
        } catch (Exception e) {
            log.warn("Rental stock warmup skipped: {}", e.getMessage());
        }
    }
}

