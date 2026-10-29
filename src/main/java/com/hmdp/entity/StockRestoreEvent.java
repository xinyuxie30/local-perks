package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 与取消订单、MySQL 库存回补在同一事务持久化的事件。 */
@Data
@TableName("tb_stock_restore_event")
public class StockRestoreEvent {
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String PENDING = "PENDING";
    public static final String SENDING = "SENDING";
    public static final String SENT = "SENT";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    @TableId(type = IdType.INPUT)
    private String id;
    private Long orderId;
    private String eventType;
    private Long voucherId;
    private Integer totalStock;
    private String status;
    private Integer sendAttempts;
    private Integer consumeAttempts;
    private String lockToken;
    private LocalDateTime lockedUntil;
    private LocalDateTime nextAttemptAt;
    private String lastError;
    private LocalDateTime sentAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
