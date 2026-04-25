package com.zeroverload.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;

@Data
public class RentalActiveDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;
    private Long stationId;
    private Long deviceId;
    private String deviceName;
    /**
     * 1=租借中 2=已归还 3=已取消
     */
    private Integer status;
}
