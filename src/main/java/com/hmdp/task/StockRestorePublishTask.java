package com.hmdp.task;

import com.hmdp.config.StockRestoreProperties;
import com.hmdp.dto.StockRestoreMessage;
import com.hmdp.entity.StockRestoreEvent;
import com.hmdp.mapper.StockRestoreEventMapper;
import com.hmdp.mq.MqConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 至少一次发送：租约恢复进程崩溃，消费确认超时重发，重复消息由 Lua 处理。 */
@Slf4j
@Component
public class StockRestorePublishTask {
    private final StockRestoreEventMapper eventMapper;
    private final StockRestoreProperties properties;
    private final RocketMQTemplate rocketMQTemplate;

    public StockRestorePublishTask(StockRestoreEventMapper eventMapper, StockRestoreProperties properties,
                                   RocketMQTemplate rocketMQTemplate) {
        this.eventMapper = eventMapper;
        this.properties = properties;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Scheduled(fixedDelayString = "${trade.stock-restore.scan-delay-ms:5000}")
    public void publishPending() {
        if (!properties.isEnabled()) {
            return;
        }
        for (StockRestoreEvent event : eventMapper.findDue(properties.getBatchSize())) {
            try {
                publish(event);
            } catch (Exception e) {
                // 某条记录故障不能阻塞整批；已认领的事件可在租约到期后恢复。
                log.error("STOCK_RESTORE_ALERT 发件箱处理失败，eventId={}", event.getId(), e);
            }
        }
    }

    private void publish(StockRestoreEvent event) {
        if (event.getSendAttempts() >= properties.getMaxSendAttempts()) {
            if (eventMapper.markExhausted(event.getId(), properties.getMaxSendAttempts()) == 1) {
                log.error("STOCK_RESTORE_ALERT 库存回补重发耗尽，eventId={}，需要排查重试/死信与 Redis 状态",
                        event.getId());
            }
            return;
        }
        String token = UUID.randomUUID().toString();
        if (eventMapper.claim(event.getId(), token, properties.getLeaseSeconds(), properties.getMaxSendAttempts()) != 1) {
            return;
        }
        try {
            SendResult result = rocketMQTemplate.syncSend(MqConstants.STOCK_RESTORE_TOPIC,
                    MessageBuilder.withPayload(new StockRestoreMessage(event.getId()))
                            .setHeader(RocketMQHeaders.KEYS, event.getId()).build(),
                    properties.getSendTimeoutMs());
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("Unconfirmed broker send: " + (result == null ? null : result.getSendStatus()));
            }
            // 消费可能先于发送确认；CAS 不允许把 DONE 改回 SENT，也不覆盖新的租约。
            eventMapper.markSent(event.getId(), token, properties.getAckTimeoutSeconds());
        } catch (Exception e) {
            eventMapper.markSendFailure(event.getId(), token, retrySeconds(event.getSendAttempts() + 1), describe(e));
            log.warn("库存回补发送失败，保留事件稍后重试，eventId={}", event.getId(), e);
        }
    }

    private int retrySeconds(int attempt) {
        long delay = properties.getRetryBaseSeconds();
        for (int i = 1; i < attempt && delay < properties.getRetryMaxSeconds(); i++) {
            delay = Math.min(delay * 2, properties.getRetryMaxSeconds());
        }
        return (int) Math.min(delay, properties.getRetryMaxSeconds());
    }

    @Scheduled(fixedDelayString = "${trade.stock-restore.alert-delay-ms:60000}")
    public void monitorBacklog() {
        long failed = eventMapper.countFailed();
        long overdue = eventMapper.countOverdue(properties.getAlertAgeSeconds());
        if (failed > 0 || overdue > 0) {
            StockRestoreEvent oldest = eventMapper.findOldestIncomplete();
            log.error("STOCK_RESTORE_ALERT 未完成库存回补：failed={}，overdue={}，oldestEvent={}，createdAt={}，lastError={}",
                    failed, overdue, oldest == null ? null : oldest.getId(),
                    oldest == null ? null : oldest.getCreatedAt(), oldest == null ? null : oldest.getLastError());
        }
    }

    private String describe(Exception error) {
        String text = error.getClass().getSimpleName() + ": " + error.getMessage();
        return text.length() > 1024 ? text.substring(0, 1024) : text;
    }
}
