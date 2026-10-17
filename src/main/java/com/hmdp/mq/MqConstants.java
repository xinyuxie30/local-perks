package com.hmdp.mq;

public class MqConstants {

    /**
     * 秒杀下单消息：模式 A 与模式 B 共用，消费者负责落库
     * 注意：RocketMQ 4.x 的 Topic/消费组只允许 ^[%|a-zA-Z0-9_-]+$，不能包含点号
     */
    public static final String SECKILL_ORDER_TOPIC = "topic-seckill-order";
    public static final String SECKILL_ORDER_GROUP = "cg-seckill-order";

    /**
     * 延迟关单消息：订单落库成功后投递，到期未支付则关闭并回补库存
     */
    public static final String ORDER_CLOSE_TOPIC = "topic-order-close";
    public static final String ORDER_CLOSE_GROUP = "cg-order-close";
}
