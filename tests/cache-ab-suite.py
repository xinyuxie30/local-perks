"""Same final JAR and ingress; switch only app.cache.shop-local-enabled.

Uses the existing isolated benchmark dependencies, without inserting orders.
Never changes resume files. Writes all measurements with exclusive creation.
"""
import datetime, hashlib, importlib.util, json, pathlib, shutil, subprocess, sys, time

ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'output/benchmarks/localperks-cache-ab-2026-10-28'
RUN=ROOT/'tmp/localperks-benchmark-20261028-afterbatchburst'
spec=importlib.util.spec_from_file_location('suite',ROOT/'tests/prelaunch-suite.py')
s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
s.OUT=OUT;s.RUN=RUN;s.STOP=OUT/'STOP'
PW=shutil.which('pwsh') or r'C:\Program Files\PowerShell\7\pwsh.exe'
SLOTS=[('one','redis'),('two','l1'),('three','l1'),('four','redis'),('five','redis'),('six','l1')]

def guard():
    if 'localperks-benchmark-20261028-afterbatchburst' not in s.f.sql('SELECT @@datadir;') or s.f.DB!='localperks_bench':
        raise RuntimeError('Wrong isolated data directory')
    if shutil.disk_usage(ROOT).free<10*1024**3:raise RuntimeError('Less than 10 GiB disk headroom')
s.guard=guard

def redis_commands(commands):
    r=s.f.Redis()
    try:
        replies=[]
        for off in range(0,len(commands),500):
            batch=commands[off:off+500];r.pipeline(batch);replies.extend(r.read() for _ in batch)
        return replies
    finally:r.reader.close();r.sock.close()

def switch(slot,variant):
    guard();print(f'SWITCH {slot} {variant}',flush=True)
    cmd=[PW,'-NoProfile','-File',str(ROOT/'tests/cache-ab-app.ps1'),'-Enabled','true' if variant=='l1' else 'false','-Slot',slot]
    with (OUT/f'{slot}-startup.log').open('x',encoding='utf-8') as log:
        subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=210)
    s.previous.clear()

def refresh_existing(slot):
    ids=[1]+list(range(10000,30000))
    replies=redis_commands([('EXPIRE','cache:shop:'+str(i),1800) for i in ids])
    if len(replies)!=20001 or sum(replies)!=20001:raise RuntimeError('L2 prewarm incomplete')
    s.save(f'{slot}-l2-existing-preparation.json',{'keys':len(replies),'all_present':True,'ttl_seconds':1800})

def refresh_missing(slot):
    # Same valid negative-cache values in both arms, only for verified absent IDs.
    if int(s.f.sql('SELECT COUNT(*) FROM tb_shop WHERE id BETWEEN 90000000 AND 90009999;')):raise RuntimeError('Unexpected real shop in negative fixture range')
    replies=redis_commands([('SETEX','cache:shop:'+str(i),120,'') for i in range(90000000,90010000)])
    if not all(x=='OK' for x in replies):raise RuntimeError('Negative-cache prewarm failed')
    s.save(f'{slot}-l2-negative-preparation.json',{'keys':len(replies),'ttl_seconds':120,'database_absence_verified':True})

def plan():
    OUT.mkdir(exist_ok=False)
    (OUT/'PLAN.md').write_text('''# LocalPerks 缓存 A/B 对照计划

- 时间：2026-10-28。单机 Windows，Redis 位于 WSL，隔离 MySQL 13306 / Redis 16379 / MQ 19876。
- 同一个已验证的 after-batch.jar，经相同 Nginx 8080 → Tomcat 8081；固定 JVM 512 MiB～1 GiB、连接复用、L1 容量 10,000、TTL 300 秒，唯一业务配置差异为 shop-local-enabled=false/true。
- 3 对测试，顺序 Redis→L1、L1→Redis、Redis→L1；每臂重启应用，等布隆过滤器完整加载 20,014 家店铺，再开始预热。无需新建数据库、写订单或外部 API。
- 每个场景/每臂独立重启应用；等量预热 50,000 次请求（避免较快一臂获得更多 JIT 预热），再正式测 60 秒。热点为 400 并发 /shop/1；混合为 200 并发。
- 混合负载沿用原始确定性生成器，70% 热点池 100 家、20% 长尾池 20,000 家、10% 不存在 ID；生成器从相同序号开始，闭环测试请求数由响应速度决定。
- 初次经真实 HTTP 填充 20,000 个合成店铺的 Redis L2；每臂将现有 L2 正常 TTL 刷新为 30 分钟；混合预热前及正式测量前为经 SQL 验证不存在的 10,000 个 ID 预热空值，正常 TTL 120 秒。避免把数据库冷启动当成本地缓存收益。
- 保留完整压缩请求日志、HTTP 吞吐/P50/P95/P99/错误、Redis 各命令计数差、Caffeine 命中/未命中差、shop 表行读取差、5 秒资源采样。正式样本三轮均报告，聚合用中位数。
- Redis GET 是实例级计数，含极少量后台任务访问；布隆过滤器 Lua 命令会同时产生 Redis 内部 GETBIT 等计数，不能将总命令数等同网络往返。
- 只测已预热读路径；不能推导冷缓存、失效风暴、缓存一致性或生产容量；混合请求的预期 not_found 单独列出，不算异常。
- 同机压测器/服务共享 CPU、Windows Nginx/WSL 网络会限制结果；保留客户端 CPU 与事件循环延迟，不能宣称纯 Redis 上限。
- 保持已有持久化配置；磁盘可用空间低于 10 GiB 停止。结束恢复本地缓存开启；不改简历或业务代码。
''',encoding='utf-8')
    print('PLAN CREATED',flush=True)

def run():
    guard()
    if not (OUT/'PLAN.md').exists():raise RuntimeError('Missing plan')
    s.save('environment.json',{'at':datetime.datetime.now().astimezone().isoformat(),'jar_sha256':hashlib.sha256((ROOT/'output/benchmarks/localperks-optimization-2026-10-28/after-batch.jar').read_bytes()).hexdigest(),'generator_sha256':hashlib.sha256((ROOT/'tests/prelaunch-http.mjs').read_bytes()).hexdigest(),'nginx_config':(RUN/'nginx/nginx.conf').read_text(encoding='utf-8-sig'),'broker_config':(RUN/'broker.conf').read_text(),'redis_server':s.info('server'),'redis_persistence':s.info('persistence'),'mysql_settings':s.f.sql("SHOW VARIABLES WHERE Variable_name IN ('version','datadir','innodb_buffer_pool_size','innodb_flush_log_at_trx_commit','sync_binlog','log_bin');"),'shops':int(s.f.sql('SELECT COUNT(*) FROM tb_shop;')),'order_status_counts':s.f.sql('SELECT status,COUNT(*) FROM tb_voucher_order GROUP BY status;'),'resources':s.resources(),'slots':SLOTS})
    last_variant=None
    try:
        prep,_=s.load('prepare-l2-existing',kind='cold-cache',port=8080,seconds=120,count=20000,concurrency=50)
        if prep['outcomes']!={'success':20000}:raise RuntimeError('L2 preparation failed')
        s.f.http('/shop/1')
        for slot,variant in SLOTS:
            for kind,concurrency in [('hot',400),('mixed-cache',200)]:
                last_variant=None
                switch(slot+'-'+kind,variant);last_variant=variant
                refresh_existing(slot+'-'+kind)
                if kind=='mixed-cache':refresh_missing(slot+'-warmup')
                name=f'{slot}-{variant}-{kind}'
                s.load(name+'-warmup',kind=kind,port=8080,seconds=120,count=50000,concurrency=concurrency)
                if kind=='mixed-cache':refresh_missing(slot+'-measured')
                s.save(name+'-redis-cpu-before.json',s.info('cpu'))
                result,_=s.load(name,kind=kind,port=8080,seconds=60,concurrency=concurrency)
                s.save(name+'-redis-cpu-after.json',s.info('cpu'))
                if result['unexpected_errors'] or result['aborted'] or result['dropped_iterations']:raise RuntimeError('Invalid measurement: '+name)
            print('ARM COMPLETE '+slot,flush=True)
        s.save('complete.json',{'completed_at':datetime.datetime.now().astimezone().isoformat(),'restored_local_cache':True,'backend_pid':int((RUN/'backend.pid').read_text().strip()),'order_status_counts':s.f.sql('SELECT status,COUNT(*) FROM tb_voucher_order GROUP BY status;')})
    finally:
        if last_variant!='l1':switch('restore','l1')

if __name__=='__main__':
    {'plan':plan,'run':run}[sys.argv[1]]()
