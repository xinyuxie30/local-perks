# 取消订单后的库存回补

取消订单与超时关单都通过同一条链路：

```text
MySQL 事务：订单 UNPAID → CANCELED（CAS）
          → MySQL 库存 +1（stock < total_stock）
          → 插入 tb_stock_restore_event
提交 → 定时扫描 → RocketMQ topic-stock-restore
     → 消费者读取事件 → Redis Lua 幂等回补 → 事件 DONE
```

任一步数据库操作失败会回滚整个取消事务。事件写入要求已有事务（MANDATORY），不再使用
`afterCommit` 直接写 Redis。退款仍不退库存；取消不删除 `seckill:order:{voucherId}` 的购买记录，
保留终身一单规则。

## 发布与消费

- 稳定事件 ID 为 `ORDER_CANCELLED:{orderId}`；数据库另外约束 `(order_id, event_type)` 唯一。
- 扫描器用数据库时间和条件 UPDATE 认领事件，租约 token 防止旧发送者覆盖新状态。
  进程在认领后退出，租约到期可重新发送。
- 发送 `SEND_OK` 只表示 Broker 接收，事件记为 `SENT`，并不代表回补完成。
  消费确认超时会再次发送同一事件 ID。消费先完成时，发送端不能把 `DONE` 改回 `SENT`。
- 发送失败指数退避；认领次数达到上限后进入 `FAILED`，等待排查。迟到的有效消息仍可完成它。
- 消费者只从消息读取事件 ID，库存参数从数据库事件读取。Lua 返回 `1`（执行）或 `2`（重复）
  才允许记录 `DONE`；缺键、坏值、库存上限、Redis 或数据库故障均抛出异常，交由 MQ 重试。
- Redis 成功但数据库确认失败时，重试通过 Redis 标记跳过加一，再补写 `DONE`。
  数据库已经 `DONE` 时直接忽略消息，即使 Redis 标记后来丢失，也不再次回补。

```text
PENDING → SENDING → SENT → DONE
             ↓       ↓
          发送失败   消费未确认
             ↓       ↓
          PENDING ← 重新认领
发送次数耗尽 → FAILED；有效迟到消息仍可使 FAILED → DONE
```

Lua 在任何写入前验证参数、键类型、整数范围和库存上限，以单条 `MSET` 同时写库存与事件标记。
库存原有 TTL 随后恢复；事件标记不设置 TTL。如果恢复 TTL 时出现运行时错误，库存和标记已一起写入，
重试仍能去重，但 TTL 异常需要排查。当前键名用于单机 Redis；迁移 Redis Cluster 时需让两个键共享 hash tag。

这是一条至少一次投递加业务幂等的链路，并不承诺跨 MySQL/Redis 的严格 exactly-once。
如果 Redis 在加一后、数据库写 DONE 前丢失或淘汰标记，系统无法仅凭“标记不存在”判断是否执行过。
库存与标记应使用可靠的 Redis 持久化配置，避免淘汰这些业务键；发生数据丢失应暂停交易核账。
持久化、复制、备份和内存策略需由部署方配置，例如按可接受的数据丢失窗口选择 AOF 策略、为交易实例
使用 `noeviction` 并监测容量与 `evicted_keys`；本次代码改造不会自动更改 Redis 服务配置。

## 部署

1. **先在所选业务库执行 `src/main/resources/db/V4__stock_restore_outbox.sql`，再部署应用。**
   项目没有自动迁移组件，此文件不指定 `USE`，需连接目标库执行；不要重跑全量 `hmdp.sql`。
2. 确保 Broker 上存在 `topic-stock-restore`，消费者组为 `cg-stock-restore`。
   开发 Broker 可自动创建；正式环境需按现有 Topic 管理流程创建并授权。
3. 切换时暂停取消入口、延迟关单和对账补关单，等待旧实例的事务与 `afterCommit` 回调结束，
   停止旧实例后再启动新实例，避免新旧回补路径重叠运行。
   **不能为历史已取消订单批量盲补事件**：旧路径可能已经回补过，需先按账本核对。

默认配置位于 `application.yaml` 的 `trade.stock-restore`：

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| enabled | true | 仅控制扫描投递；false 时取消仍写事件，消费者继续排空 |
| batch-size / scan-delay-ms | 50 / 5000 | 每轮扫描上限 / 扫描间隔 |
| lease-seconds / send-timeout-ms | 30 / 3000 | 认领租约 / 单次同步发送超时 |
| ack-timeout-seconds | 120 | 等待消费确认后再发送的时间 |
| retry-base-seconds / retry-max-seconds | 5 / 300 | 发送退避初值 / 上限 |
| max-send-attempts | 20 | 包含租约恢复、确认超时重发在内的最大认领次数 |
| alert-age-seconds / alert-delay-ms | 600 / 60000 | 积压告警阈值 / 监测间隔 |

## 监控与人工处理

`STOCK_RESTORE_ALERT` 是日志告警关键字，当前实现不直接调用外部通知服务。
应接入日志告警，并监控事件积压、最老未完成事件，以及 MQ 重试和
`%DLQ%cg-stock-restore` 死信。消费者每条消息最多重试 16 次；发件箱重发与 MQ 重试是两层机制。

```sql
SELECT status, COUNT(*) AS events, MIN(created_at) AS oldest
FROM tb_stock_restore_event GROUP BY status;

SELECT id, order_id, voucher_id, status, send_attempts, consume_attempts,
       created_at, next_attempt_at, locked_until, sent_at, completed_at, last_error
FROM tb_stock_restore_event
WHERE status = 'FAILED'
   OR (status <> 'DONE' AND created_at < NOW() - INTERVAL 10 MINUTE)
ORDER BY created_at, id LIMIT 100;
```

`consume_attempts` 只统计成功写入数据库的消费结果，进程退出时可能少计，不能据此证明 Redis 未执行。
Lua 返回 `-1` 表示库存键缺失，`-2` 表示库存类型/值非法，`-3` 表示达到或超过上限，
`-4` 表示参数非法，`-5` 表示幂等标记类型/值异常；这些情况都会保留未完成事件。

确认只是 Broker/网络暂时故障，且 Redis 数据和幂等标记完整时，可修复故障后按原事件重放 MQ，
或仅将核实过的 FAILED 事件重新排队，不能更换事件 ID：

```sql
UPDATE tb_stock_restore_event
SET status='PENDING', send_attempts=0, next_attempt_at=CURRENT_TIMESTAMP,
    lock_token=NULL, locked_until=NULL
WHERE id='ORDER_CANCELLED:替换成已核实的订单ID' AND status='FAILED';
```

不要通过删除标记、换事件 ID 或未经核实直接设置 `DONE` 来消除告警；发送确认和消费次数都不能替代库存核验。

库存缺失、幂等标记疑似丢失或达到上限时，不要直接重建库存再重放。
先暂停秒杀、手动取消、延迟关单与对账补关单，排空/暂停发布器和消费者（包括重试、死信与在途调用），
再逐事件核对订单账本、Redis 库存与标记，决定哪些事件已包含在重建库存中。
维护完成前不能让已计入库存的事件再次执行加一。

`TradeReconcileTask` 仍补关单并报告差异、缺键和未完成回补事件，**Redis 始终只观测**，
即使 `trade.reconcile.auto-fix=true` 也不覆盖 Redis；该开关现在仅保留维护窗口内的 MySQL 条件修复。
“先查没有待处理事件，再 CAS 改 Redis”存在与取消事务交错的窗口，不能当成安全修复流程。

DONE 事件与 Redis 标记不自动清理。只有对应券停用、相关消息（包括死信）排空且禁止重放后，
才可统一制定归档和标记清理策略；不能单独给标记设置短 TTL。

## 验证

使用 JDK 8，独立测试库先导入基础脚本及 V2/V3/V4，配置测试数据源、Redis、RocketMQ。
以下测试会创建并清理自己的订单、券、事件和 Redis 键，禁止对生产库运行：

```powershell
# 不连接外部服务的发送/消费故障注入
mvn '-Dtest=StockRestoreDeliveryTest' test
# 真实 Redis 脚本边界、并发重复和写后异常
$env:RUN_REDIS_SCRIPT_TESTS = 'true'
mvn '-Dtest=StockRestoreScriptTest' test
# 真正的事务回滚、SQL 租约、取消竞态、MQ 发布到消费完整链路、对账护栏
mvn '-Dtest=OrderStateMachineTest,TradeReconcileSafetyTests' '-Dtrade.stock-restore.enabled=false' '-Dtrade.reconcile-cron=-' '-Dtrade.close-delay-level=16' test
```

设计参考：[AWS 事务发件箱](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)、
[RocketMQ 消费幂等与发送重试建议](https://rocketmq.apache.org/docs/bestPractice/01bestpractice/)。
