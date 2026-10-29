"""Independently audit raw HTTP samples and summarize prelaunch artifacts."""
import bisect, collections, datetime, gzip, hashlib, json, math, pathlib, re, statistics

ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'output/benchmarks/localperks-prelaunch-2026-10-27'
RUN=ROOT/'tmp/localperks-benchmark-20261027'

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def quantile(values,q):
    values=sorted(values)
    return values[max(0,math.ceil(len(values)*q)-1)] if values else None
def audit(path,result):
    outcomes=collections.Counter();hist=collections.Counter();shist=collections.Counter();orders=[];count=0;status=collections.Counter()
    with gzip.open(str(path)+'.raw.ndjson.gz','rt',encoding='utf-8') as stream:
        for line in stream:
            i,start,latency,code,outcome,target,oid=json.loads(line);count+=1;outcomes[outcome]+=1;status[str(code)]+=1
            # Raw records have 0.001ms rounding; comparison tolerance covers 0.1ms histogram bin boundaries.
            hist[math.ceil(latency*10)]+=1
            if outcome=='success':shist[math.ceil(latency*10)]+=1
            if oid:orders.append(oid)
    def percentile(h,q):
        target=math.ceil(sum(h.values())*q);n=0
        for x,c in sorted(h.items()):
            n+=c
            if n>=target:return x/10
        return None
    checks={'request_count':count==result['requests'],'outcomes':dict(outcomes)==result['outcomes'],'http_status':dict(status)==result['http_status'],'successful_qps':abs(outcomes['success']/result['elapsed_seconds']-result['successful_qps'])<1e-8,'accepted_ids':collections.Counter(orders)==collections.Counter(x['id'] for x in result['accepted_orders']),'scheduled_accounting':result['scheduled']==count+result['dropped_iterations']}
    for key,q in [('p50',.5),('p95',.95),('p99',.99)]:
        checks[key]=count==0 or abs(percentile(hist,q)-result['latency_ms'][key])<=.101
        checks['success_'+key]=not shist or abs(percentile(shist,q)-result['successful_latency_ms'][key])<=.101
    return {'case':path.stem,'pass':all(checks.values()),'checks':checks,'raw_sha256':hashlib.sha256(path.with_name(path.name+'.raw.ndjson.gz').read_bytes()).hexdigest()}

gc_events=[]
gc_path=RUN/'backend.gc.log'
if gc_path.exists():
    for line in gc_path.read_text(encoding='utf-8',errors='replace').splitlines():
        stamp=re.match(r'^(\S+): \d+\.\d+: \[(Full GC|GC) ',line)
        durations=re.findall(r', ([\d.]+) secs\]',line)
        if stamp and durations:
            gc_events.append({'at_ms':datetime.datetime.strptime(stamp[1],'%Y-%m-%dT%H:%M:%S.%f%z').timestamp()*1000,'full':stamp[2]=='Full GC','pause_ms':float(durations[-1])*1000})
results=[];audits=[];metrics={}
for path in sorted(OUT.glob('*.json')):
    r=read(path)
    if not isinstance(r,dict) or 'arrival_model' not in r:continue
    name=path.stem;print('Audit '+name,flush=True);audits.append(audit(path,r))
    ep=OUT/(name+'-evidence.json');e=read(ep) if ep.exists() else None
    telemetry_path=OUT/(name+'-telemetry.ndjson')
    telemetry=[json.loads(line) for line in telemetry_path.read_text(encoding='utf-8').splitlines()] if telemetry_path.exists() else []
    ram=[x['available_memory_bytes']/1024**3 for x in telemetry if 'available_memory_bytes'in x];cpus=[x['system_cpu_percent'] for x in telemetry if x.get('system_cpu_percent') is not None]
    process_metrics={}
    for label in sorted({k for row in telemetry for k in row.get('processes',{})}):
        samples=[row['processes'][label] for row in telemetry if label in row.get('processes',{})]
        cpu=[s['cpu_core_percent'] for s in samples if s.get('cpu_core_percent') is not None]
        process_metrics[label]={'max_working_set_mib':max(s['working_set_bytes'] for s in samples)/1024**2,'median_cpu_core_percent':statistics.median(cpu) if cpu else None,'max_cpu_core_percent':max(cpu) if cpu else None}
    accepted_times=sorted(x['accepted_at_ms'] for x in r['accepted_orders']);backlog=[]
    for row in telemetry:
        if 'orders' not in row:continue
        at=row.get('orders_observed_at_ms',row['at_ms']);persisted=sum(x['persisted'] for x in row['orders'])
        backlog.append({'seconds':(at-r['started_at_ms'])/1000,'accepted':bisect.bisect_right(accepted_times,at),'persisted':persisted,'unpersisted_accepted_estimate':max(0,bisect.bisect_right(accepted_times,at)-persisted)})
    baseline=[x for x in backlog if 0<=x['seconds']<=r['elapsed_seconds']]
    pending=[x['unpersisted_accepted_estimate'] for x in baseline]
    observed_drain=max(0,e['batch_completion_seconds_upper_bound']-r['elapsed_seconds']) if e else None
    complete=bool(e and e['accepted_ids_match_persisted'])
    drain=observed_drain if complete else None
    perf={'nonempty_sample':r['requests']>0,'error_rate_at_most_0_1_percent':r['unexpected_error_rate']<=.001,'latency_p95_target':r['latency_ms'].get('p95',0)<=(500 if e else 100),'no_client_dropped_iterations':r['dropped_iterations']==0,'not_aborted':not r['aborted']}
    if e:
        perf['all_accepted_orders_reconciled']=e['pass'];perf['drain_within_60_seconds']=complete and drain<=60
        if r['kind']=='seckill':
            planned=r['configured_count'] or round(r['target_rate']*r['configured_seconds'])
            perf['all_planned_unique_orders_accepted']=r['outcomes'].get('success',0)==planned
        if r['arrival_model']=='open-loop':
            perf['last_minute_pending_not_growing']=len(baseline)>=12 and statistics.mean(pending[-6:])<=statistics.mean(pending[-12:-6])+max(50,r['target_rate'])
    metrics[name]={'requests':r['requests'],'qps':r['successful_qps'],'p95':r['latency_ms'].get('p95'),'p99':r['latency_ms'].get('p99'),'min_available_memory_gib':min(ram) if ram else None,'median_system_cpu':statistics.median(cpus) if cpus else None,'max_system_cpu':max(cpus) if cpus else None,'processes':process_metrics,'monitor_errors':[x['monitor_error'] for x in telemetry if 'monitor_error'in x],'max_estimated_pending':max(pending) if pending else 0,'drain_seconds_upper_bound':drain,'backlog':backlog,'gates':perf,'provisional_gates_pass':all(perf.values())}
    if e:metrics[name].update({'all_accepted_ids_persisted_at_observation_end':complete,'post_http_observation_seconds':observed_drain,'unpersisted_at_observation_end':len(e['missing_ids'])})
    events=[x for x in gc_events if r['started_at_ms']<=x['at_ms']<=r['started_at_ms']+r['elapsed_seconds']*1000]
    metrics[name]['jvm_gc']={'collections':len(events),'full_gc_count':sum(x['full'] for x in events),'max_pause_ms':max((x['pause_ms'] for x in events),default=0),'total_pause_ms':sum(x['pause_ms'] for x in events)}
    db_all=[row['mysql_status'] for row in telemetry if 'mysql_status'in row]
    db=[x for x in db_all if all(k in x for k in ('Innodb_row_lock_waits','Innodb_row_lock_time','Threads_running','Slow_queries'))]
    metrics[name]['incomplete_mysql_samples']=len(db_all)-len(db)
    redis=[row['redis_memory'] for row in telemetry if 'redis_memory'in row]
    persistence=[row['redis_persistence'] for row in telemetry if 'redis_persistence'in row]
    if db:metrics[name]['database']={'row_lock_waits_delta':int(db[-1]['Innodb_row_lock_waits'])-int(db[0]['Innodb_row_lock_waits']),'row_lock_wait_ms_delta':int(db[-1]['Innodb_row_lock_time'])-int(db[0]['Innodb_row_lock_time']),'max_threads_running':max(int(x['Threads_running']) for x in db),'slow_queries_delta':int(db[-1]['Slow_queries'])-int(db[0]['Slow_queries'])}
    if redis:metrics[name]['redis_peak_used_memory_mib']=max(int(x['used_memory']) for x in redis)/1024**2
    if persistence:metrics[name]['redis_delayed_fsync_delta']=int(persistence[-1].get('aof_delayed_fsync',0))-int(persistence[0].get('aof_delayed_fsync',0))
    bp,ap=OUT/(name+'-before.json'),OUT/(name+'-after.json')
    if bp.exists() and ap.exists():
        b,a=read(bp),read(ap)
        metrics[name]['cache_deltas']={'l1_hits':a['l1']['hitCount']-b['l1']['hitCount'],'l1_misses':a['l1']['missCount']-b['l1']['missCount'],'l1_evictions':a['l1']['evictionCount']-b['l1']['evictionCount'],'db_shop_rows_read':a['shop_rows_read']-b['shop_rows_read'],'redis_get_calls':int(a['redis_commandstats'].get('get',{}).get('calls',0))-int(b['redis_commandstats'].get('get',{}).get('calls',0))}
    results.append((name,r,e))

with (OUT/'audit.json').open('x',encoding='utf-8') as stream:json.dump({'all_pass':all(x['pass'] for x in audits),'cases':audits},stream,ensure_ascii=False,indent=2)
with (OUT/'analysis.json').open('x',encoding='utf-8') as stream:json.dump(metrics,stream,ensure_ascii=False,indent=2)
env=read(OUT/'environment.json')
lines=['# LocalPerks 单机上线前容量与稳定性预演（2026-10-27）','','本报告区分入口受理与实际落库，保留失败及预检数据。结果适用于本机当前配置，不能作为生产上线验收通过证明。','','## 环境与方法','','- Nginx 8080 → 单个 Spring Boot 8081 → Redis / RocketMQ / MySQL，压测端与服务同机；Windows Nginx、WSL Redis、回环网络，无 TLS/WAN。','- 全新隔离数据目录；20,014 个商户、50,000 个测试用户。登录态预置，真实请求经过鉴权、限流、Lua、消息与数据库。','- Redis AOF everysec、RocketMQ SYNC_FLUSH；MySQL 实际参数详见 environment.json。默认 30 分钟未支付自动关单保留。','- 阶梯测试采用闭环并发；持续测试采用固定到达速率开放模型，发压端丢弃迭代单独报告。所有客户端均无自动重试。','- 延迟由客户端测量，包含网络及排队；直方图向上取整到 0.1ms。请求原始记录保留 0.001ms 精度用于独立核验。','- CPU 单进程指标以 100%=一个逻辑核计；主机 CPU 以 100%=全部逻辑核计。MySQL 监听子进程与启动器分开，正式阶段使用 process-map.json 的真实服务 PID。',f"- JAR SHA-256：`{env['jar_sha256']}`。",'','## 完整 HTTP 结果','','| 场景 | 请求数 | 成功数 | 成功 QPS | P95 / P99 ms | 非预期错误 | 发压丢弃 |','|---|---:|---:|---:|---|---:|---:|']
for name,r,e in results:
    lines.append(f"| {name} | {r['requests']:,} | {r['outcomes'].get('success',0):,} | {r['successful_qps']:.1f} | {r['latency_ms'].get('p95',0):.1f} / {r['latency_ms'].get('p99',0):.1f} | {r['unexpected_errors']} | {r['dropped_iterations']} |")
lines+=['','库存不足、重复提交、限流和预期不存在商户不计入成功下单/有效读取 QPS。混合缓存的成功 QPS 只计算有效商户响应，总 QPS 请查看结果 JSON。','','## 订单最终结果与积压','','| 场景 | 受理 / 落库 | 最终一致性 | 批次完成吞吐 单/s | HTTP 停止后排空上界 s | 采样最大未落库受理数 |','|---|---:|---|---:|---:|---:|']
for name,r,e in results:
    if e:
        m=metrics[name]
        complete=m['all_accepted_ids_persisted_at_observation_end']
        throughput=f"{e['batch_completion_tps']:.1f}" if complete else f"未完成（观察期 {e['batch_completion_tps']:.1f}）"
        drain_text=f"{m['drain_seconds_upper_bound']:.1f}" if complete else f"观察 {m['post_http_observation_seconds']:.1f} 后仍未清空"
        lines.append(f"| {name} | {e['accepted_count']:,} / {e['persisted_count']:,} | {'通过' if e['pass'] else '未通过'} | {throughput} | {drain_text} | {m['max_estimated_pending']:,} |")
lines+=['','批次完成吞吐=落库总数/从首次请求到最终核对的总时间，不是单秒消费者峰值。未落库受理数由客户端受理时间和约 5 秒一次 SQL 观测估算，存在采样误差，不等同于 Broker 精确 lag。阶段结束 MQ 位点另见 *-mq.txt。','', '## 暂定工程门槛','','业务方尚未提供生产 SLA。预先设定的测试门槛见 PLAN.md：错误率≤0.1%，缓存 P95≤100ms，下单受理 P95≤500ms，持续测试无客户端丢弃，停压后 60 秒排空，订单与库存一致。','', '| 场景 | 暂定门槛 | 未达标项 | 主机 CPU 中位/最大 % | 最低可用内存 GiB |','|---|---|---|---|---:|']
for name,r,e in results:
    if name.startswith('pilot'):continue
    m=metrics[name];failed=', '.join(k for k,v in m['gates'].items() if not v) or '—'
    cpu_text=f"{m['median_system_cpu']:.1f} / {m['max_system_cpu']:.1f}" if m['median_system_cpu'] is not None else '见并行订单场景'
    ram_text=f"{m['min_available_memory_gib']:.2f}" if m['min_available_memory_gib'] is not None else '见并行订单场景'
    lines.append(f"| {name} | {'通过' if m['provisional_gates_pass'] else '未通过'} | {failed} | {cpu_text} | {ram_text} |")
lines+=['','## 数据解释与发现','']
bursts=[(n,r,e) for n,r,e in results if n.startswith('orders-20k')]
if bursts:
    values=[e['batch_completion_tps'] for _,_,e in bursts]
    lines.append(f"- 四档有效订单突发测试的批次完成吞吐范围为 {min(values):.1f}～{max(values):.1f} 单/秒；不同并发档位不能混算成同配置重复实验中位数。接口受理更快，但最终落库能力没有按入口峰值同比例增长。")
for n,r,e in results:
    if n.startswith('orders-soak'):
        m=metrics[n]
        lines.append(f"- `{n}`：计划 {r['configured_seconds']} 秒，实际 HTTP 测量 {r['elapsed_seconds']:.3f} 秒，{'主动中止，未完成持续测试' if r['aborted'] else '完整执行'}；目标下单 {r['target_rate']} 次/秒，实际受理 {r['outcomes'].get('success',0):,}，落库 {e['persisted_count']:,}，受理 P95/P99 {r['latency_ms']['p95']}/{r['latency_ms']['p99']} ms，未执行计划迭代 {r['dropped_iterations']}，采样最大未落库受理数约 {m['max_estimated_pending']}。并行读取见对应 cache-mixed-soak 场景。")
        if r.get('arrival_schedule_lag_ms'):lines.append(f"- 订单发压调度延迟 P95/P99：{r['arrival_schedule_lag_ms'].get('p95')}/{r['arrival_schedule_lag_ms'].get('p99')} ms。开放模型没有在服务变慢时自动降低目标到达率；因活跃请求上限而未发出的请求另计 dropped_iterations。")
if 'cache-proxy-c400' in metrics:
    lines.append('- 原始 Nginx 配置下 400 并发热点读出现连接失败/超时；混合读另出现上游 socket 10055。错误日志同时显示 50x 错误页缺失，使该次上游错误最终呈现为非 JSON 的 HTTP 404。原始证据保留，不将其当作业务不存在。')
if 'cache-keepalive-c100' in metrics:
    b,c=metrics['cache-proxy-c100'],metrics['cache-keepalive-c100']
    lines.append(f"- 隔离 Nginx 初版连接复用（keepalive 128）单次诊断对照：100 并发热点成功 QPS {b['qps']:.1f} → {c['qps']:.1f}，P95 {b['p95']} → {c['p95']} ms。但该版本 400 并发随后失败，不能只引用其 100 并发结果宣称入口问题已解决。两次非交替、背景负载不完全相同，不能宣称严格统计意义的固定提升倍数。")
if 'cache-keepalive-v2-c400' in metrics:
    c=metrics['cache-keepalive-v2-c400']
    lines.append(f"- 第二版隔离入口设置 keepalive 512、keepalive_requests 10000、keepalive_timeout 60s，并清空上游 Connection 头；400 并发热点读成功 QPS {c['qps']:.1f}，P95/P99 {c['p95']}/{c['p99']} ms。结果来自运行时配置对照，尚未合入业务项目的部署配置。具体错误率和持续场景结果见上表。")
lines.append('- 原始持续场景被人工中止，表中发压丢弃包含中止后取消的剩余计划迭代；中止前进度日志显示 dropped=0。该数量不能解读为服务端错误数或发压端饱和丢弃数。原始配置约 320 秒的失败数据没有与完成的 900 秒对照混合统计。')
lines.append('- 如果停压后约 300 秒观察期结束仍有未落库受理订单，结论是“在观察窗口内未收敛”，不能直接称为已丢单。此时 evidence 中历史命名的 batch_completion_seconds_upper_bound / batch_completion_tps 只是观察结束时间与观察期吞吐，不代表批次已完成；本报告已据 ID 对账结果区分。')
lines.append('- 多个高吞吐缓存用例中，单个 Node 发压进程 CPU 时间接近测量时长，即约占满一个逻辑核；因此测得的是本次单机端到端吞吐，不能据此断言服务器极限。未实施压测端分机或多进程扩容。')
if (OUT/'soak-live-lock-diagnostics-v2.tsv').exists():
    lines.append('- 22:01:53 的活动事务快照确认 tb_seckill_voucher 主键记录锁等待：910300 新订单扣库存与 910010 旧订单关单回补分别发生同券行竞争，同时占用业务数据库连接。证据见 soak-live-lock-diagnostics-v2.tsv。不能把有自动关单背景流量的容量误写成纯下单容量。')
for status_path in [OUT/'post-soak-order-status-probes.json',OUT/'post-soak-order-status-probes-fresh.json']:
    if not status_path.exists():continue
    probes=read(status_path)
    lines.append(f"- 订单状态抽查 `{status_path.name}`：{len(probes['samples'])} 个样本中，{probes['acknowledged_but_not_found_samples']} 个曾受理订单在前后两次 SQL 均未落库时返回 NOT_FOUND。定向抽样，不能外推发生比例；首批按旧快照取样时消费者已补齐相应订单，第二批使用新 SQL 快照挑选受理超过 620 秒且仍未落库的订单，分别保留证据。源码排队状态 TTL 为 10 分钟，订单未落库且状态过期后会走 NOT_FOUND 分支；需要补齐持久状态或积压保护。")
lines.append('- 未排空场景的库存、订单数和 Redis 值是先后采样，消费者仍在修改数据；此时部分库存等式不通过不能单独认定为超卖或永久不一致。已排空的竞争和突发场景有独立的完整 ID/库存核对结果。')
restoration_path=OUT/'restoration.json'
if restoration_path.exists():
    restoration=read(restoration_path)
    lines.append(f"- 测试结束入口配置恢复核验：{'原配置 SHA-256 一致' if restoration['nginx_config_matches_original'] else '不一致，需处理'}；详情见 restoration.json。隔离数据与服务保留以便复查。")
lines+=['','## 关键边界与上线缺口','','- [Windows Nginx 官方文档](https://nginx.org/en/docs/windows.html)说明其连接处理和扩展性限制，因此入口瓶颈需要在实际 Linux 部署上复测。连接复用参数含义见 [upstream 官方文档](https://nginx.org/en/docs/http/ngx_http_upstream_module.html)。','- 当前只有单实例、单副本；未验证故障切换、Broker/Redis/MySQL 崩溃、断电持久性或外部支付。','- 15 分钟持续场景只构成本轮稳定性预演；不能替代真实生产资源配额下的数小时长稳、多机压测与真实流量回放。','- 20,000 个合成商户字段较均匀，布隆过滤器仍按源码期望 10,000 条初始化；需根据生产基数评估误判率。','- 尚未验证混合支付、取消、退款、图片上传和智能助手模型调用的全业务负载。本次不能给这些业务套用查询/秒杀 QPS。','- 不应将短时受理峰值当作可持续落库容量；若消费者持续积压，增加入口并发并不能解决端到端吞吐问题。','- 原始配置的全局入口保护、消费积压告警、数据库热点库存行竞争、上游连接复用及错误响应需在实际部署前逐项验证与整改。','', '## 复现与证据','','- 启动：tests/benchmark-start.ps1 -RunTag 20261027 -Durable；只允许新的独立数据目录/数据库，已有端口或已存在数据时停止。','- 初始化：benchmark-fixtures.py init；prelaunch-suite.py prepare；启动后端和 Nginx 时均指定 RunTag。','- 执行：prelaunch-suite.py capture / pilot / cache / orders / soak / control / control_tuned；重新执行请更换日期、输出目录和测试券 ID，禁止覆盖旧证据。','- 每个用例保留 JSON 汇总、原始 *.raw.ndjson.gz、客户端日志、遥测、前后快照；订单另有逐 ID/归属/库存一致性 evidence。','- 原始记录独立核验：tests/prelaunch-report.py；audit.json 提供每个原始 gzip 的 SHA-256 与计数、延迟、QPS、订单 ID 对照。',f"- 本次原始数据审计：{len(audits)} 个场景，{'全部通过' if all(x['pass'] for x in audits) else '存在未通过项'}。",'','简历未自动修改。业务系统可能在 30 分钟后关闭未支付订单，核对结论以用例结束时保存的快照为准。']
index={name:(r,e) for name,r,e in results}
overview=['## 本轮结论','',f"共执行 {len(results)} 个有完整 HTTP 汇总的场景，累计 {sum(r['requests'] for _,r,_ in results):,} 次实际请求（含预检、失败与不同配置的诊断对照，不能混算统一 QPS）。",'']
if 'orders-soak-keepalive-v2-150rps-900s' in index:
    name='orders-soak-keepalive-v2-150rps-900s';r,e=index[name];m=metrics[name]
    overview.append(f"- 第二版入口的 15 分钟混合测试：受理 {e['accepted_count']:,} 单，核对窗口结束落库 {e['persisted_count']:,} 单，未落库 {len(e['missing_ids']):,} 单；暂定门槛{'通过' if m['provisional_gates_pass'] else '未通过'}。详见持续场景及排空记录。")
overview+=['- 原入口出现连接资源耗尽；初版连接复用仍有失败。第二版入口改善缓存读吞吐，但不能代替对持续落库能力的验证。','- 本轮未形成生产上线通过结论。需要先处理入口连接与错误响应、消费积压与限流、热点库存行竞争及长排队状态，再在实际 Linux 生产资源上复测。','- 简历和业务源码未修改；所有失败数据保留。','']
lines[4:4]=overview
with (OUT/'REPORT.md').open('x',encoding='utf-8') as stream:stream.write('\n'.join(lines)+'\n')
print(json.dumps({'cases':len(audits),'audit_pass':all(x['pass'] for x in audits),'report':str(OUT/'REPORT.md')},ensure_ascii=False),flush=True)
