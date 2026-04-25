package com.zeroverload.controller;

import com.zeroverload.dto.Result;
import com.zeroverload.service.IRentalOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/rental")
public class RentalOrderController {
    @Resource
    private IRentalOrderService rentalOrderService;

    @PostMapping("/rent/{stationId}/{deviceId}")
    public Result rent(@PathVariable("stationId") Long stationId, @PathVariable("deviceId") Long deviceId) {
        return rentalOrderService.rentDevice(stationId, deviceId);
    }

    @PostMapping("/return/{orderId}")
    public Result returnDevice(@PathVariable("orderId") Long orderId) {
        return rentalOrderService.returnDevice(orderId);
    }

    @GetMapping("/active")
    public Result active() {
        return rentalOrderService.activeRental();
    }

    @GetMapping("/actives")
    public Result actives() {
        return rentalOrderService.activeRentals();
    }
}
