package com.zeroverload.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zeroverload.entity.Device;
import com.zeroverload.mapper.DeviceMapper;
import com.zeroverload.service.IDeviceService;
import org.springframework.stereotype.Service;

@Service
public class DeviceServiceImpl extends ServiceImpl<DeviceMapper, Device> implements IDeviceService {
}

