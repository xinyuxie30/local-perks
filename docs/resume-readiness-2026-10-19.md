# 简历可用性与压测方案复核（2026-10-19）

## 0. 结论

**可以写，而且现在就能写一版“机制版”；但不建议现在写“高并发 + QPS”。**

三条依据：

1. 交易、缓存、限流、内容社交、管理端和 Agent 已经形成可演示的闭环，不是只有目录和接口壳子。
2. 关键正确性有代码和测试支撑：Lua 原子预扣、事务消息回查、消费幂等、唯一索引、状态机、对账 CAS、Bloom 降级、限流 fail-open，都有对应实现或回归用例。
3. 目前缺的不是功能，而是**可验证的性能结论**。仓库里没有 HTTP 层压测脚本、没有 JMeter 报告、没有 CI，也没有“多少并发、多少 QPS、p99 多少”的记录。

参考图里的 800 QPS、1000→223→728、1376→2514→3474 都不能直接搬。面试官只要追问“什么机器、多少并发、测多久、瓶颈在哪、优化前后差多少”，抄来的数字就会变成减分项。

**建议的最终形态：**

- 本周先写“机制版”，保证每条都能落到代码和测试。
- 再用 0.5–1 天压 2 个场景（秒杀入口、店铺详情缓存），拿到 2–3 个可信数字，替换成“数据版”。
- Agent 单独作为第二个项目写，不要和交易主链路挤在一个项目里。

---

## 1. 项目现状核查

核查基准：`458cae5`（2026-10-19），工作区仅 `docs/teaching-assistant/` 未跟踪。当前 MySQL 3306、Redis 6379 在运行，RocketMQ 9876、后端 8081、Nginx 8080、EchoMind 18080 未运行，因此本次是“源码 + 历史测试记录”核查，不是全链路复跑。

### 1.1 已经落地的能力

| 领域 | 实际实现 | 证据 | 验证强度 |
| --- | --- | --- | --- |
| 秒杀交易 | Redis + Lua 单脚本完成库存校验、一人一单判定、预扣、事务标记；RocketMQ 事务消息 + 本地事务 + Broker 回查；消费端订单主键 + `(user_id, voucher_id)` 唯一索引 + Redisson 用户锁幂等；延迟消息关单；`UNPAID→PAID→USED`、`UNPAID→CANCELED`、`PAID→REFUNDED` 状态机 | `seckill.lua`、`VoucherOrderServiceImpl`、`SeckillTransactionListener`、`SeckillOrderConsumer`、`OrderStateMachineTest` | 高（有并发功能测试与状态机测试） |
| 最终一致性 | 以订单表为账本的对账任务；默认只观测差异，`auto-fix=false`；修复用 Redis CAS + MySQL 条件更新；受控预热拒绝有历史订单/有占用的券 | `TradeReconcileTask`、`AdminServiceImpl`、`TradeReconcileSafetyTests`、`AdminWarmupTest`、`StockWarmupScriptTest` | 中高（有并发/安全回归；单实例、单值 CAS 仍有 ABA） |
| 多级缓存 | L1 Caffeine → Redis Bloom → L2 Redis；L1 命中不访问 Redis；空值缓存防穿透；Pub/Sub 广播失效 + TTL 兜底；Bloom 查询/写入异常时本实例降级放行 | `ShopServiceImpl`、`ShopLocalCache`、`ShopBloomFilter`、`ShopCacheInvalidationPublisher`、`MultiLevelCacheTest`、`ShopCacheLookupTest`、`ShopBloomFilterTest` | 中高（有单测证明调用顺序与降级；无吞吐对比） |
| 接口治理 | `@RateLimit` + AOP + SpEL + Redis ZSET 滑动窗口，支持全局/用户/IP 维度；超限 429 + Retry-After；Redis 故障 fail-open；统一异常映射 400/403/405/413/429 | `RateLimitAspect`、`rate_limit.lua`、`RateLimitAspectTest`、`WebExceptionAdvice` | 中高（窗口放行/拒绝/恢复有测试） |
| 内容与社交 | 点赞 Redis ZSet + Lua 原子 toggle；Feed 推模式写粉丝收件箱，按时间戳滚动分页；关注双写 + Set 交集共同关注；签到 Bitmap + BITFIELD 统计连续天数；评论两级结构、点赞、按可见评论重算计数 | `BlogServiceImpl`、`like_toggle.lua`、`FollowServiceImpl`、`UserServiceImpl`、`BlogCommentsServiceImpl`、`CommentServiceTest` | 中（功能与部分测试；Feed 同步写粉丝，规模大时需异步） |
| 管理端 | `/admin/seckill/{id}/warmup`、`/stats`、`/reconcile`、`/cache/stats`；`@AdminOnly` 白名单 | `AdminController`、`AdminServiceImpl`、`AdminGuardTest` | 中高 |
| Agent | EchoMind 独立服务：Spring AI 1.1 + DeepSeek；手动 Agent Loop；21 个工具（6 个写工具）；逐调用 HITL；Redis Lua 原子审批认领；SSE；上下文压缩；长期记忆；3 个只读子 Agent；Skills 按需加载；BM25 + n-gram 哈希向量知识检索 | `EchoMindJava`（80 个 Java 文件）、`progress-review-2026-10-18.md` | 中高（41 项隔离测试历史通过；运行回执仍主要在内存） |
| 前端 | 12 个页面；个人中心三 tab、订单页服务端时钟校准、助手控制台（对话/功能/专家/记忆）、审批卡、SSE 分片处理 | `nginx-1.18.0/html/hmdp/`、`tests/frontend-regression.cjs` | 中（10 项浏览器回归历史 10/10，接口为模拟） |
| 测试 | 14 个测试类、45 个 `@Test`；覆盖秒杀并发（100 任务/20 线程/库存 10）、状态机、缓存、Bloom、限流、预热并发、对账安全 | `src/test/java/com/hmdp/` | 中高（部分依赖 MySQL/Redis/MQ，当前未全量复跑） |
| 交付 | README、V2/V3 迁移脚本、RocketMQ 启动脚本、9 份设计与复核文档、Git 提交链 | `README.md`、`docs/`、`deploy/` | 中（无 CI、无 Docker、无压测报告、无公开仓库） |

### 1.2 当前不能宣称的能力

| 想写的说法 | 实际情况 | 处理 |
| --- | --- | --- |
| 高并发、万级 QPS、800 QPS | 没有 HTTP 压测；只有 service 层 100 任务并发功能测试 | 压测后再写，或只写“并发正确性” |
| 分库分表、ShardingSphere | 未实现 | 不写 |
| Elasticsearch、Canal | 未实现，文档明确暂缓 | 不写 |
| Kafka | 实际用 RocketMQ | 不要写 Kafka |
| 微服务、Gateway、Nacos | 只有 EchoMind 独立服务，没有注册中心/网关治理 | 不写“微服务架构” |
| 双机/集群部署 | 单机本地部署 | 不写 |
| 真实支付、短信 | 模拟支付；验证码只打日志 | 写“模拟支付”或不写 |
| 退款回补库存 | 退款不回补；取消才回补；终身一单资格保留 | 不要写“退款回补库存” |
| 向量检索/RAG 平台 | BM25 + 字符 n-gram 哈希；VectorStore 未接入搜索路径 | 写“轻量知识检索” |
| 多 Agent 提升 X% | 没有单/多 Agent 对比评测 | 不写收益数字 |
| 对账强一致 | 单实例 + 单值 CAS，存在 ABA；默认只观测 | 写“观测/修复分离 + CAS 降低覆盖风险” |
| 26 个测试/121 用例 | 那是参考图的项目，不是我们的 | 不要混用 |

### 1.3 就绪度判断

| 维度 | 判断 |
| --- | --- |
| 功能闭环 | 完成。登录→找店→领券/秒杀→下单→支付/取消/核销/退款→对账；内容、社交、评论、Agent 都有页面和接口 |
| 正确性证据 | 中等偏上。关键路径有测试，但部分依赖真实中间件，未在当前环境全量复跑 |
| 性能证据 | 缺失。没有 HTTP 压测、没有报告、没有瓶颈分析 |
| 工程化证据 | 偏弱。无 CI、无容器化、无公开仓库、无部署脚本（只有本地启动说明） |
| 简历就绪度 | 机制版可写；数据版待压测；工程化版待补 CI/仓库 |

---

## 2. 与四份参考写法逐项对照

| 参考亮点 | 我们的情况 | 判断 |
| --- | --- | --- |
| Redis + Lua 原子扣减、一人一单 | 有，且额外做了事务标记与 Broker 回查 | 可以写，且比参考更深 |
| RocketMQ/Kafka 异步秒杀、削峰 | 有 RocketMQ 事务消息、延迟关单、对账 | 可以写，注意别写 Kafka |
| 布隆过滤器 + 空值缓存 + Caffeine 二级缓存 | 有 L1→Bloom→L2、Pub/Sub 失效、故障降级 | 可以写，但缺吞吐对比 |
| AOP + SpEL + Redis 滑动窗口限流 | 有，支持多维度、429 + Retry-After、fail-open | 可以写 |
| 逻辑过期解决热点 Key | 未采用逻辑过期；用 L1 + TTL + Pub/Sub | 不要写逻辑过期 |
| Cache-Aside + 消息队列补偿 + TTL 兜底 | 有 Cache-Aside + Pub/Sub + TTL；没有 MQ 补偿缓存 | 写“Pub/Sub 失效 + TTL 兜底” |
| 分布式锁 + 唯一索引 | 有 Redisson 锁 + 唯一索引 | 可以写，但要讲清锁不是最终防线 |
| 订单状态机 + 乐观锁/条件更新 | 有状态机 + `WHERE status=?` 条件更新 | 可以写 |
| EasyExcel 批量发券 | 未实现 | 不写 |
| 分片广播、XXL-Job、BufferTrigger | 未实现 | 不写 |
| Spring Retry + 死信队列 | RocketMQ 重试 + 死信 + 对账收口；没有 Spring Retry | 写 MQ 重试/死信/对账 |
| Testcontainers、GitHub Actions、Docker Compose、双机部署 | 未实现 | 不写；可作为后续加分项 |
| 公开 GitHub 链接、测试数量、压测前后对比 | 仓库私有；14 类/45 用例；无压测 | 补齐后竞争力明显提升 |

**关键判断：**

- 参考 1、2、4 的“机制密度”我们并不输，甚至交易一致性更深（事务消息回查、对账观测/修复分离、预热保护）。
- 参考 3 的“可验证性”我们明显不足：公开仓库、CI、容器化、真实中间件集成测试、压测报告。这是简历筛选时最容易形成差距的部分。
- 参考 1 和我们同源（都是 Redis 秒杀 + MQ + 缓存 + 限流），它用 JMeter 800 QPS 把项目“抬”起来了。我们没有数字，就会在同一档简历里显得单薄。所以**压测不是可选项，是把机制优势兑现成简历竞争力的一步**。

---

## 3. 三档清单：稳写、慎写、不写

### A. 稳写（有代码 + 测试/演示，面试可追到实现）

- Redis + Lua 原子预扣、一人一单、库存不足/重复下单/未预热三种返回码。
- RocketMQ 事务消息：半消息、本地事务、`seckill:tx:{orderId}` 回查标记、COMMIT/ROLLBACK/UNKNOW 三态处理。
- 消费幂等：订单主键 + `(user_id, voucher_id)` 唯一索引 + Redisson 用户级锁；重复消息回滚本次扣减。
- 延迟消息关单 + 定时对账兜底；对账默认只观测，修复用 Redis CAS + MySQL 条件更新。
- 受控预热：有历史订单/有 Redis 占用/库存快照变化一律拒绝，`force=true` 也不能绕过。
- L1 Caffeine → Bloom → L2 Redis；L1 命中不访问 Redis；空值缓存；Pub/Sub 失效 + TTL；Bloom 异常降级。
- AOP + SpEL + ZSET 滑动窗口限流；全局/用户/IP 维度；429 + Retry-After；fail-open。
- 点赞 ZSet + Lua toggle；Feed 推模式 + 滚动分页；关注双写 + 共同关注；签到 Bitmap；评论两级 + 计数重算。
- Agent：手动循环、21 工具、逐调用审批、Lua 原子认领、SSE、上下文压缩、长期记忆、只读子 Agent、Skills 按需加载。
- 测试：14 个测试类 / 45 个用例；前端 10 项浏览器回归。

### B. 慎写（有实现，但必须限定口径）

| 想写 | 限定写法 |
| --- | --- |
| QPS/延迟 | 只在压测后写，且带机器、并发、时长、p99、错误率 |
| 多级缓存收益 | 可写“L1 命中不访问 Redis”，不要写“提升 N 倍”直到有对比 |
| 多 Agent | 可写“三个只读子 Agent、工具白名单、上下文隔离”，不要写“提升完成率” |
| 知识检索 | 写“BM25 + 字符 n-gram 哈希向量”，不要写“向量数据库/RAG 平台” |
| 对账 | 写“观测/修复分离 + CAS 降低覆盖风险”，不要写“强一致” |
| 退款 | 写“退款状态流转”，不要写“退款回补库存” |
| 支付/核销 | 写“模拟支付/模拟核销”，不要写“对接支付渠道” |
| 单机 | 写“单机部署”，不要写“集群/双机” |

### C. 不写（未做或不可信）

分库分表、ShardingSphere、ES、Canal、Kafka、Gateway/Nacos、Docker/K8s、双机、真实支付、短信通道、百万用户、万级 QPS、800 QPS、线上运行、26 个测试/121 用例。

---

## 4. 简历条目草稿

> 时间按实际开发时间调整。若从 2026 年 10 月才开始，写 `2026.10 – 至今`；不要为了凑 3 个月编造。
>
> 技术名词加粗；每条控制在 2 行以内；主项目 5–6 条，Agent 3–4 条。

### 4.1 主项目：机制版（现在就能用，不含 QPS）

**智惠生活通 LocalPerks · 本地生活优惠券交易平台**  
`2026.09 – 2026.10` | 个人项目  
**技术栈：** Java 8、Spring Boot 2.3、MyBatis-Plus、MySQL、Redis、Redisson、RocketMQ、Caffeine、Nginx、Vue2 + Element UI

- **秒杀一致性：** 基于 **Redis + Lua** 在单脚本内完成库存校验、一人一单判定与预扣，结合 **RocketMQ 事务消息**（半消息 + 本地事务 + `seckill:tx:{orderId}` 回查标记）保证“库存已扣”与“消息可投递”一致；消费端用订单主键、`(user_id, voucher_id)` 唯一索引和 **Redisson** 用户级锁做幂等，覆盖重复投递与并发下单。
- **异步交易闭环：** 入口只做资格校验与消息投递，订单异步落库；**RocketMQ 延迟消息**关闭超时未支付订单，定时对账以订单表为账本校正 Redis/MySQL 库存；对账默认只观测，修复使用 **Redis CAS + MySQL 条件更新**，避免覆盖正常扣减。
- **多级缓存：** 店铺详情按 **Caffeine L1 → Redis Bloom → Redis L2** 读取，L1 命中不访问 Redis；空值缓存防穿透，**Pub/Sub** 广播失效 + TTL 兜底；Bloom 读写异常时本实例降级放行，避免新店被误挡。
- **接口治理：** **AOP + SpEL + Redis ZSET** 实现滑动窗口限流，支持全局/用户/IP 维度，超限返回 429 + Retry-After；Redis 故障时 fail-open；统一异常处理映射 400/403/405/413/429。
- **内容与社交：** 点赞用 **Redis ZSet + Lua** 原子 toggle，Feed 采用推模式写入粉丝收件箱并按时间戳滚动分页；关注双写 + Set 交集共同关注；签到 **Bitmap**；评论两级结构与计数重算。
- **质量保障：** 14 个测试类 / 45 个用例覆盖秒杀并发（100 任务、20 线程、库存 10）、订单状态机、缓存降级、Bloom、限流、预热并发与对账安全；10 项浏览器回归覆盖审批、中文输入、SSE 分片、排序竞态与翻页重试。

### 4.2 主项目：压测增强版（数字必须来自报告）

在 4.1 基础上替换/新增以下 2–3 条，数字用实测：

- **秒杀入口压测：** 单机 JMeter，`<100>` 并发、`<60s>`、库存 `<5000>`，入口受理 QPS `<____>`、p99 `<____ms>`、错误率 `<__%>`；压后 Redis 库存 0、订单 `<____>` 条、无重复用户/负库存。
- **缓存收益对比：** 店铺详情 L1 命中 QPS `<____>`（p99 `<____ms>`），较关闭缓存的 `<____>` 提升 `<__>` 倍；L1 命中路径不访问 Redis。
- **模式 A/B 对比：** 入口受理 QPS A `<____>` → B `<____>`，落库 TPS `<____>`，最终一致耗时 `<____s>`；定位 `<Lettuce 连接池 / MQ 发送 / DB 连接池>` 为瓶颈，调整后 QPS 从 `<____>` 提升到 `<____>`。

> 写法要点：即使实测只有 300 QPS，也写 300，并写清“单机、同机压测、瓶颈在 X”。真实且能解释的数字，比抄来的 800 更抗追问。

### 4.3 Agent 项目（建议单独一段）

**EchoMind · 本地生活智能客服 Agent**  
`2026.10` | 个人项目  
**技术栈：** Java 21、Spring Boot 3.5、Spring AI 1.1、DeepSeek、Redis、SSE

- **Agent Loop：** 基于 **Spring AI 1.1 + DeepSeek** 实现独立客服服务，采用“模型选工具 → 参数校验 → 执行 → 结果回填 → 再请求”的手动循环，注册 **21 个**业务/平台工具，设置轮次上限、总超时、同参去重与失败回填，避免循环失控。
- **写操作 HITL：** 抢券/支付/取消/核销/退款/签到统一走二次确认，审批令牌用 **Redis Lua 原子抢占**（userId + conversationId + toolCallId），拒绝/重复确认不产生业务与记忆副作用；**SSE** 推送正文与工具事件，前端逐调用渲染确认卡。
- **上下文与多 Agent：** 按 token 预算保留历史，超限做 LLM 摘要压缩；长期记忆按用户隔离；`delegate_task` 把检索子任务派给导购/内容/订单三个**只读子 Agent**，子 Agent 使用工具白名单、上下文隔离、只回摘要，业务写操作保留在主 Agent。
- **技能与知识：** 技能索引常驻、正文按需加载；平台规则问题走轻量知识检索（**BM25 + 字符 n-gram 哈希向量**）。

### 4.4 排版建议

- 投 Java 后端：主项目放第一，Agent 放第二，避免“AI 项目”盖过交易基本功。
- 投 AI 应用/Agent 岗：Agent 放第一，主项目作为“业务系统底座”。
- 每条 bullet 用“技术 + 机制 + 结果”结构，不要写成功能清单。
- 技术栈只写真正用过的；不要把参考图里的 ShardingSphere、Kafka、EasyExcel 抄进来。
- 如果公开仓库：先确认 `.env`、`application-local.yaml` 未被跟踪（当前 `.gitignore` 已排除），再清理测试数据、截图和内部审计文档。

---

## 5. 压测方案

### 5.1 先明确：压测要回答什么

不要为了“有个 QPS 数字”而压。要回答四个问题：

1. 秒杀入口在单机上能受理多少请求？p99 和错误率是多少？
2. 异步落库最终能不能对上账？压完库存、订单、占用集合是否一致？
3. 多级缓存到底省了多少？L1 命中、L2 命中、回源三档差多少？
4. 瓶颈在哪？是 Redis、MQ、DB、连接池，还是 Nginx/JVM？

对应四组指标：吞吐、延迟、正确性、资源/瓶颈。

### 5.2 工具与环境

- **工具：** JMeter 5.6.3，非 GUI 模式，生成 HTML 报告。理由：Java 生态常用、支持 CSV 参数化、报告可存档、简历认可度高。
- **机器：** 本机 20 核（`nproc=20`），Windows。压测机与被测服务同机，CPU/内存会互相争抢，数字偏保守；报告里必须写明“单机同机压测”，不要冒充独立压测机。
- **服务：** MySQL 3306、Redis 6379 已运行；需启动 RocketMQ NameServer/Broker、后端 8081、Nginx 8080。建议直接用 8081 测后端容量，另跑一轮 8080 验证接入层。
- **JVM：** `-Xms2g -Xmx2g`，固定堆，避免压测中扩容干扰。
- **日志：** 压测时把 `logging.level.com.hmdp` 从 `debug` 调到 `info`，否则日志 I/O 会显著拉低吞吐。
- **预热：** 先跑 30s 低并发预热 JIT/连接池，再开始计时；每档 60s。
- **隔离：** 独立数据库、Redis db index、MQ topic/consumer group；压测前清空测试券和订单；`trade.reconcile.auto-fix=false`。

### 5.3 数据准备

**测试用户与 token：**

- 造 2000 个测试用户（`tb_user` id 9000001–9002000），或至少 1000 个。
- 登录态不是 String 而是 Hash：`HSET login:token:<uuid> id <userId> nickName <name> icon ""`，`EXPIRE` 30 分钟。
- 用 pipeline 批量写入，生成 CSV：`token,userId`。
- 秒杀接口按用户维度限流 5 次/秒，必须用不同 token；同一 token 压会大量 429，测的是限流不是秒杀。

**测试券：**

- 新建一张秒杀券，库存 5000，`begin_time` 早于当前、`end_time` 晚于当前；`tb_seckill_voucher` 与 `tb_voucher` 同时插入。
- 调 `POST /admin/seckill/{id}/warmup`（管理员 token，默认 userId 1011）预热 Redis 库存与时间窗缓存。
- 确认 `seckill:stock:{id}`、`seckill:order:{id}`、`CACHE_SECKILL_VOUCHER_KEY:{id}` 存在。

**店铺数据：**

- 选 100 个已存在店铺，先逐个 `GET /shop/{id}` 预热 L1/L2；L1 只在本实例有效，重启后为空。
- 记录每个店铺 id，压测时随机取，避免单 key 热点掩盖分布问题。

### 5.4 场景设计

#### 场景 1：秒杀入口（模式 A，必做）

- **接口：** `POST /voucher-order/seckill/{voucherId}`，Header `authorization: <token>`。
- **线程：** 50 / 100 / 200 三档，ramp-up 10s，duration 60s，loop forever。
- **断言：** HTTP 200；`success=true` 返回订单号，或 `success=false` 且错误为“库存不足/手速太快”。
- **统计：** 受理成功数、库存不足数、429 数、其他错误数；吞吐、p50/p95/p99。
- **压后核对（等 30s 让 MQ 消费完）：**
  - `GET /admin/seckill/{id}/stats` 看 DB/Redis 库存、占用人数、订单状态分布。
  - Redis `GET seckill:stock:{id}` 应为 0；`SCARD seckill:order:{id}` 应等于库存。
  - MySQL `SELECT COUNT(*), COUNT(DISTINCT user_id) FROM tb_voucher_order WHERE voucher_id=?` 应等于库存，且两者相等。
  - 检查无负库存、无重复 `(user_id, voucher_id)`。
- **注意：** 入口 QPS 是“受理 QPS”，不是“落库 TPS”。异步链路要单独看订单表增长速率和 MQ 堆积。

#### 场景 2：秒杀模式 B（可选，但很适合写简历）

- 改 `seckill.mode=B`，重启后端；入口只投递，库存在消费端 `seckill_claim.lua` 认领。
- 同样的线程与库存，记录入口 QPS、落库 TPS、最终一致耗时。
- 对比 A/B：A 入口更重但资格判定前置；B 入口更轻但所有请求都受理，胜负由消费端决定。这个对比能讲清“削峰”和“一致性窗口”的取舍。

#### 场景 3：店铺详情三档（最稳的缓存收益证据）

- **L1 命中：** 预热后，单实例重复请求同一批店铺；此时 Caffeine 命中，不访问 Redis。
- **L2 命中：** 需要让 L1 为空。两种做法：
  1. 压测前重启后端（L1 清空），只预热 Redis；第一轮请求会回源并填充 L1，所以要在报告中区分“冷启动轮”和“稳定轮”。
  2. 更严谨：加一个仅测试 profile 的开关 `app.cache.local-enabled=false`，单独跑 L2/回源。这个开关本身也是可讲的降级能力。
- **回源：** 关闭 L1 且删除 Redis key，或直接停 Redis（会触发降级，不是纯回源，需区分）。
- **指标：** 三档 QPS、p50/p95/p99；同时记录 Redis `INFO stats` 的 `instantaneous_ops_per_sec`，证明 L1 命中时 Redis 操作数接近 0。
- **写法：** “L1 命中 QPS X，较回源 Y 提升 N 倍；L1 命中路径 Redis ops/s 接近 0。”这是最抗追问的一条。

#### 场景 4：限流正确性（不做吞吐，做行为）

- 同一 token 在 1 秒内发 20 次秒杀请求：预期 5 次进入业务、15 次 429，`Retry-After` 存在且合理。
- 不同 token 各 5 次：预期都进入业务，证明维度是用户而非全局。
- 这个场景不产生 QPS 数字，但能证明“限流不是摆设”，面试可讲。

### 5.5 指标记录模板

每轮压测保存一行，最终汇总成表：

| 字段 | 示例 |
| --- | --- |
| 时间 / commit | 2026-10-20 / `458cae5` |
| 机器 / JVM | 20 核 / 16G / JDK8 / `-Xms2g -Xmx2g` |
| 接口 / 模式 | `POST /voucher-order/seckill/12` / A |
| 并发 / 时长 | 100 / 60s |
| 吞吐 | `____ req/s` |
| p50 / p95 / p99 | `__ / __ / __ ms` |
| 错误率 | `__%`（其中 429 `__%`） |
| Redis ops/s | `____` |
| MySQL QPS / 连接数 | `____ / ____` |
| MQ 堆积 | `____` |
| 压后一致性 | 库存 `__`、订单 `__`、占用 `__`、重复 `__` |
| 瓶颈判断 | 例：Lettuce `max-active=10` 等待 |

保存位置：`docs/perf/2026-10-20/`，包含 `.jmx`、`.jtl`、HTML 报告、启动命令、机器信息、SQL/Redis 准备脚本。**简历数字只能来自这里。**

### 5.6 预期瓶颈与优化点（面试素材）

按可能性排序：

1. **Lettuce 连接池 `max-active=10`**：并发一上来就会出现连接等待。调到 50/100 再测，QPS 可能明显上升。这是最容易讲的一条“定位→调整→复测”。
2. **HikariCP 默认 10 连接**：店铺回源、订单落库会打满；看 `SHOW GLOBAL STATUS LIKE 'Threads_connected'` 和 Hikari 指标。
3. **RocketMQ 事务消息 RTT**：入口 QPS 受 Broker 往返和本地事务影响；看 producer send latency、Broker 异步刷盘配置。
4. **Nginx worker/keepalive**：走 8080 时，Nginx 自身可能成为瓶颈；对比 8081 直连。
5. **日志级别 debug**：压测时务必调 info。
6. **同机 JMeter 争抢 CPU**：20 核也要看 JMeter 自身 CPU；必要时降到 50 并发，或把 JMeter 放到另一台机器/WSL。
7. **Windows 端口与 TIME_WAIT**：用 keep-alive，避免每请求新建连接；JMeter 默认 keep-alive 要确认开启。

### 5.7 半天 / 一天 / 两天三种做法

| 投入 | 做什么 | 产出 |
| --- | --- | --- |
| 半天 | 起 MQ + 后端；造 500 用户/券；压店铺详情 L1 vs 回源；压秒杀入口 100 并发一档 | 2 条数字：缓存提升、秒杀受理 QPS |
| 1 天 | 加秒杀 A/B、限流正确性、三档缓存、报告与瓶颈分析 | 完整压测报告，可写 3–4 条数据版 bullet |
| 2 天 | 加 GitHub Actions（编译 + 单测）、可选 Testcontainers/公开仓库、简历定稿 | 工程化加分 + 可验证链接 |

> 如果只有半天，优先做“店铺详情 L1 vs 回源”。它最稳、最容易复现、最能讲清缓存价值；秒杀入口受 MQ/事务消息影响，数字波动大。

---

## 6. 面试追问清单

| 追问 | 回答要点 | 代码证据 |
| --- | --- | --- |
| 为什么用 Lua？ | 库存判断、一人一单、扣减、写事务标记必须原子；分开执行会有竞态 | `seckill.lua` |
| Redis 扣了库存，但消息没发出去怎么办？ | 事务消息半消息 + 本地事务 + 回查；`seckill:tx:{orderId}` 标记 COMMIT，Broker 回查据此提交/回滚 | `SeckillTransactionListener` |
| 消息重复投递怎么办？ | 订单主键 + `(user_id, voucher_id)` 唯一索引 + Redisson 用户锁；重复消息回滚本次扣减 | `createOrder`、`V2__trade.sql` |
| 为什么有唯一索引还要分布式锁？ | 锁降低并发冲突，唯一索引是最终防线；锁在事务提交前释放，极端情况仍会漏 | `createOrder` 注释 |
| 对账会不会覆盖正常扣减？ | 默认只观测；修复用 Redis CAS + MySQL 条件更新，读后写之间被正常交易改动就放弃 | `TradeReconcileTask` |
| CAS 就万无一失吗？ | 不是，单值 CAS 仍有 ABA；当前是单实例演示，多实例/高并发要版本号或水位 | 文档已标注 |
| 为什么 L1 在 Bloom 之前？ | L1 命中完全不访问 Redis；Bloom 本身在 Redis，先查它会破坏“L1 零远程调用” | `ShopServiceImpl` |
| Bloom 故障怎么办？ | 查询/写入异常时本实例停用过滤、放行并补建，避免新店被误挡 | `ShopBloomFilter` |
| 缓存一致性怎么保证？ | 事务提交后删缓存；Pub/Sub 广播失效；TTL 兜底；不保证强一致 | `ShopServiceImpl`、`ShopCacheInvalidationPublisher` |
| 限流为什么用 ZSET？ | 滑动窗口，按时间戳淘汰；固定窗口有临界突刺 | `rate_limit.lua` |
| fail-open 会不会被打穿？ | 会，业务可用性优先；Redis 恢复后自动恢复限流；核心接口还有唯一索引/库存兜底 | `RateLimitProperties` |
| 退款回补库存吗？ | 不回补；取消才回补；退款保留终身一单资格。这是演示业务规则，要讲清楚 | 状态机/`refundOrder` |
| Agent 和 Spring AI 默认工具调用有什么区别？ | 关闭 internalToolExecution，自己控制轮次、预算、权限、审批、回填与终止 | `AgentLoopRunner` |
| HITL 怎么防重复确认？ | Redis Lua 原子抢占审批令牌；userId + conversationId + toolCallId 绑定；拒绝/重复不写记忆 | 审批服务 |
| 子 Agent 为什么只读？ | 写操作必须留在主 Agent 的审批链里；子 Agent 只做检索，工具白名单 + 上下文隔离 | `DelegateTool` |
| 你的 QPS 怎么测的？ | 机器、并发、时长、接口、p99、错误率、压后一致性；瓶颈是什么、调了什么、复测多少 | 压测报告 |

---

## 7. 建议排期与下一步

1. **今天：** 用 4.1 的机制版替换简历里的项目描述；Agent 用 4.3。不要写任何 QPS。
2. **下一步（0.5–1 天）：** 按第 5 节压两个场景，优先“店铺详情 L1 vs 回源”和“秒杀入口 100 并发”。把数字填进 4.2。
3. **加分（可选）：** 公开一个脱敏仓库 + GitHub Actions 编译/单测；这比再堆一个功能更能提升简历可信度。
4. **不要做：** 抄参考图的数字；写 ShardingSphere/Kafka/ES；把模拟支付写成真实支付；把单机写成集群。

**一句话：项目已经够写进简历，但先写“做对了什么”，再写“有多快”。前者现在就能交付，后者需要一次诚实的压测。**
