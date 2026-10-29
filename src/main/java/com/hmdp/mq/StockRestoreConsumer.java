package com.hmdp.mq;

import com.hmdp.dto.StockRestoreMessage;
import com.hmdp.entity.StockRestoreEvent;
import com.hmdp.mapper.StockRestoreEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_RESTORE_EVENT_KEY;

/** Redis 回补失败必须抛出，使 RocketMQ 重试；耗尽后进入本消费组死信队列。 */
@Slf4j
@Component
@RocketMQMessageListener(topic = MqConstants.STOCK_RESTORE_TOPIC,
        consumerGroup = MqConstants.STOCK_RESTORE_GROUP, maxReconsumeTimes = 16)
public class StockRestoreConsumer implements RocketMQListener<StockRestoreMessage> {
    private static final DefaultRedisScript<Long> RESTORE_STOCK_SCRIPT = new DefaultRedisScript<>();

    static {
        RESTORE_STOCK_SCRIPT.setLocation(new ClassPathResource("restore_stock.lua"));
        RESTORE_STOCK_SCRIPT.setResultType(Long.class);
    }

    private final StockRestoreEventMapper eventMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public StockRestoreConsumer(StockRestoreEventMapper eventMapper, StringRedisTemplate stringRedisTemplate) {
        this.eventMapper = eventMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void onMessage(StockRestoreMessage message) {
        if (message == null || message.getEventId() == null || message.getEventId().isEmpty()) {
            throw new IllegalArgumentException("Missing stock restore event id");
        }
        StockRestoreEvent event = eventMapper.selectById(message.getEventId());
        if (event == null) {
            // 不能把未知事件当作成功，避免删除/迁移错误静默吞掉回补。
            throw new IllegalStateException("Stock restore event not found: " + message.getEventId());
        }
        if (StockRestoreEvent.DONE.equals(event.getStatus())) {
            return;
        }
        try {
            if (!StockRestoreEvent.ORDER_CANCELLED.equals(event.getEventType()) || event.getVoucherId() == null
                    || event.getVoucherId() <= 0 || event.getTotalStock() == null || event.getTotalStock() <= 0) {
                throw new IllegalStateException("Invalid stored stock restore event: " + event.getId());
            }
            Long result = stringRedisTemplate.execute(RESTORE_STOCK_SCRIPT,
                    Arrays.asList(SECKILL_STOCK_KEY + event.getVoucherId(), SECKILL_STOCK_RESTORE_EVENT_KEY + event.getId()),
                    event.getTotalStock().toString(), event.getId());
            if (!Long.valueOf(1L).equals(result) && !Long.valueOf(2L).equals(result)) {
                throw new IllegalStateException("Redis stock restore rejected, code=" + result + ", eventId=" + event.getId());
            }
            // 即便此处 DB 故障或进程退出，Redis 的持久幂等标记也会让下一次返回 2。
            int changed = eventMapper.markDone(event.getId());
            if (changed == 0) {
                StockRestoreEvent current = eventMapper.selectById(event.getId());
                if (current == null || !StockRestoreEvent.DONE.equals(current.getStatus())) {
                    throw new IllegalStateException("Stock restore completion not persisted: " + event.getId());
                }
            }
        } catch (RuntimeException e) {
            try {
                eventMapper.recordConsumeFailure(event.getId(), describe(e));
            } catch (RuntimeException recordFailure) {
                e.addSuppressed(recordFailure);
            }
            log.error("STOCK_RESTORE_ALERT 库存回补消费失败，eventId={}，voucherId={}，由 MQ 重试",
                    event.getId(), event.getVoucherId(), e);
            throw e;
        }
    }

    private String describe(Exception error) {
        String text = error.getClass().getSimpleName() + ": " + error.getMessage();
        return text.length() > 1024 ? text.substring(0, 1024) : text;
    }
}
