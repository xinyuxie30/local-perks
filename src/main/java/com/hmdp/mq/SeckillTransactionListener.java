package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.SeckillOrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 模式 A 的本地事务：在事务消息的半消息提交前执行 Lua 预扣。
 * 本地事务与入口请求在同一个线程里执行，因此失败原因可以直接写进排队状态供入口读取
 */
@Slf4j
@Component
@RocketMQTransactionListener
public class SeckillTransactionListener implements RocketMQLocalTransactionListener {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        SeckillOrderMessage message = resolveMessage(msg, arg);
        if (message == null) {
            log.error("无法解析秒杀消息，回滚半消息");
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        Long code;
        try {
            code = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    message.getVoucherId().toString(),
                    message.getUserId().toString(),
                    message.getOrderId().toString()
            );
        } catch (Exception e) {
            // Redis 瞬时故障：返回 UNKNOWN 交给 Broker 回查，而不是直接回滚
            log.error("本地事务执行 Lua 异常，orderId={}", message.getOrderId(), e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
        if (code != null && code == 0L) {
            // 库存已扣、资格已占、事务标记已写，半消息可以投递给消费者
            return RocketMQLocalTransactionState.COMMIT;
        }
        // 没有抢购资格：把失败原因写回排队状态（带归属 userId 供状态查询校验），入口据此返回文案，同时丢弃半消息
        stringRedisTemplate.opsForValue().set(
                SECKILL_QUEUE_KEY + message.getOrderId(),
                SECKILL_QUEUE_FAILED + ":" + message.getUserId() + ":" + (code == null ? "unknown" : code),
                SECKILL_QUEUE_TTL, TimeUnit.MINUTES);
        return RocketMQLocalTransactionState.ROLLBACK;
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        // 回查：以 Redis 事务标记为准。标记在，说明本地事务已经扣减成功，半消息必须提交
        Long orderId = parseOrderId(msg);
        if (orderId == null) {
            log.error("回查无法解析订单id，回滚半消息");
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        String mark = stringRedisTemplate.opsForValue().get(SECKILL_TX_KEY + orderId);
        if (SECKILL_TX_COMMIT.equals(mark)) {
            return RocketMQLocalTransactionState.COMMIT;
        }
        // 标记不存在或已过期：说明本地事务没成功，丢弃半消息
        return RocketMQLocalTransactionState.ROLLBACK;
    }

    private SeckillOrderMessage resolveMessage(Message msg, Object arg) {
        if (arg instanceof SeckillOrderMessage) {
            return (SeckillOrderMessage) arg;
        }
        Object payload = msg.getPayload();
        if (payload instanceof SeckillOrderMessage) {
            return (SeckillOrderMessage) payload;
        }
        try {
            return JSONUtil.toBean(bodyOf(msg), SeckillOrderMessage.class);
        } catch (Exception e) {
            log.error("解析秒杀消息失败", e);
            return null;
        }
    }

    private Long parseOrderId(Message msg) {
        SeckillOrderMessage message = resolveMessage(msg, null);
        if (message == null) {
            return null;
        }
        return message.getOrderId();
    }

    private String bodyOf(Message msg) {
        Object payload = msg.getPayload();
        if (payload instanceof byte[]) {
            return new String((byte[]) payload, StandardCharsets.UTF_8);
        }
        return String.valueOf(payload);
    }
}
