"""Audit matched benchmark raw records and report the measured before/after difference."""
import bisect, collections, gzip, hashlib, json, math, pathlib, statistics

ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE=ROOT/'output/benchmarks/localperks-optimization-2026-10-28'
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def pct(h,q):
    target=math.ceil(sum(h.values())*q);n=0
    for b,c in sorted(h.items()):
        n+=c
        if n>=target:return b/10
    return 0

def audit(p,r):
    outcomes=collections.Counter();status=collections.Counter();lat=collections.Counter();ids=collections.Counter();n=0
    raw=pathlib.Path(str(p)+'.raw.ndjson.gz')
    with gzip.open(raw,'rt',encoding='utf-8') as f:
        for line in f:
            _,_,ms,code,outcome,_,oid=json.loads(line)
            n+=1;outcomes[outcome]+=1;status[str(code)]+=1;lat[math.ceil(ms*10)]+=1
            if oid:ids[oid]+=1
    checks={'count':n==r['requests'],'outcomes':dict(outcomes)==r['outcomes'],'status':dict(status)==r['http_status'],
            'ids':ids==collections.Counter(x['id'] for x in r['accepted_orders']),
            'scheduled':n+r['dropped_iterations']==r['scheduled'],
            'qps':abs(outcomes['success']/r['elapsed_seconds']-r['successful_qps'])<1e-7}
    for k,q in [('p50',.5),('p95',.95),('p99',.99)]:checks[k]=abs(pct(lat,q)-r['latency_ms'][k])<=.101
    h=hashlib.sha256()
    with raw.open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
    return {'case':str(p.relative_to(BASE)),'pass':all(checks.values()),'checks':checks,'raw_sha256':h.hexdigest()}

def analyze(folder,name,r):
    ep=folder/(name+'-evidence.json');e=read(ep) if ep.exists() else None
    tp=folder/(name+'-telemetry.ndjson');rows=[json.loads(x) for x in tp.read_text(encoding='utf-8').splitlines()] if tp.exists() else []
    times=sorted(x['accepted_at_ms'] for x in r['accepted_orders']);pending=[]
    for row in rows:
        at=row.get('orders_observed_at_ms',row['at_ms'])
        if 'orders' in row and at<=r['started_at_ms']+r['elapsed_seconds']*1000:
            pending.append(max(0,bisect.bisect_right(times,at)-sum(x['persisted'] for x in row['orders'])))
    complete=bool(e and e['accepted_ids_match_persisted'])
    drain=max(0,e['batch_completion_seconds_upper_bound']-r['elapsed_seconds']) if complete else None
    gates={'error_rate':r['unexpected_error_rate']<=.001,'p95':r['latency_ms']['p95']<=(500 if e else 100),'no_drops':r['dropped_iterations']==0,'not_aborted':not r['aborted']}
    if e:
        gates.update({'consistency':e['pass'],'drain_60s':complete and drain<=60})
        if r['kind']=='seckill':gates['all_planned_accepted']=r['outcomes'].get('success',0)==(r['configured_count'] or r['target_rate']*r['configured_seconds'])
        if r['arrival_model']=='open-loop':gates['pending_not_growing']=len(pending)>=12 and statistics.mean(pending[-6:])<=statistics.mean(pending[-12:-6])+r['target_rate']
    valid_db=[x['mysql_status'] for x in rows if all(k in x.get('mysql_status',{}) for k in ['Innodb_row_lock_waits','Innodb_row_lock_time','Threads_running'])]
    db_delta={}
    if valid_db:db_delta={'row_lock_waits':int(valid_db[-1]['Innodb_row_lock_waits'])-int(valid_db[0]['Innodb_row_lock_waits']),'row_lock_wait_ms':int(valid_db[-1]['Innodb_row_lock_time'])-int(valid_db[0]['Innodb_row_lock_time']),'max_threads_running':max(int(x['Threads_running']) for x in valid_db)}
    cpu=[x['system_cpu_percent'] for x in rows if x.get('system_cpu_percent') is not None]
    return {'qps':r['successful_qps'],'total_qps':r['total_qps'],'requests':r['requests'],'success':r['outcomes'].get('success',0),'p95':r['latency_ms']['p95'],'p99':r['latency_ms']['p99'],'errors':r['unexpected_errors'],'drops':r['dropped_iterations'],'accepted':e['accepted_count'] if e else None,'persisted':e['persisted_count'] if e else None,'consistency':e['pass'] if e else None,'drain_seconds':drain,'completion_tps':e['batch_completion_tps'] if complete else None,'max_sampled_pending':max(pending,default=0),'gates':gates,'pass':all(gates.values()),'mysql':db_delta,'cpu_median':statistics.median(cpu) if cpu else None,'monitor_errors':[x.get('monitor_error') for x in rows if x.get('monitor_error')]}

STAGES=('before','afterbatchretry','beforesteadyretry','afterbatchsteady','beforeburst','afterbatchburst')
audits=[];data={};result_counts=0
for stage in STAGES:
    folder=BASE/stage
    expected='orders-soak-burst-150rps-300s-evidence.json' if stage.endswith('burst') else 'orders-soak-matched-150rps-900s-evidence.json' if 'steady' in stage else 'orders-abuse-rate-evidence.json'
    if not (folder/expected).exists():raise RuntimeError(stage+' has not completed')
    data[stage]={}
    for p in sorted(folder.glob('*.json')):
        r=read(p)
        if not isinstance(r,dict) or 'arrival_model' not in r:continue
        # Retain invalid replay data, but never mix it into the paired steady workload.
        if stage=='before' and '-soak-' in p.stem:continue
        print('Audit '+stage+'/'+p.stem,flush=True)
        audits.append(audit(p,r));data[stage][p.stem]=analyze(folder,p.stem,r);result_counts+=r['requests']

background={}
for stage in ('beforesteadyretry','afterbatchsteady','beforeburst','afterbatchburst'):
    samples=[json.loads(x) for x in (BASE/stage/'background-close.raw.ndjson').read_text(encoding='utf-8').splitlines()]
    lags=sorted(x['lag_ms'] for x in samples);times=[x['sent_at_ms'] for x in samples]
    final=read(BASE/stage/'background-final.json')
    closed=sum(int(line.split('\t')[2]) for line in final['orders'].splitlines() if line.split('\t')[1]=='4')
    stock_checks={}
    for vid,snap in final['snapshots'].items():
        v=snap['voucher']
        stock_checks[vid]={'db_stock':v['dbStock'],'redis_stock':int(v['redisStock']),'fully_restored':v['dbStock']==27000 and int(v['redisStock'])==27000}
    background[stage]={'sent':len(samples),'all_indices_once':sorted(x['index'] for x in samples)==list(range(54000)),'elapsed_seconds':(max(times)-min(times))/1000,'lag_p95_ms':lags[math.ceil(len(lags)*.95)-1],'lag_max_ms':max(lags),'closed':closed,'stock':stock_checks,'final':final}
    if not background[stage]['all_indices_once']:raise RuntimeError('Invalid background replay: '+stage)

env={s:read(BASE/s/'environment.json') for s in STAGES}
comparability={}
for a,b in [('before','afterbatchretry'),('beforesteadyretry','afterbatchsteady'),('beforeburst','afterbatchburst')]:
    def sqlsettings(e):return dict(line.split('\t',1) for line in e['mysql_settings'].splitlines() if not line.startswith('datadir\t'))
    checks={'http_generator':env[a]['generator_sha256']==env[b]['generator_sha256'],'mysql_settings':sqlsettings(env[a])==sqlsettings(env[b]),'shops':env[a]['shop_count']==env[b]['shop_count'],'users':env[a]['users']==env[b]['users']}
    comparability[a+'/'+b]=checks
    if not all(checks.values()):raise RuntimeError('Unmatched environments '+a+'/'+b)
for name,value in [('audit.json',{'pass':all(x['pass'] for x in audits),'cases':audits,'comparability':comparability}),('analysis.json',{'cases':data,'background':background})]:
    with (BASE/name).open('x',encoding='utf-8') as f:json.dump(value,f,ensure_ascii=False,indent=2)

def fmt(x):return '未在观察期完成' if x is None else (f'{x:,}' if isinstance(x,int) else f'{x:,.1f}')
def row(label,name,key,pair=('before','afterbatchretry')):
    return f"| {label} | {fmt(data[pair[0]][name][key])} | {fmt(data[pair[1]][name][key])} |"
steady=('beforesteadyretry','afterbatchsteady');burst=('beforeburst','afterbatchburst')
soak='orders-soak-matched-150rps-900s';cache='cache-mixed-soak-matched-900rps-900s'
bo='orders-soak-burst-150rps-300s';bc='cache-mixed-soak-burst-900rps-300s'
lines=['# LocalPerks 修复前后压测对照（2026-10-28）','',
f'本轮有效配对数据共核验 {len(audits)} 个场景（含预热）、{result_counts:,} 次真实 HTTP 请求。所有历史、失败及无效回放结果保留；简历未修改。','',
'## 短时容量对比','','| 指标 | 旧版 | 修复版 |','|---|---:|---:|',
row('400 并发热点读成功 QPS','cache-proxy-c400','qps'),row('400 并发热点读 P95（ms）','cache-proxy-c400','p95'),row('400 并发热点读非预期错误数','cache-proxy-c400','errors'),
row('200 并发两万单批次完成吞吐（单/s）','orders-20k-c200','completion_tps'),row('200 并发两万单停压后排空（s）','orders-20k-c200','drain_seconds'),'',
'## 15 分钟持续负载','','150 次/秒下单 + 900 次/秒混合查询 + 60 条/秒到期关单消息。','','| 指标 | 旧版 | 修复版 |','|---|---:|---:|']
for label,key in [('实际受理订单','accepted'),('观察结束已落库订单','persisted'),('下单受理 P95（ms）','p95'),('下单非预期错误','errors'),('下单未发出的计划请求','drops'),('最大采样未落库受理数','max_sampled_pending'),('停压后排空（s）','drain_seconds')]:
    lines.append(row(label,soak,key,steady))
for label,key in [('并行查询 P95（ms）','p95'),('并行查询非预期错误','errors'),('并行查询未发出的计划请求','drops')]:lines.append(row(label,cache,key,steady))
lines+=['','## 5 分钟关单突发负载','','54,000 笔到期关单通过 8 个线程集中投递，同时 150 次/秒下单 + 900 次/秒查询。','',
'| 指标 | 旧版 | 修复版 |','|---|---:|---:|']
for label,key in [('实际受理订单','accepted'),('观察结束已落库订单','persisted'),('下单受理 P95（ms）','p95'),('下单非预期错误','errors'),('下单未发出的计划请求','drops'),('最大采样未落库受理数','max_sampled_pending'),('停压后排空（s）','drain_seconds')]:lines.append(row(label,bo,key,burst))
for label,key in [('并行查询 P95（ms）','p95'),('并行查询非预期错误','errors'),('并行查询未发出的计划请求','drops')]:lines.append(row(label,bc,key,burst))
lines+=['','## 预设门槛判定','']
for pair,order,reads,label in [(steady,soak,cache,'持续'),(burst,bo,bc,'突发')]:
    for stage,version in zip(pair,('旧版','修复版')):
        m=data[stage][order];n=data[stage][reads]
        failed=[k for k,v in m['gates'].items() if not v]+['read_'+k for k,v in n['gates'].items() if not v]
        if background[stage]['closed']!=54000:failed.append('background_not_drained')
        if not all(x['fully_restored'] for x in background[stage]['stock'].values()):failed.append('background_stock_not_restored')
        lines.append(f"- {version}{label}场景：{'通过本轮预设门槛' if not failed else '未达标：'+', '.join(failed)}。")
lines+=['',
'受理 QPS 不等于完成订单 TPS。完成吞吐以批次发起至 SQL 确认全部落库的总时长计算；排空时间包含 SQL 轮询观测误差。最大积压由受理时间与约五秒一次 SQL 观测估算。未在停压后 300 秒观察期完成的批次不计算完成 TPS，也不直接认定为丢单。',
'混合查询中预设 10% 不存在 ID：not_found 是预期业务结果，不计入非预期错误；成功 QPS 只统计 success，不能把约 90% 的业务成功率误称为服务可用率。持续负载门槛包含未发出请求为零，不能通过降低实际发压量提高成功率。','',
'## 比较条件与边界','',
'- 两版使用全新独立数据目录；同一主机、端口、Java 堆、数据规模、持久化参数、鉴权限流和 HTTP 发压器。environment.json 记录 JAR/生成器 SHA-256 与实测配置。',
'- 缓存阶梯比较原 Nginx 与修复后的 Nginx/Tomcat。交易阶段旧版启用之前的 keepalive-v2 入口；修复版还提高了 Tomcat 每连接请求上限，所以交易对比体现完整修复的联合效果，不能单独归因于 SQL 优化。',
'- 平稳及突发场景分别从全新数据开始，不继承前面短测产生的延迟关单。预置 54,000 笔到期订单，实际关单消费者和定时兜底保持开启；这属于固定背景负载。',
'- 首次 before 持续测试的单线程关单发送器未达到目标速率，已标记无效并排除配对；本报告使用 beforesteadyretry / afterbatchsteady 的同一有界并行发送器。原始失败数据保留，详见 PLAN.md 和 before/background-invalid.json。',
'- 昨天的背景关单由之前测试的自然时间决定，与本轮固定回放不同；效果以前后配对数据为准，不能直接套用昨天的提升倍数。',
'- beforesteady 完成 HTTP 负载后，在落库观察阶段随会话中断停止，未完成最终核对；保留原始记录，使用全新 beforesteadyretry 完成配对基线。',
'- 实测主机 i5-14600KF（14 核 / 20 线程）、约 32 GB 内存；应用堆 512 MiB～1 GiB、MySQL 缓冲池 512 MiB。详细规格见 hardware.json。',
'- 同机 Windows/WSL，未验证 Linux 生产部署、多副本容灾、数小时长稳；单轮对照不是统计显著性结论，也不是生产上线认证。','',
'## 修复与回归','',
'- 连接：Nginx 使用命名 upstream、清空 Connection、复用连接；Tomcat 通过 customizer 将每连接请求上限从默认 100 提高至 10,000、空闲超时 65 秒。上游不可用返回 JSON 503。',
'- 下单：先插订单，唯一索引处理并发幂等；最后执行条件扣库存并提交，移除库存锁期间的 Redis 用户锁操作和先查 count。扣库存失败回滚整个事务。',
'- 消费聚合：最多 32 条、20 ms 调度间隔，512 条有界等待队列；同批订单合并条件扣库存并提交，MQ 回调等待提交成功才继续清理状态和确认。整批失败先回滚，再逐条隔离；超时/异常交回 MQ 重试。持久化参数不降低。',
'- 中间版本 after / aftersteady 保留：虽然短测改善，但持续场景排空仍约 155.5 秒，因此追加消费聚合，并以新 JAR 在 afterbatch 系列目录重新完成全部对照。',
'- 关单：Redis 库存回补移到数据库 afterCommit；回滚时不释放 Redis 库存，减少持有热点行锁期间的远程调用。',
'- 状态：Lua 预扣成功时原子 PERSIST 排队标记，消费终态清理；查询遇到 SQL/Redis 交接窗口时再次查库并继续校验归属。',
'- 26 项回归全部通过、无跳过：并发唯一约束、库存不足回滚、提交后回补、Lua TTL、归属校验、查询竞态、连接参数等。日志见 regression-batch.log 与 test-reports-batch；第一次连接测试使用未绑定端口的临时值而失败，已修正并保留日志。',
'- 黑盒连接验证见 before/keepalive-probe.json 与 afterbatchretry/keepalive-probe.json；故障上游的两条路由均返回 JSON 503，见 nginx-failure-regression.json。',
'- Redis 提交后回补失败仍需现有对账流程介入；永久排队记录需配合 MQ 重试/死信排障。此次未声称实现跨 Redis/MySQL 的原子提交。','',
'## 全部有效场景','','| 场景 | 版本 | 请求数 | 成功 QPS | P95 / P99 ms | 错误 / 未发出 | 正确性 | 门槛 |','|---|---|---:|---:|---|---|---|---|']
for pair in [('before','afterbatchretry'),steady,burst]:
    for name in data[pair[0]]:
        for stage,version in zip(pair,('旧版','修复版')):
            m=data[stage][name]
            lines.append(f"| {name} | {version} | {m['requests']:,} | {m['qps']:.1f} | {m['p95']} / {m['p99']} | {m['errors']} / {m['drops']} | {m['consistency'] if m['consistency'] is not None else '—'} | {'通过' if m['pass'] else '未通过'} |")
lines+=['','## 背景流量与证据','']
for stage,b in background.items():
    lines.append(f"- {stage}：发送 {b['sent']:,} 条，跨度 {b['elapsed_seconds']:.3f}s，调度延迟 P95 {b['lag_p95_ms']:.1f}ms、最大 {b['lag_max_ms']:.1f}ms；索引完整且不重复 {b['all_indices_once']}；结束时关闭 {b['closed']:,} 笔，库存完全回补 {all(x['fully_restored'] for x in b['stock'].values())}。")
lines+=['',f"原始数据审计：{'全部通过' if all(x['pass'] for x in audits) else '存在失败'}。这表示请求计数、状态、延迟、QPS 和订单 ID 与原始记录一致，不表示性能门槛通过。",'',
'原始请求：各阶段 *.raw.ndjson.gz；订单核对：*-evidence.json；遥测：*-telemetry.ndjson；MQ 位点：*-mq.txt；计算详情：analysis.json；审计：audit.json；配置及源代码快照：archive/。']
with (BASE/'REPORT.md').open('x',encoding='utf-8') as f:f.write('\n'.join(lines)+'\n')
print(json.dumps({'audited_cases':len(audits),'requests':result_counts,'raw_audit_pass':all(x['pass'] for x in audits),'report':str(BASE/'REPORT.md')},ensure_ascii=False),flush=True)
