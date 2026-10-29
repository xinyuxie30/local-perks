-- 库存回补发件箱。先在目标业务库执行此增量脚本，再部署新代码；不要重跑 hmdp.sql。
-- 不指定 USE，允许运维显式选择目标库，也便于隔离测试库复用。
CREATE TABLE IF NOT EXISTS tb_stock_restore_event (
    id VARCHAR(64) NOT NULL COMMENT '稳定业务事件 ID，例如 ORDER_CANCELLED:订单ID',
    order_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    voucher_id BIGINT NOT NULL,
    total_stock INT NOT NULL COMMENT '关单事务中读取的库存上限',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENDING/SENT/DONE/FAILED',
    send_attempts INT NOT NULL DEFAULT 0,
    consume_attempts INT NOT NULL DEFAULT 0 COMMENT '已记录的消费结果次数；进程崩溃时可能少计',
    lock_token VARCHAR(36) DEFAULT NULL,
    locked_until DATETIME DEFAULT NULL,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(1024) DEFAULT NULL,
    sent_at DATETIME DEFAULT NULL,
    completed_at DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_event (order_id, event_type),
    KEY idx_status_next (status, next_attempt_at),
    KEY idx_status_lease (status, locked_until),
    KEY idx_status_created (status, created_at),
    KEY idx_voucher_status (voucher_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- DONE 记录与 Redis 幂等标记不能单独按 TTL 清理。归档前必须停用对应券、排空 MQ
-- （含重试/死信）并禁止重放；已经取消的历史订单不能盲目补事件，可能早已回补 Redis。
