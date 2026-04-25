package com.zeroverload.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class RentalRentMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long orderId;
    private Long userId;
    private Long stationId;
    private Long deviceId;
    private Long rentTimeMillis;
}

