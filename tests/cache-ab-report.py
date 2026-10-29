"""Audit original HTTP records and summarize the cache-switch-only A/B test."""
import collections, gzip, hashlib, json, math, pathlib, statistics

ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'output/benchmarks/localperks-cache-ab-2026-10-28'
SLOTS=[('one','redis'),('two','l1'),('three','l1'),('four','redis'),('five','redis'),('six','l1')]

def read(name):return json.loads((OUT/name).read_text(encoding='utf-8-sig'))
def median(values):return statistics.median(values)
def delta(before,after,key):return int(after.get(key,{}).get('calls',0))-int(before.get(key,{}).get('calls',0))
def percentile(hist,p):
    target=math.ceil(sum(hist.values())*p);n=0
    for k,v in sorted(hist.items()):
        n+=v
        if n>=target:return k/10

def summarize(slot,variant,kind):
    name=f'{slot}-{variant}-{kind}';r=read(name+'.json');b=read(name+'-before.json');a=read(name+'-after.json')
    warm=read(name+'-warmup.json')
    assert warm['requests']==50000 and warm['configured_count']==50000
    assert warm['unexpected_errors']==0 and warm['dropped_iterations']==0 and not warm['aborted']
    assert set(warm['outcomes'])==({'success'} if kind=='hot' else {'success','not_found'})
    assert r['unexpected_errors']==0 and r['dropped_iterations']==0 and not r['aborted']
    expected={'success'} if kind=='hot' else {'success','not_found'}
    assert set(r['outcomes'])<=expected and set(r['http_status'])=={'200'}
    bc,ac=b['redis_commandstats'],a['redis_commandstats']
    commands={key:delta(bc,ac,key) for key in set(bc)|set(ac)}
    hits=a['l1']['hitCount']-b['l1']['hitCount'];misses=a['l1']['missCount']-b['l1']['missCount']
    db=a['shop_rows_read']-b['shop_rows_read']
    assert db==0,(name,'Unexpected DB reads',db)
    if variant=='l1':assert hits+misses==r['requests'],(name,hits,misses,r['requests'])
    else:assert hits==misses==0
    rows=[json.loads(line) for line in (OUT/(name+'-telemetry.ndjson')).read_text(encoding='utf-8').splitlines()]
    assert not any('monitor_error' in row for row in rows)
    raw=OUT/(name+'.json.raw.ndjson.gz');counts=collections.Counter();statuses=collections.Counter();hist=collections.Counter();n=0
    with gzip.open(raw,'rt',encoding='utf-8') as stream:
        indices=set()
        for line in stream:
            index,started,latency,status,outcome,target,order_id=json.loads(line)
            assert index not in indices;indices.add(index)
            assert order_id is None and started>=0 and latency>=0
            if kind=='hot':assert target==1
            else:
                sequence=((index+1)*2654435761)&0xffffffff
                missing=index%10==9
                expected_target=90000000+sequence%10000 if missing else 10000+sequence%(100 if index%10<7 else 20000)
                assert target==expected_target
                assert outcome==('not_found' if missing else 'success')
            counts[outcome]+=1;statuses[str(status)]+=1;hist[math.ceil(latency*10)]+=1;n+=1
    assert n==r['requests'] and dict(counts)==r['outcomes'] and dict(statuses)==r['http_status']
    assert min(indices)==0 and max(indices)==n-1
    assert abs(percentile(hist,.95)-r['latency_ms']['p95'])<=.11
    cpu_before=read(name+'-redis-cpu-before.json');cpu_after=read(name+'-redis-cpu-after.json')
    redis_cpu=sum(float(cpu_after[k])-float(cpu_before[k]) for k in ('used_cpu_sys','used_cpu_user'))
    def values(field):return [row[field] for row in rows if row.get(field) is not None]
    backend=[row['processes']['backend']['cpu_core_percent'] for row in rows if row.get('processes',{}).get('backend',{}).get('cpu_core_percent') is not None]
    return {'name':name,'slot':slot,'variant':variant,'kind':kind,'requests':n,'outcomes':r['outcomes'],'total_qps':r['total_qps'],'successful_qps':r['successful_qps'],'latency_ms':r['latency_ms'],'errors':r['unexpected_errors'],'get_delta':commands.get('get',0),'get_per_http_request':commands.get('get',0)/n,'redis_commands_delta':commands,'l1_hits':hits,'l1_misses':misses,'l1_hit_rate_all_requests':hits/(hits+misses) if hits+misses else None,'l1_hit_rate_existing_requests':hits/r['outcomes'].get('success',1) if variant=='l1' else None,'l1_evictions':a['l1']['evictionCount']-b['l1']['evictionCount'],'shop_rows_read_delta':db,'client_cpu_seconds':r['client_cpu_seconds'],'client_event_loop_p99_ms':r['client_event_loop_p99_ms'],'redis_cpu_seconds_window':redis_cpu,'system_cpu_percent_median':median(values('system_cpu_percent')),'backend_cpu_core_percent_median':median(backend),'min_available_memory_bytes':min(values('available_memory_bytes')),'min_disk_free_bytes':min(values('disk_free_bytes')),'raw_sha256':hashlib.sha256(raw.read_bytes()).hexdigest(),'raw_requests_recounted':n,'raw_pass':True}

def main():
    complete=read('complete.json');environment=read('environment.json')
    assert complete['restored_local_cache']
    assert complete['order_status_counts']==environment['order_status_counts']
    assert environment['generator_sha256']==hashlib.sha256((ROOT/'tests/prelaunch-http.mjs').read_bytes()).hexdigest()
    assert environment['jar_sha256']==hashlib.sha256((ROOT/'output/benchmarks/localperks-optimization-2026-10-28/after-batch.jar').read_bytes()).hexdigest()
    assert environment['nginx_config']==(ROOT/'tmp/localperks-benchmark-20261028-afterbatchburst/nginx/nginx.conf').read_text(encoding='utf-8-sig')
    results=[summarize(slot,variant,kind) for slot,variant in SLOTS for kind in ('hot','mixed-cache')]
    # Compare launch arguments after removing output-file names and the single intended toggle.
    common=None
    for slot,variant in SLOTS:
        for kind in ('hot','mixed-cache'):
            args=read(slot+'-'+kind+'-app-args.json')
            assert f'--app.cache.shop-local-enabled={"true" if variant=="l1" else "false"}' in args
            normalized=[x for x in args if not x.startswith(('-Xloggc:','--app.cache.shop-local-enabled='))]
            if common is None:common=normalized
            else:assert common==normalized
    aggregates={}
    for kind in ('hot','mixed-cache'):
        aggregates[kind]={}
        for variant in ('redis','l1'):
            group=[r for r in results if r['kind']==kind and r['variant']==variant]
            q=[r['total_qps'] for r in group]
            aggregates[kind][variant]={'qps_median':median(q),'qps_min':min(q),'qps_max':max(q),'success_qps_median':median([r['successful_qps'] for r in group]),'p50_median_ms':median([r['latency_ms']['p50'] for r in group]),'p95_median_ms':median([r['latency_ms']['p95'] for r in group]),'p99_median_ms':median([r['latency_ms']['p99'] for r in group]),'get_per_request_median':median([r['get_per_http_request'] for r in group]),'l1_hit_rate_median':median([r['l1_hit_rate_all_requests'] for r in group]) if variant=='l1' else None}
        a,b=aggregates[kind]['redis'],aggregates[kind]['l1']
        aggregates[kind]['qps_ratio']=b['qps_median']/a['qps_median']
        aggregates[kind]['p95_reduction']=1-b['p95_median_ms']/a['p95_median_ms']
        aggregates[kind]['get_per_request_reduction']=1-b['get_per_request_median']/a['get_per_request_median']
    output={'scenarios':results,'aggregates':aggregates,'formal_requests':sum(r['requests'] for r in results),'raw_audit_pass':True,'all_warmups_50000_requests_without_errors':True,'application_arguments_same_except_cache_toggle_and_gc_log':True,'boundaries':['single host, Windows Nginx, WSL Redis; no production capacity claim','L1 fast path bypasses Redis Bloom lookup, Redis GET and JSON parsing','same request count warmup; short 60-second windows; hot-cache read-only comparison','GET is instance-wide and includes small reconciliation background; does not equal all Redis network roundtrips','mixed total QPS includes expected not_found; zero unexpected errors; successful QPS reported separately']}
    with (OUT/'results.json').open('x',encoding='utf-8') as stream:json.dump(output,stream,ensure_ascii=False,indent=2)
    lines=['# LocalPerks：仅 Redis 与 Caffeine + Redis 对照压测','','正式样本为 3 轮 × 2 种配置 × 2 个场景。每场景每种配置独立重启、等布隆过滤器加载完成、预热 50,000 次请求，再测量 60 秒。以下为三轮中位数；未修改简历或业务代码。','','## 聚合结果','','| 场景 | 配置 | 完成 QPS 中位数（范围） | P50 / P95 / P99（ms） | Redis GET / HTTP请求 | L1 命中率 |','|---|---|---:|---:|---:|---:|']
    for kind in ('hot','mixed-cache'):
        for variant in ('redis','l1'):
            x=aggregates[kind][variant]
            hit=f"{x['l1_hit_rate_median']:.2%}" if variant=='l1' else '未启用'
            lines.append(f"| {'热点单商户，400并发' if kind=='hot' else '混合商户，200并发'} | {'仅Redis' if variant=='redis' else 'Caffeine+Redis'} | {x['qps_median']:,.0f}（{x['qps_min']:,.0f}–{x['qps_max']:,.0f}） | {x['p50_median_ms']:.1f} / {x['p95_median_ms']:.1f} / {x['p99_median_ms']:.1f} | {x['get_per_request_median']:.6f} | {hit} |")
    lines+=['','混合流量中 70% 访问热点池（100 家）、20% 访问长尾池（20,000 家）、10% 查询不存在 ID；QPS 包括正确返回的“不存在”，成功返回店铺数据的 QPS 单独保存在 results.json。闭环运行请求数不同，但使用同一确定性请求序列与分布。','']
    for kind in ('hot','mixed-cache'):
        x=aggregates[kind]
        group=[r for r in results if r['kind']==kind and r['variant']=='l1']
        get_effect=(f"几乎消除热点 Redis GET（三轮累计 {sum(r['requests'] for r in group):,} 个 HTTP 请求，实例 GET 增量合计 {sum(r['get_delta'] for r in group)} 次）" if kind=='hot' else f"按每请求归一化的 Redis GET 减少 {x['get_per_request_reduction']:.2%}")
        lines.append(f"- {'热点' if kind=='hot' else '混合'}场景：吞吐为 Redis-only 的 {x['qps_ratio']:.2f} 倍，P95 降低 {x['p95_reduction']:.1%}，{get_effect}。")
    lines+=['','## 每轮结果','','| 场景 | 顺序/配置 | 请求数 | QPS | P95 ms | GET 增量 | 商户表行读取增量 | 异常数 |','|---|---|---:|---:|---:|---:|---:|---:|']
    for r in results:lines.append(f"| {r['kind']} | {r['slot']} / {r['variant']} | {r['requests']:,} | {r['total_qps']:,.1f} | {r['latency_ms']['p95']:.1f} | {r['get_delta']:,} | {r['shop_rows_read_delta']} | {r['errors']} |")
    lines+=['','## 结果口径与证据','','- 同一最终 JAR SHA-256：`9071ccfd4ede7ca37c0f4408b396dcf2b7a692b094e0c88e21d84ea11ad09a3f`；Nginx/Tomcat 长连接优化在两臂均开启。业务启动参数逐项比较，仅本地缓存开关不同（GC 日志文件名因轮次不同）。','- L1 容量 10,000、TTL 300 秒。已存在商户 L2 TTL 30 分钟；负缓存在正式测量前设为正常 120 秒。所有正式样本的商户表行读取增量为 0，比较的是预热后的读路径。','- 启用 L1 命中会跳过 Redis 布隆过滤器、Redis GET 及 JSON 反序列化；所以这是项目完整 L1 快速路径的收益，并非 Caffeine 和 Redis 单次操作的微基准。','- Redis GET 为实例计数；定时对账可能产生少量额外 GET。Redis EVAL、GETBIT 等命令增量完整保留，但脚本内部命令也计数，不能把它们简单相加当作网络请求数。','- Caffeine 命中率使用测量窗口的 hit/miss 差值，Redis-only 的空统计不解释为 100% 命中；混合场景命中率分母包含不存在 ID。',f"- {output['formal_requests']:,} 个正式 HTTP 请求已从 gzip NDJSON 原始记录独立重计，核对数量、状态、业务结果、连续唯一序号及 P95（0.1ms 桶精度）；12 个样本均无异常、无丢弃、无监控错误。",'- 这是单机短时缓存容量对照，压测器、Nginx、Java、数据库和 Redis 共享主机资源。不能外推生产整体吞吐、冷启动、失效风暴或分布式缓存一致性。系统/应用 CPU、客户端 CPU/事件循环延迟、Redis CPU 和剩余资源保留在 results.json 及 telemetry。','- 未改持久化策略；未新增订单。结束状态已恢复 L1 开启，详情见 complete.json。原应用日志、启动参数、每次预热和正式测试的 before/after 快照及原始请求均保留。','', '## 使用边界','','此前约 1,900→13,450 QPS 的测试，两边都已启用本地缓存，主要反映连接复用等配置修复。本报告固定这些配置，只比较 L1 开/关；两组收益不能相乘，也不能合并成一次性能提升。']
    with (OUT/'REPORT.md').open('x',encoding='utf-8') as stream:stream.write('\n'.join(lines)+'\n')
    print(json.dumps({'aggregates':aggregates,'formal_requests':output['formal_requests'],'audit':True},ensure_ascii=False,indent=2))

if __name__=='__main__':main()
