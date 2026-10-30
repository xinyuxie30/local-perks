# 库存回补发件箱验证（2026-10-29）

> 留存状态（2026-10-30）：用户要求释放磁盘空间，历史测试数据库、消息存储、原始请求、完整日志与旧 JAR 已删除；本文保留当时过程/结果，所引用原始文件路径不再可用。

结果：JDK 8，70 个测试全部通过，0 失败、0 错误、0 跳过。未改动开发库，未部署新应用。

独立环境：MySQL 8 / stock_restore_test（23306），Redis（26379），RocketMQ 4.9.7
NameServer（29876）、Broker（30911）。数据库已导入基础脚本与 V2、V3、V4。
本次测试 Broker 因 D 盘空间比例触发保护，存储切换至 C 盘临时目录后完成验证；未修改 Broker 保护阈值。

执行参数：

```text
mvn -Dtest=OrderStateMachineTest,StockRestoreDeliveryTest,StockRestoreScriptTest,TradeReconcileSafetyTests,OrderStatusRaceTest,AdminWarmupTest,StockWarmupScriptTest -Dtrade.close-delay-level=16 -Dtrade.stock-restore.enabled=false -Dtrade.reconcile-cron=- test
```

设置 RUN_REDIS_SCRIPT_TESTS=true 并将数据源/Redis/MQ 指向上述独立端口。
测试关闭定时投递，真实 MQ 用例手动调用生产发布器，实际 Broker 与监听器完成剩余链路。

覆盖：

- 取消、超时关单 CAS；订单、MySQL 库存、事件共同提交或回滚。
- 事件插入失败/库存更新失败不提交半完成订单；终身一单资格不释放。
- 数据库真实租约认领、崩溃后过期重认领、旧 token 不覆盖新状态、DONE 不回退。
- 发送失败退避、非 SEND_OK、Broker 接收但数据库确认失败、发送次数耗尽与迟到消息。
- 真实 MQ 端到端发送到消费；Lua 成功后数据库确认丢失时重放不重复加库存。
- 真实 Lua 并发重复、缺键、错误类型、非法参数、整数边界、库存上限、TTL、写后运行时错误。
- 对账始终不覆盖 Redis，即使开启原 auto-fix；预热和订单状态查询回归。

原并发重复建单测试遇到 InnoDB 唯一键竞争死锁，测试按线上 MQ 重投语义对该异常进行有界重投，
仍验证一单和一次库存扣减；业务建单逻辑未为此修改。

详细结果见本目录各 `com.hmdp.*.txt` / `TEST-*.xml`，完整日志见 `tests.log`。
这些是功能与故障路径测试，不代表重新执行了此前简历中的吞吐压测。
