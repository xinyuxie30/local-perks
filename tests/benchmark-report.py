"""Summarize retained HTTP results; never synthesize performance observations."""
import hashlib
import json
import pathlib
import statistics

ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'output/benchmarks/localperks-2026-10-26'
def read(name): return json.loads((OUT/(name+'.json')).read_text(encoding='utf-8'))
def med(rows,key): return statistics.median(r[key] for r in rows)
def percentile(rows,p): return statistics.median(r['latency_ms'][p] for r in rows)
cache={label:[read(f'cache-{label}-r{i}') for i in range(1,4)] for label in ['l2','l1-l2']}
orders=[read(f'seckill-unique-r{i}') for i in range(1,4)]
order_evidence=[read(f'seckill-unique-r{i}-evidence') for i in range(1,4)]
oversub=read('seckill-oversubscribe'); rate=read('seckill-rate-limit')
summary={'cache':{},'seckill':{},'oversubscribe':oversub['outcomes'],'rate_limit':rate['outcomes']}
for label,rows in cache.items():
    summary['cache'][label]={'qps_median':med(rows,'successful_qps'),'qps_min':min(r['successful_qps'] for r in rows),
       'qps_max':max(r['successful_qps'] for r in rows),'p95_ms_median':percentile(rows,'p95'),
       'p99_ms_median':percentile(rows,'p99'),'total_requests':sum(r['requests'] for r in rows),
       'errors':sum(r['unexpected_errors'] for r in rows)}
summary['seckill']={'successful_admission_qps_median':med(orders,'successful_qps'),
    'p95_ms_median':percentile(orders,'p95'),'p99_ms_median':percentile(orders,'p99'),
    'persisted_batch_tps_median':med(order_evidence,'persisted_orders_per_batch_wall_second'),
    'total_orders':sum(r['outcomes']['success'] for r in orders),
    'errors':sum(r['unexpected_errors'] for r in orders)}
baseline=summary['cache']['l2']; improved=summary['cache']['l1-l2']
summary['cache']['qps_median_change_percent']=(improved['qps_median']/baseline['qps_median']-1)*100
summary['cache']['p95_median_reduction_percent']=(1-improved['p95_ms_median']/baseline['p95_ms_median'])*100
metadata={'date':'2026-10-26','os':'Windows 11; Redis runs inside WSL Ubuntu',
 'cpu':'Intel Core i5-14600KF; 14 cores / 20 logical processors','physical_memory_bytes':34138931200,
 'java':'Zulu OpenJDK 1.8.0_504','mysql':'8.0.46','redis':'6.2.6','rocketmq':'4.9.7','node':'24.16.0',
 'git_head':'458cae5aa8e90d851d110686663370379cb2481d',
 'app_heap':'-Xms512m -Xmx1g','mysql_buffer_pool':'512 MiB','mq_heap':'512 MiB',
 'app_ports':[8081,8082],'mysql_port':13306,'redis_port':16379,'mq_namesrv_port':19876,'mq_broker_port':20911,
 'database':'localperks_bench','seckill_mode':'A','rate_limit_enabled':True,
 'http_client':'Node built-in HTTP/1.1 keep-alive; closed loop; 100 connections; no request retries',
 'warmup':'cache: 10 seconds each; seckill: 200 requests excluded',
 'cache_data':'GET /shop/1; anonymous hot-key reads; no login Redis traffic; database has 14 seed shops',
 'cache_control':'same jar/config except port and L1 enabled switch; disabled instance bypasses get/put',
 'seckill_data':'5000 distinct users and stock=5000 per round; fixture login hashes; normal auth/rate limit/messaging/SQL paths',
 'boundaries':['Direct backend HTTP; Nginx/Gateway and external AI service are not in the timed path',
               'Single machine shared by client and services; client CPU/event loop can limit measured throughput',
               'Closed-loop load, not an open-loop capacity or saturation proof; no injected failures',
               'Warm single hot key; not a claim about real-world cache hit rate or cold/mixed traffic',
               'Admission latency is not time-to-order-persist; batch completion separately observed by SQL polling',
               'Local ASYNC_FLUSH MQ; no process-crash durability conclusion'],
 'jar_sha256':hashlib.sha256((ROOT/'target/local-perks-0.0.1-SNAPSHOT.jar').read_bytes()).hexdigest()}
for name,obj in [('summary.json',summary),('environment.json',metadata)]:
    with (OUT/name).open('x',encoding='utf-8') as fp: json.dump(obj,fp,ensure_ascii=False,indent=2)
lines=['# LocalPerks 本地真实 HTTP 压测报告（2026-10-26）','',
 '## 口径与环境','',
 '使用真实 Spring Boot、Redis、RocketMQ、MySQL；HTTP 客户端与服务端同机。接口直接访问 8081/8082，计时不经过 Nginx、Gateway 或 AI 助手。',
 'i5-14600KF（14 核 / 20 线程）、约 31.8 GiB 内存；Java 8u504、MySQL 8.0.46、WSL Redis 6.2.6、RocketMQ 4.9.7。JVM 堆 512 MiB–1 GiB。',
 '隔离数据：MySQL 13306 / localperks_bench、Redis 16379、NameServer 19876、Broker 20911；未对原 hmdp 开发库造单或清库。',
 '默认每用户每秒 5 次限流保持开启。测试登录态由脚本写入隔离 Redis，真实请求仍经过认证、限流、Lua、事务消息和数据库落库。',
 '', '## 热点缓存：100 并发，10 秒预热，3 轮 × 30 秒','',
 '两实例使用同一 JAR，通过 app.cache.shop-local-enabled=false 关闭对照实例的 L1；按 L2/L1、L1/L2、L2/L1 顺序交替测量。每次读取同一个已预热的商铺，校验 HTTP 200、success=true、data 非空。',
 '', '| 配置/轮次 | 成功请求数 | 成功 QPS | P50 / P95 / P99（ms） | 非预期错误 | L1 命中 | Redis GET | DB 商铺读取 |',
 '|---|---:|---:|---|---:|---:|---:|---:|']
for label,rows in cache.items():
    for i,r in enumerate(rows,1):
        d=read(f'cache-{label}-r{i}-evidence')['deltas']; l=r['latency_ms']
        lines.append(f"| {label} / {i} | {r['requests']} | {r['successful_qps']:.1f} | {l['p50']:.2f} / {l['p95']:.2f} / {l['p99']:.2f} | {r['unexpected_errors']} | {d['l1_hits']} | {d['redis_get_calls']} | {d['shop_rows_read']} |")
lines += ['',f"三轮成功 QPS 中位数：Redis 基线 {baseline['qps_median']:.1f}，两级缓存 {improved['qps_median']:.1f}；变化 {summary['cache']['qps_median_change_percent']:.1f}%。各轮 P95 的中位数为 {baseline['p95_ms_median']:.2f} → {improved['p95_ms_median']:.2f} ms。",
 '中位数由各轮统计量计算，不是合并样本百分位。原始逐请求延迟在 *.raw.json.gz 中；client_cpu_seconds 和 client_event_loop_p99_ms 用于识别发压端限制。',
 '零容量及零 TTL 的预演存在短暂 L1 命中，已单独保存在 pilot-* 目录，未纳入正式结果。',
 '', '## 秒杀：100 并发，3 轮 × 5000 个不同用户 / 5000 份库存','',
 '| 轮次 | HTTP 成功受理 QPS | 接口 P95 / P99（ms） | 批次全部落库耗时上界（s） | 落库数 / 批次总时长（单/s） | HTTP 结束首次观测订单数 | 最终订单数 |',
 '|---|---:|---|---:|---:|---:|---:|']
for i,(r,e) in enumerate(zip(orders,order_evidence),1):
    l=r['latency_ms']; v=e['after']['voucher']
    lines.append(f"| {i} | {r['successful_qps']:.1f} | {l['p95']:.2f} / {l['p99']:.2f} | {e['batch_completion_seconds_upper_bound']:.3f} | {e['persisted_orders_per_batch_wall_second']:.1f} | {e['first_observed_order_count_after_http_batch']} | {v['orderCount']} |")
lines += ['', '接口返回只代表受理；落库结果通过独立 SQL 核对。批次时间从第一个 HTTP 请求发出算到 SQL 观测到全部成功订单，包含请求阶段、排队及轮询开销；轮询间隔 250 ms，因此为完成时间上界，不是单笔订单 P95 或消费者峰值 TPS。',
 f"三轮受理 QPS 中位数 {summary['seckill']['successful_admission_qps_median']:.1f}，合计 {summary['seckill']['total_orders']} 笔成功订单。核对返回的订单 ID 集合与数据库 ID 集合、Redis / MySQL 库存、占用人数和重复用户券分组。详见每轮 evidence.json。", '',
 '## 超卖、重复提交与限流','']
for name,label in [('seckill-oversubscribe','2000 用户各提交 2 次，4000 请求竞争 1000 库存'),('seckill-rate-limit','同一用户 100 并发请求，库存 10')]:
    r=read(name);e=read(name+'-evidence')
    lines += [f"- {label}：HTTP 结果 {json.dumps(r['outcomes'],ensure_ascii=False)}；总用时 {r['elapsed_seconds']:.3f}s。",
              f"  最终订单 {e['after']['voucher']['orderCount']}，Redis / MySQL 剩余库存 {e['after']['voucher']['redisStock']} / {e['after']['voucher']['dbStock']}；校验 {json.dumps(e['consistency'],ensure_ascii=False)}。"]
lines += ['', '库存不足、重复提交与 HTTP 429 单独统计为预期业务拒绝，不能计入成功下单 QPS。超卖结论仅表示这些已执行批次中未发生超卖/重复建单。',
 '', '## 复现与文件','',
 '1. tests/benchmark-start.ps1 启动依赖；tests/benchmark-fixtures.py init 仅允许创建不存在的独立 schema，拒绝覆盖。',
 '2. tests/benchmark-app.ps1 及 -Baseline 启动两个实例；tests/benchmark-suite.py cache 执行缓存对照。',
 '3. 停止本次对照实例，等消费者重平衡；tests/benchmark-suite.py seckill 执行订单场景。',
 '4. tests/benchmark-report.py 生成报告；输出使用排他写入，重跑请换输出目录和新券 ID，不覆盖历史证据。',
 '5. tests/benchmark-nginx.ps1 可启动前端入口 http://127.0.0.1:8080，仍连接本次隔离后端。',
 '', '本次只测正常依赖下的后端并发，不涵盖故障注入、支付网关、跨机部署、线上流量或智能助手的真实模型响应。Harness 继续使用代码已实现的机制描述，不套用这些 QPS。',
 '脚本保留所有正式结果及预演，environment.json 保存软件版本、参数及 JAR SHA-256。现有缓存路径回归测试 5 项通过。']
(OUT/'REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False,indent=2))
