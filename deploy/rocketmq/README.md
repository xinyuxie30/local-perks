# 本地启动 RocketMQ（Windows，不用 Docker）

秒杀链路用到了事务消息和延迟消息，本地开发需要先起一个 NameServer + 一个 Broker。

## 1. 下载解压

下载 `rocketmq-all-4.9.x-bin-release.zip`（4.9.x 自带 Windows 的 `.cmd` 启动脚本，5.x 默认面向 Linux）：

```text
https://archive.apache.org/dist/rocketmq/4.9.7/rocketmq-all-4.9.7-bin-release.zip
```

解压到例如 `D:\tools\rocketmq\rocketmq-all-4.9.7-bin-release`。

## 2. 调小 JVM 内存（必做）

默认参数是给服务器准备的，开发机直接启动会因内存不足失败。改这两个文件里的 `JAVA_OPT`：

- `bin\runserver.cmd`：`-server -Xms4g -Xmx4g -Xmn2g` → `-server -Xms512m -Xmx512m -Xmn256m`
- `bin\runbroker.cmd`：`-server -Xms8g -Xmx8g -Xmn4g` → `-server -Xms1g -Xmx1g -Xmn512m`

## 3. 启动

开两个命令行窗口，先 NameServer 再 Broker。把这两个脚本顶部的 `ROCKETMQ_HOME` 改成你的解压目录：

```text
deploy\rocketmq\start-namesrv.cmd
deploy\rocketmq\start-broker.cmd
```

`start-broker.cmd` 会带上 `deploy\rocketmq\broker.conf`，其中固定了 `namesrvAddr`、`brokerIP1=127.0.0.1` 和 `autoCreateTopicEnable=true`（开发环境免手动建 topic）。

看到 `The Name Server boot success` 和 `The broker[broker-a, ...] boot success` 就算起来了。

## 4. 验证

命令行里执行，能列出 `DefaultCluster` 即正常：

```text
%ROCKETMQ_HOME%\bin\mqadmin.cmd clusterList -n 127.0.0.1:9876
```

## 5. 相关主题与消费组

`autoCreateTopicEnable=true` 时首次发送会自动创建，也可以手动建：

| 用途 | Topic | 消费组 |
| --- | --- | --- |
| 秒杀下单 | `topic.seckill.order` | `cg.seckill.order` |
| 延迟关单 | `topic.order.close` | `cg.order.close` |

消费失败会由 RocketMQ 自动重试（并发消费默认最多 16 次），超限后进入死信主题 `%DLQ%cg.seckill.order`。排查时用：

```text
%ROCKETMQ_HOME%\bin\mqadmin.cmd topicStatus -n 127.0.0.1:9876 -t topic.seckill.order
```

## 关于延迟级别

RocketMQ 4.x 只支持 18 个固定延迟级别：
`1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h`

关单默认取级别 16（30 分钟），对应 `trade.close-delay-level`；本地演示时改成 `3`（10 秒）即可快速看到自动关单。RocketMQ 5.x 的定时消息支持任意时间，本项目没升级，所以不做规避设计。
