package com.hmdp.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 抢购结果轮询视图：订单落库前只有排队状态
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * PENDING 排队中 / CREATED 已落库 / FAILED 抢购失败 / NOT_FOUND 订单不存在
     */
    private String state;

    /**
     * 仅 state 为 CREATED 时有值
     */
    private Integer status;

    /**
     * 仅 state 为 FAILED 时有值：失败原因（库存不足 / 不能重复下单等）
     */
    private String failReason;

    /**
     * 超出 JS 安全整数范围，序列化成字符串
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;
}
