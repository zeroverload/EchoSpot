package com.zeroverload.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zeroverload.dto.Result;
import com.zeroverload.entity.RentalOrder;

public interface IRentalOrderService extends IService<RentalOrder> {
    Result rentDevice(Long stationId, Long deviceId);

    Result returnDevice(Long orderId);

    Result activeRental();

    /**
     * 当前用户所有租借中的订单（用于支持“不同类型可同时租借”）。
     */
    Result activeRentals();
}
