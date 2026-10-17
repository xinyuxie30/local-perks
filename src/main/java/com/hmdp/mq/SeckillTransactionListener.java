package com.hmdp.mq;

import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 延迟关单消费者：到期仍然未支付的订单改为已取消并回补库存。
 * 与支付并发时由条件更新决定胜负，重复投递也只是空跑一次
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = MqConstants.ORDER_CLOSE_TOPIC, consumerGroup = MqConstants.ORDER_CLOSE_GROUP)
public class OrderCloseConsumer implements RocketMQListener<SeckillOrderMessage> {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public void onMessage(SeckillOrderMessage message) {
        boolean closed = voucherOrderService.closeOrderIfUnpaid(message.getOrderId());
        if (!closed) {
            log.info("订单无需关闭（已支付或已处理），orderId={}", message.getOrderId());
        }
    }
}
