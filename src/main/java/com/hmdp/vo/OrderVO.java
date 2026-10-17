package com.hmdp.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单列表与详情视图，联表补齐优惠券与商铺信息
 */
@Data
public class OrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单id 由时间戳左移 32 位生成，超出 JS 安全整数范围，必须序列化成字符串
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long userId;

    private Long voucherId;

    /**
     * 1 未支付 2 已支付 3 已核销 4 已取消 5 退款中 6 已退款
     */
    private Integer status;

    private Integer payType;

    private LocalDateTime createTime;

    private LocalDateTime payTime;

    private LocalDateTime useTime;

    private LocalDateTime refundTime;

    private String title;

    private String subTitle;

    private String rules;

    private Long payValue;

    private Long actualValue;

    private Long shopId;

    private String shopName;
}
