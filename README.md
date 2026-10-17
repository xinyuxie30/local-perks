# 智惠生活通 · LocalPerks

基于 Spring Boot 与 Redis 的本地生活服务项目，提供商铺发现、探店内容、用户社交和优惠券秒杀。

## 功能

| 模块 | 功能 |
| --- | --- |
| 用户 | 手机验证码登录、Redis 登录态、登出、签到及连续签到统计 |
| 商铺 | 分类、详情缓存、名称搜索、附近商铺 |
| 优惠券 | 发券、优惠券查询、Lua 秒杀资格判断 |
| 订单 | 事务消息异步下单、A/B 两种写策略、延迟关单、支付、核销、退款、定时对账 |
| 内容 | 探店笔记、热门列表、点赞、评论（发布 / 回复 / 点赞 / 删除，游客可读） |
| 社交 | 关注、共同关注、关注动态滚动分页 |
| 智能助手 | 独立服务 EchoMind：工具调用、写操作审批、多专家协作、长期记忆、SSE 流式 |

## 智能助手（EchoMind）

`nginx-1.18.0/html/hmdp/chat.html` 是助手控制台（对话 / 功能 / 专家 / 记忆四个面板），
能力由独立服务 EchoMind 提供，默认跑在 `18080`，通过 Nginx 的 `/cs-api` 反代接入。

- 真 Agent Loop：模型自主选工具 → 参数校验 → 执行 → 结果回填 → 再请求，带轮次上限、总超时、同参去重、失败降级。
- 写操作 HITL：抢券、支付、取消、核销、退款、签到都要前端二次确认后才执行。
- 多 Agent：`delegate_task` 可把检索类子任务派给「导购顾问 / 内容推荐官 / 订单专员」，子 Agent 只读、上下文隔离、只回摘要。
- 上下文与记忆：预算内保留历史，超限做 LLM 摘要压缩；长期记忆按用户隔离存放在 `data/memory/<userId>/`。
- 按需技能与知识库：技能索引常驻、正文按需 `load_skill`；平台规则类问题走 `search_knowledge`。

接口：`/cs-api/chat/agent`、`/cs-api/chat/agent/stream`（SSE）、`/cs-api/chat/approve`、`/cs-api/agents`、`/cs-api/memory`。

## 技术栈

Java 8 · Spring Boot 2.3.12 · MyBatis-Plus 3.4.3 · Spring Data Redis 2.6.2 · Redisson 3.13.6 · RocketMQ 4.9.x · MySQL · Vue · Element UI · Nginx

## 运行

1. 准备 JDK 8、Maven 3、MySQL、Redis 6.2+ 和 RocketMQ 4.9.x。
2. 创建 hmdp 开发数据库，导入 src/main/resources/db/hmdp.sql。脚本会重建同名表。
3. 在同一个库上导入交易链路增量脚本 src/main/resources/db/V2__trade.sql，它会补上
   `(user_id, voucher_id)` 唯一索引、关单扫描索引和库存对账用的 `total_stock` 列。
4. 参考 .env.example，在终端或 IDE 中配置 DB_PASSWORD、DB_USER、DB_URL 和 REDIS_HOST、REDIS_PORT、REDIS_PASSWORD。应用不会自动读取 .env 文件。
5. 启动 RocketMQ 的 NameServer 与 Broker，步骤见 [deploy/rocketmq/README.md](deploy/rocketmq/README.md)：

~~~text
deploy\rocketmq\start-namesrv.cmd
deploy\rocketmq\start-broker.cmd
~~~

6. 从项目根目录启动后端：

~~~powershell
$env:DB_PASSWORD = '填写本机数据库密码'
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '6379'
# Redis 开启认证时设置 REDIS_PASSWORD。
# RocketMQ 不在本机时设置 ROCKETMQ_NAME_SERVER，默认 127.0.0.1:9876。
mvn spring-boot:run
~~~

7. 单独安装 Nginx，使用仓库中的配置和前端资源。Windows 示例：

~~~powershell
New-Item -ItemType Directory -Force nginx-1.18.0/logs, nginx-1.18.0/temp
& 'D:/tools/nginx/nginx.exe' -p "$PWD/nginx-1.18.0/" -c conf/nginx.conf
~~~

前端地址：http://localhost:8080；后端端口：8081。Nginx 将 /api 请求转发到后端。

图片默认保存到 nginx-1.18.0/html/hmdp/imgs，可通过 JVM 属性 hmdp.image-upload-dir 修改。附近商铺的 GEO 数据可通过 HmDianPingApplicationTests.loadShopData 导入开发环境。

## 秒杀写策略与关单配置

`seckill.mode` 决定入口和消费者谁负责扣库存：

- `A`（默认）：入口执行 seckill.lua 预扣库存并占用资格，通过 RocketMQ 事务消息投递下单；
  本地事务与半消息提交绑定，本地事务失败则半消息回滚。Lua 会写 `seckill:tx:{orderId}` 作为
  Broker 回查依据，避免「库存已扣但消息丢了」。
- `B`：入口只做排队并投递普通消息，库存扣减与一人一单下移到消费者里用 seckill_claim.lua 认领，
  峰值从入口 Redis 热点转移到消费端（库存速率 = 消费速率）。

`trade.close-delay-level` 是延迟关单的 RocketMQ 延迟级别（4.x 只有 18 个固定档位，16 表示 30 分钟），
本地演示改成 `3`（10 秒）即可快速观察自动关单；`trade.reconcile-cron` 控制对账任务频率，
对账以订单表为账本校正 Redis 与 MySQL 库存并兜底补关单。

## 构建

~~~text
mvn -DskipTests package
~~~

现有集成测试依赖 MySQL 和 Redis，部分测试会写入数据，请使用独立测试环境。

## 待完善

- 支付为模拟实现，未对接真实支付渠道；申请退款直接落到已退款，未经过「退款中」状态。
- 商铺、优惠券、上传接口的角色权限（当前仅校验登录，不区分商家/普通用户）。
- 验证码仅输出到开发日志，未接短信通道。
- 智能助手暂未接入语音与多模态，知识库为本地 JSON 存储。
