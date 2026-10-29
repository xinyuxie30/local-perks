package com.hmdp;

import com.hmdp.config.StockRestoreProperties;
import com.hmdp.dto.StockRestoreMessage;
import com.hmdp.entity.StockRestoreEvent;
import com.hmdp.mapper.StockRestoreEventMapper;
import com.hmdp.mq.MqConstants;
import com.hmdp.mq.StockRestoreConsumer;
import com.hmdp.service.StockRestoreOutboxService;
import com.hmdp.task.StockRestorePublishTask;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.Message;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 不依赖外部服务的故障注入；真实事务与 Lua 分别由集成测试覆盖。 */
class StockRestoreDeliveryTest {
    private StockRestoreEventMapper mapper;
    private StockRestoreProperties properties;
    private RocketMQTemplate mq;
    private StringRedisTemplate redis;
    private StockRestorePublishTask publisher;
    private StockRestoreConsumer consumer;
    private StockRestoreEvent event;

    @BeforeEach
    void setUp() {
        mapper = mock(StockRestoreEventMapper.class);
        properties = new StockRestoreProperties();
        mq = mock(RocketMQTemplate.class);
        redis = mock(StringRedisTemplate.class);
        publisher = new StockRestorePublishTask(mapper, properties, mq);
        consumer = new StockRestoreConsumer(mapper, redis);
        event = new StockRestoreEvent();
        event.setId(StockRestoreOutboxService.eventId(41L));
        event.setOrderId(41L);
        event.setVoucherId(7L);
        event.setEventType(StockRestoreEvent.ORDER_CANCELLED);
        event.setTotalStock(10);
        event.setStatus(StockRestoreEvent.PENDING);
        event.setSendAttempts(0);
        when(mapper.findDue(anyInt())).thenReturn(Collections.singletonList(event));
        when(mapper.claim(eq(event.getId()), anyString(), anyInt(), anyInt())).thenReturn(1);
        when(mapper.selectById(event.getId())).thenReturn(event);
        when(mapper.markDone(event.getId())).thenReturn(1);
    }

    @Test
    void disabledPublisherMustLeavePendingEventsUntouched() {
        properties.setEnabled(false);
        publisher.publishPending();
        verify(mapper, never()).findDue(anyInt());
        verifyNoInteractions(mq);
    }

    @Test
    void workerLosingClaimMustNotSend() {
        when(mapper.claim(eq(event.getId()), anyString(), anyInt(), anyInt())).thenReturn(0);
        publisher.publishPending();
        verifyNoInteractions(mq);
        verify(mapper, never()).markSent(anyString(), anyString(), anyInt());
    }

    @Test
    void successfulPublishMustCarryStableEventIdAndAwaitConsumerConfirmation() {
        sendReturns(SendStatus.SEND_OK);
        publisher.publishPending();

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(mq).syncSend(eq(MqConstants.STOCK_RESTORE_TOPIC), message.capture(), eq(3000L));
        assertEquals(event.getId(), ((StockRestoreMessage) message.getValue().getPayload()).getEventId());
        assertEquals(event.getId(), message.getValue().getHeaders().get(RocketMQHeaders.KEYS));
        verify(mapper).markSent(eq(event.getId()), anyString(), eq(120));
        verify(mapper, never()).markDone(anyString());
    }

    @Test
    void consumerFinishingBeforeSendConfirmationMustNotTriggerAnUnconditionalStatusWrite() {
        sendReturns(SendStatus.SEND_OK);
        when(mapper.markSent(eq(event.getId()), anyString(), anyInt())).thenReturn(0);
        publisher.publishPending();

        verify(mapper).markSent(eq(event.getId()), anyString(), eq(120));
        verify(mapper, never()).markSendFailure(anyString(), anyString(), anyInt(), anyString());
        verify(mapper, never()).updateById(any());
    }

    @Test
    void brokerFailureMustKeepSameEventForRetryWithBoundedBackoff() {
        event.setSendAttempts(15);
        when(mq.syncSend(anyString(), any(Message.class), anyLong())).thenThrow(new IllegalStateException("broker down"));
        publisher.publishPending();
        verify(mapper).markSendFailure(eq(event.getId()), anyString(), eq(300), contains("broker down"));
        verify(mapper, never()).markSent(anyString(), anyString(), anyInt());
    }

    @Test
    void brokerTimeoutStatusMustNotCountAsSendSuccess() {
        sendReturns(SendStatus.FLUSH_DISK_TIMEOUT);
        publisher.publishPending();
        verify(mapper).markSendFailure(eq(event.getId()), anyString(), eq(5), contains("FLUSH_DISK_TIMEOUT"));
        verify(mapper, never()).markSent(anyString(), anyString(), anyInt());
    }

    @Test
    void failurePersistingSendConfirmationMustReturnEventToRetryPath() {
        sendReturns(SendStatus.SEND_OK);
        when(mapper.markSent(eq(event.getId()), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("database unavailable"));
        publisher.publishPending();
        verify(mapper).markSendFailure(eq(event.getId()), anyString(), eq(5), contains("database unavailable"));
    }

    @Test
    void unacknowledgedSentEventMustBeRepublishedWithSameBusinessKey() {
        event.setStatus(StockRestoreEvent.SENT);
        event.setSendAttempts(1);
        sendReturns(SendStatus.SEND_OK);
        publisher.publishPending();
        verify(mapper).claim(eq(event.getId()), anyString(), eq(30), eq(20));
        verify(mq).syncSend(eq(MqConstants.STOCK_RESTORE_TOPIC), any(Message.class), eq(3000L));
        verify(mapper).markSent(eq(event.getId()), anyString(), eq(120));
    }

    @Test
    void exhaustedEventMustRequireInterventionWithoutAnotherSend() {
        event.setSendAttempts(20);
        publisher.publishPending();
        verify(mapper).markExhausted(event.getId(), 20);
        verify(mapper, never()).claim(anyString(), anyString(), anyInt(), anyInt());
        verifyNoInteractions(mq);
    }

    @Test
    void completedEventMustNotTouchRedisEvenIfMarkerWasEvicted() {
        event.setStatus(StockRestoreEvent.DONE);
        consume();
        verifyNoInteractions(redis);
        verify(mapper, never()).markDone(anyString());
    }

    @Test
    void unknownEventMustNotBeAcknowledged() {
        when(mapper.selectById(event.getId())).thenReturn(null);
        assertThrows(IllegalStateException.class, this::consume);
        verifyNoInteractions(redis);
        verify(mapper, never()).markDone(anyString());
    }

    @Test
    void successfulRestoreMustUseDatabaseValuesAndThenRecordCompletion() {
        scriptReturns(1L);
        consume();
        verify(redis).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                eq(java.util.Arrays.asList("seckill:stock:7", "seckill:stock:restored:" + event.getId())),
                eq("10"), eq(event.getId()));
        org.mockito.InOrder ordered = inOrder(redis, mapper);
        ordered.verify(redis).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(), any());
        ordered.verify(mapper).markDone(event.getId());
    }

    @Test
    void lostRedisResponseRetryMayConfirmDuplicateWithoutAnotherIncrement() {
        scriptReturns(2L);
        consume();
        verify(mapper).markDone(event.getId());
        verify(mapper, never()).recordConsumeFailure(anyString(), anyString());
    }

    @Test
    void lateDeliveryMayCompleteEvenAfterPublisherExhaustedRetries() {
        event.setStatus(StockRestoreEvent.FAILED);
        scriptReturns(1L);
        consume();
        verify(mapper).markDone(event.getId());
    }

    @Test
    void missingStockMustRemainIncompleteAndThrowForMqRetry() {
        scriptReturns(-1L);
        assertThrows(IllegalStateException.class, this::consume);
        verify(mapper).recordConsumeFailure(eq(event.getId()), contains("code=-1"));
        verify(mapper, never()).markDone(anyString());
    }

    @Test
    void nullRedisResultMustNeverBeTreatedAsSuccess() {
        scriptReturns(null);
        assertThrows(IllegalStateException.class, this::consume);
        verify(mapper, never()).markDone(anyString());
    }

    @Test
    void failureRecordingCompletionMustThrowSoRedisMarkerProtectsRetry() {
        scriptReturns(1L);
        when(mapper.markDone(event.getId())).thenThrow(new IllegalStateException("database down"));
        IllegalStateException error = assertThrows(IllegalStateException.class, this::consume);
        assertEquals("database down", error.getMessage());
        verify(mapper).recordConsumeFailure(eq(event.getId()), contains("database down"));
    }

    @Test
    void failureRecordingErrorMustPreserveOriginalFailureForMq() {
        scriptReturns(-3L);
        when(mapper.recordConsumeFailure(anyString(), anyString())).thenThrow(new IllegalStateException("db down"));
        IllegalStateException error = assertThrows(IllegalStateException.class, this::consume);
        assertTrue(error.getMessage().contains("code=-3"));
        assertEquals(1, error.getSuppressed().length);
    }

    @Test
    void anotherConsumerMayAlreadyHaveCompletedTheSameEvent() {
        scriptReturns(2L);
        when(mapper.markDone(event.getId())).thenReturn(0);
        StockRestoreEvent done = new StockRestoreEvent();
        done.setStatus(StockRestoreEvent.DONE);
        when(mapper.selectById(event.getId())).thenReturn(event, done);
        assertDoesNotThrow(this::consume);
    }

    @Test
    void missingCompletionRowMustNotBeAcknowledged() {
        scriptReturns(1L);
        when(mapper.markDone(event.getId())).thenReturn(0);
        when(mapper.selectById(event.getId())).thenReturn(event, null);
        assertThrows(IllegalStateException.class, this::consume);
    }

    private void consume() {
        consumer.onMessage(new StockRestoreMessage(event.getId()));
    }

    private void scriptReturns(Long value) {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(), any())).thenReturn(value);
    }

    private void sendReturns(SendStatus status) {
        SendResult result = new SendResult();
        result.setSendStatus(status);
        when(mq.syncSend(anyString(), any(Message.class), anyLong())).thenReturn(result);
    }
}
