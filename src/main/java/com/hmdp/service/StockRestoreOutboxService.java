package com.hmdp.service;

import com.hmdp.entity.StockRestoreEvent;
import com.hmdp.mapper.StockRestoreEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 必须由取消订单的外层事务调用；插入失败应回滚订单状态与 MySQL 库存。 */
@Service
public class StockRestoreOutboxService {
    private final StockRestoreEventMapper eventMapper;

    public StockRestoreOutboxService(StockRestoreEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void createEvent(Long orderId, Long voucherId, Integer totalStock) {
        if (orderId == null || orderId <= 0 || voucherId == null || voucherId <= 0
                || totalStock == null || totalStock <= 0) {
            throw new IllegalArgumentException("Invalid stock restore event");
        }
        StockRestoreEvent event = new StockRestoreEvent();
        event.setId(eventId(orderId));
        event.setOrderId(orderId);
        event.setEventType(StockRestoreEvent.ORDER_CANCELLED);
        event.setVoucherId(voucherId);
        event.setTotalStock(totalStock);
        event.setStatus(StockRestoreEvent.PENDING);
        if (eventMapper.insert(event) != 1) {
            throw new IllegalStateException("Stock restore event insert failed: " + event.getId());
        }
    }

    public static String eventId(Long orderId) {
        return StockRestoreEvent.ORDER_CANCELLED + ":" + orderId;
    }
}
