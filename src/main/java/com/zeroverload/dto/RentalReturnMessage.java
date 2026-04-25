package com.zeroverload.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class RentalReturnMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long orderId;
    private Long userId;
    private Long returnTimeMillis;
}

