package com.zeroverload.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zeroverload.entity.Station;
import com.zeroverload.mapper.StationMapper;
import com.zeroverload.service.IStationService;
import org.springframework.stereotype.Service;

@Service
public class StationServiceImpl extends ServiceImpl<StationMapper, Station> implements IStationService {
}

