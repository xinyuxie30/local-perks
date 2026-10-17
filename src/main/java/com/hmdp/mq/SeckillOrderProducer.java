package com.hmdp.mq;

import com.hmdp.dto.SeckillOrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class SeckillOrderProducer {

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    /**
     * RocketMQ 4.x 只支持 18 个固定延迟级别：1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h
     * 默认 16 对应 30 分钟；演示时改成 3（10s）方便观察关单
     */
    @Value("${trade.close-delay-level:16}")
    private int closeDelayLevel;

    /**
     * 模式 A：事务消息。半消息先落 Broker，本地事务成功才提交
     */
    public TransactionSendResult sendOrderMessageInTransaction(SeckillOrderMessage message) {
        return rocketMQTemplate.sendMessageInTransaction(
                MqConstants.SECKILL_ORDER_TOPIC,
                MessageBuilder.withPayload(message).build(),
                message);
    }

    /**
     * 模式 B：入口只排队，用普通消息把创建订单的请求投递出去
     */
    public void sendOrderMessage(SeckillOrderMessage message) {
        rocketMQTemplate.syncSend(MqConstants.SECKILL_ORDER_TOPIC, message);
    }

    /**
     * 订单落库成功后投递延迟关单消息
     */
    public void sendCloseDelayMessage(SeckillOrderMessage message) {
        rocketMQTemplate.syncSend(
                MqConstants.ORDER_CLOSE_TOPIC,
                MessageBuilder.withPayload(message).build(),
                3000,
                closeDelayLevel);
    }
}
