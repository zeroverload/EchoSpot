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
@TableName("tb_device")
public class Device implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long stationId;

    private String name;

    private String model;

    /**
     * 站点维度的可租借库存（DB 侧兜底；高并发路径以 Redis 为准）
     */
    private Integer stock;

    /**
     * 0=下线 1=在线
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

