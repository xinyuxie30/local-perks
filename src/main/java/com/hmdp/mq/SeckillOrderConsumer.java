package com.hmdp.mq;

import com.hmdp.config.SeckillProperties;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 秒杀下单消费者：模式 A 直接落库，模式 B 先认领库存再落库。
 * 消费失败抛异常交给 RocketMQ 重试，重试耗尽后进入 DLQ（cg-seckill-order）
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = MqConstants.SECKILL_ORDER_TOPIC, consumerGroup = MqConstants.SECKILL_ORDER_GROUP)
public class SeckillOrderConsumer implements RocketMQListener<SeckillOrderMessage> {

    private static final DefaultRedisScript<Long> CLAIM_SCRIPT;

    static {
        CLAIM_SCRIPT = new DefaultRedisScript<>();
        CLAIM_SCRIPT.setLocation(new ClassPathResource("seckill_claim.lua"));
        CLAIM_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillProperties seckillProperties;

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Override
    public void onMessage(SeckillOrderMessage message) {
        if (seckillProperties.isClaimInConsumer()) {
            Long code = claimStock(message);
            if (code != null && (code == 1L || code == 3L)) {
                // 库存不足或券未预热：重试也不会成功，直接确认消费并记录失败原因
                log.warn("库存认领失败，orderId={}，code={}", message.getOrderId(), code);
                markFailed(message.getOrderId(), message.getUserId(), code);
                return;
            }
            // 返回 2 表示该用户已在占用集合里，可能是重复消息或上一次消费重试：
            // 不在这里拦，交给数据库的「先查 + 唯一索引」兜底，避免重试把订单丢掉
        }
        // 幂等落库。抛异常则 MQ 重试，重复投递时内部会识别并跳过
        voucherOrderService.createOrder(message);
        // 落库成功：清掉排队状态，并投递延迟关单消息
        stringRedisTemplate.delete(SECKILL_QUEUE_KEY + message.getOrderId());
        seckillOrderProducer.sendCloseDelayMessage(message);
    }

    private Long claimStock(SeckillOrderMessage message) {
        try {
            return stringRedisTemplate.execute(
                    CLAIM_SCRIPT,
                    Collections.emptyList(),
                    message.getVoucherId().toString(),
                    message.getUserId().toString());
        } catch (Exception e) {
            // Redis 异常时抛出去让 MQ 重试
            throw new IllegalStateException("库存认领异常，orderId=" + message.getOrderId(), e);
        }
    }

    private void markFailed(Long orderId, Long userId, Long code) {
        stringRedisTemplate.opsForValue().set(SECKILL_QUEUE_KEY + orderId,
                SECKILL_QUEUE_FAILED + ":" + userId + ":" + code, SECKILL_QUEUE_TTL, TimeUnit.MINUTES);
    }
}
