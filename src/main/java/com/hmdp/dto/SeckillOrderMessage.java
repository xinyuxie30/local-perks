package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀下单消息，同时用于下单投递与延迟关单投递
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单id，由 RedisIdWorker 生成，同时作为订单主键，重复投递会撞主键
     */
    private Long orderId;

    private Long userId;

    private Long voucherId;

    public SeckillOrderMessage(Long userId, Long voucherId) {
        this.userId = userId;
        this.voucherId = voucherId;
    }
}
