package com.zeroverload.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_rental_order")
public class RentalOrder implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long userId;

    private Long stationId;

    private Long deviceId;

    /**
     * 1=租借中 2=已归还 3=已取消
     */
    private Integer status;

    private LocalDateTime rentTime;

    private LocalDateTime returnTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

