"""Prelaunch-style localhost capacity/soak tests, isolated 20261027 stack only.

No deployment, paid API traffic, automatic HTTP retry, or resume edits.
Artifacts are exclusive-create. Data and active settings are captured before load.
"""
import argparse, ctypes, datetime, hashlib, importlib.util, json, os, pathlib
import shutil, statistics, subprocess, threading, time

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('fixtures', ROOT/'tests/benchmark-fixtures.py')
f = importlib.util.module_from_spec(spec); spec.loader.exec_module(f)
OUT = ROOT/'output/benchmarks/localperks-prelaunch-2026-10-27'
RUN = ROOT/'tmp/localperks-benchmark-20261027'
NODE = r'C:\Program Files\nodejs\node.exe'
JAVA_HOME = pathlib.Path(r'D:\PyChram_project\Java_learn\Redis\runtime\zulu8.96.0.205-ca-jdk8.0.504-win_x64')
MQ = pathlib.Path(r'D:\tools\rocketmq\rocketmq-all-4.9.7-bin-release')
STOP = OUT/'STOP'
OUT.mkdir(parents=True, exist_ok=True)

def save(name, data):
    with (OUT/name).open('x', encoding='utf-8') as stream:
        json.dump(data, stream, ensure_ascii=False, indent=2)

def guard():
    datadir=f.sql('SELECT @@datadir;')
    if 'localperks-benchmark-20261027' not in datadir or f.DB!='localperks_bench':
        raise RuntimeError('Refuse operation outside current isolated benchmark datadir')

def info(section):
    r=f.Redis()
    try:
        return {k:v for line in r.command('INFO',section).splitlines() if line and not line.startswith('#') for k,_,v in [line.partition(':')]}
    finally:
        r.reader.close();r.sock.close()

def prepare():
    guard()
    if int(f.sql('SELECT COUNT(*) FROM tb_shop WHERE id>=10000;')):
        raise RuntimeError('Synthetic shop IDs already exist; refusing overwrite')
    for off in range(0,20000,500):
        values=','.join(f"({10000+i},'BENCHMARK-SHOP-{i}',1,'benchmark.jpg','load-test','isolated benchmark address',120.1,30.2,80,100,10,45)" for i in range(off,off+500))
        f.sql('INSERT INTO tb_shop(id,name,type_id,images,area,address,x,y,avg_price,sold,comments,score) VALUES '+values+';')
    r=f.Redis()
    for off in range(0,50000,500):
        ids=range(off,off+500)
        values=','.join(f"({1000000+i},'{18000000000+i}','bench-{i}')" for i in ids)
        f.sql('INSERT INTO tb_user(id,phone,nick_name) VALUES '+values+';')
        cmds=[]
        for i in ids:
            cmds.extend([('HSET',f'login:token:lp-bench-{i}','id',1000000+i,'nickName',f'bench-{i}'),('EXPIRE',f'login:token:lp-bench-{i}',86400)])
        r.pipeline(cmds)
        for _ in cmds:r.read()
    print('Prepared 20,000 additional shops and 50,000 distinct users',flush=True)

def seed(voucher, stock):
    guard()
    if not 910000<=voucher<920000 or not 1<=stock<=50000:raise ValueError('Bounded benchmark voucher only')
    if int(f.sql(f'SELECT COUNT(*) FROM tb_voucher WHERE id={voucher};')):raise RuntimeError('Voucher exists')
    f.sql(f"INSERT INTO tb_voucher(id,shop_id,title,pay_value,actual_value,type,status) VALUES({voucher},1,'PRELAUNCH-{voucher}',1,100,1,1);INSERT INTO tb_seckill_voucher(voucher_id,stock,total_stock,begin_time,end_time) VALUES({voucher},{stock},{stock},NOW()-INTERVAL 1 DAY,NOW()+INTERVAL 1 DAY);")
    return f.http(f'/admin/seckill/{voucher}/warmup',method='POST')

class Memory(ctypes.Structure):
    _fields_=[('length',ctypes.c_ulong),('load',ctypes.c_ulong)]+[(s,ctypes.c_ulonglong) for s in ['total_physical','available_physical','total_page','available_page','total_virtual','available_virtual','extended']]

class Counters(ctypes.Structure):
    _fields_=[('cb',ctypes.c_ulong),('page_faults',ctypes.c_ulong)]+[(s,ctypes.c_size_t) for s in ['peak_working_set','working_set','peak_paged','paged','peak_nonpaged','nonpaged','pagefile','peak_pagefile','private_bytes']]

kernel=ctypes.WinDLL('kernel32',use_last_error=True)
kernel.OpenProcess.restype=ctypes.c_void_p
kernel.GetProcessTimes.argtypes=[ctypes.c_void_p]+[ctypes.POINTER(ctypes.c_ulonglong)]*4
kernel.CloseHandle.argtypes=[ctypes.c_void_p]
psapi=ctypes.WinDLL('psapi');psapi.GetProcessMemoryInfo.argtypes=[ctypes.c_void_p,ctypes.c_void_p,ctypes.c_ulong]
previous={}

def resources():
    now=time.monotonic();m=Memory();m.length=ctypes.sizeof(m);kernel.GlobalMemoryStatusEx(ctypes.byref(m))
    idle,kern,user=[ctypes.c_ulonglong() for _ in range(3)]
    kernel.GetSystemTimes(ctypes.byref(idle),ctypes.byref(kern),ctypes.byref(user))
    current=(idle.value,kern.value+user.value);old=previous.get('system');previous['system']=current
    cpu=100*(1-(current[0]-old[0])/max(1,current[1]-old[1])) if old else None
    result={'system_cpu_percent':cpu,'available_memory_bytes':m.available_physical,'total_memory_bytes':m.total_physical,'disk_free_bytes':shutil.disk_usage(ROOT).free,'processes':{}}
    mapping_path=RUN/'process-map.json'
    mapping=json.loads(mapping_path.read_text(encoding='utf-8-sig')) if mapping_path.exists() else {label:int(p.read_text().strip()) for label in ('backend','broker','namesrv','mysql') if (p:=RUN/(label+'.pid')).exists()}
    for label,pid in mapping.items():
        h=kernel.OpenProcess(0x410,False,pid)
        if not h:continue
        try:
            ts=[ctypes.c_ulonglong() for _ in range(4)];kernel.GetProcessTimes(h,*[ctypes.byref(x) for x in ts]);total=(ts[2].value+ts[3].value)/1e7
            c=Counters();c.cb=ctypes.sizeof(c);psapi.GetProcessMemoryInfo(h,ctypes.byref(c),c.cb)
            old=previous.get(label);previous[label]=(now,total)
            result['processes'][label]={'pid':pid,'working_set_bytes':c.working_set,'private_bytes':c.private_bytes,'cpu_core_percent':100*(total-old[1])/(now-old[0]) if old else None}
        finally:kernel.CloseHandle(h)
    return result

def mq_progress(name):
    cmd=[str(JAVA_HOME/'bin/java.exe'),'-Xmx128m',f'-Drocketmq.home.dir={MQ}','-cp',str(MQ/'conf')+';'+str(MQ/'lib/*'),'org.apache.rocketmq.tools.command.MQAdminStartup','consumerProgress','-n','127.0.0.1:19876','-g','cg-seckill-order']
    try:
        r=subprocess.run(cmd,capture_output=True,timeout=25)
        (OUT/(name+'-mq.txt')).write_bytes(r.stdout+r.stderr)
    except Exception as e:(OUT/(name+'-mq.txt')).write_text(str(e))

def monitor(name, vouchers, done):
    low=0
    with (OUT/(name+'-telemetry.ndjson')).open('x',encoding='utf-8') as stream:
        while not done.is_set():
            row={'at_ms':time.time()*1000}
            try:
                row.update(resources());row['redis_memory']=info('memory');row['redis_stats']=info('stats');row['redis_persistence']=info('persistence')
                status=f.sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_running','Threads_connected','Slow_queries','Innodb_row_lock_time','Innodb_row_lock_waits','Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests','Aborted_connects');")
                row['mysql_status']=dict(s.split('\t',1) for s in status.splitlines())
                if vouchers:
                    rows=f.sql('SELECT voucher_id,COUNT(*),SUM(status=4) FROM tb_voucher_order WHERE voucher_id IN ('+','.join(map(str,vouchers))+') GROUP BY voucher_id;')
                    row['orders']=[dict(zip(['voucher','persisted','canceled'],map(int,s.split('\t')))) for s in rows.splitlines()]
                    row['orders_observed_at_ms']=time.time()*1000
                low=low+1 if row['available_memory_bytes']<3*1024**3 or row['disk_free_bytes']<10*1024**3 else 0
                if low>=3:STOP.write_text('Resource threshold: less than 3 GiB available RAM or 10 GiB disk for 3 samples')
            except Exception as e:row['monitor_error']=str(e)
            stream.write(json.dumps(row,ensure_ascii=False)+'\n');stream.flush();done.wait(5)

def settle(name, result, stocks):
    accepted={x['id']:x for x in result['accepted_orders']}
    vids=','.join(map(str,stocks));deadline=time.time()+300;observations=[]
    while True:
        n=int(f.sql(f'SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id IN ({vids});'))
        observations.append({'at_ms':time.time()*1000,'persisted':n})
        if n>=len(accepted) or time.time()>deadline:break
        time.sleep(2)
    rows=f.sql(f'SELECT id,user_id,voucher_id,status FROM tb_voucher_order WHERE voucher_id IN ({vids});')
    persisted={p[0]:{'user':int(p[1]),'voucher':int(p[2]),'status':int(p[3])} for line in rows.splitlines() for p in [line.split('\t')]}
    r=f.Redis();pending=0
    ids=list(accepted)
    for off in range(0,len(ids),500):
        commands=[('GET','seckill:queue:'+x) for x in ids[off:off+500]];r.pipeline(commands)
        pending+=sum(str(r.read()).startswith('PENDING:') for _ in commands)
    ledger={};checks=[]
    for vid,stock in stocks.items():
        snap=f.snapshot(vid);v=snap['voucher'];items=[x for x in persisted.values() if x['voucher']==vid];canceled=sum(x['status']==4 for x in items)
        entry={'initial_stock':stock,'snapshot':snap,'accepted':sum(x['voucher']==vid for x in accepted.values()),'checks':{
            'stock_conserved':v['dbStock']+len(items)-canceled==stock,
            'redis_matches_db':int(v['redisStock'])==v['dbStock'],
            'nonnegative_stock':v['dbStock']>=0 and int(v['redisStock'])>=0,
            'no_duplicate_user_voucher':snap['duplicate_user_voucher_groups']==0,
            'occupied_equals_orders':v['occupiedUsers']==len(items)}}
        ledger[vid]=entry;checks+=list(entry['checks'].values())
    matched=set(accepted)==set(persisted);ownership=all(persisted[k]['user']==v['user'] and persisted[k]['voucher']==v['voucher'] for k,v in accepted.items() if k in persisted)
    elapsed=(observations[-1]['at_ms']-result['started_at_ms'])/1000
    evidence={'accepted_count':len(accepted),'persisted_count':len(persisted),'accepted_ids_match_persisted':matched,'ownership_matches':ownership,'missing_ids':sorted(set(accepted)-set(persisted)),'extra_ids':sorted(set(persisted)-set(accepted)),'pending_accepted_queue_keys':pending,'batch_completion_seconds_upper_bound':elapsed,'batch_completion_tps':len(persisted)/elapsed,'observations':observations,'ledger':ledger,'pass':matched and ownership and pending==0 and all(checks)}
    save(name+'-evidence.json',evidence);return evidence

def load(name, stocks=None, observe=True, **opts):
    if STOP.exists():raise RuntimeError('Safety stop: '+STOP.read_text())
    guard();stocks=stocks or {};out=OUT/(name+'.json')
    if out.exists() or (OUT/(name+'-client.log')).exists():raise RuntimeError('Case already exists: '+name)
    save(name+'-before.json',f.snapshot())
    cmd=[NODE,str(ROOT/'tests/prelaunch-http.mjs'),f'--output={out}',f'--stopfile={STOP}']+[f'--{k}={v}' for k,v in opts.items()]
    save(name+'-request.json',{'command':cmd,'stocks':stocks,'options':opts})
    done=threading.Event();thread=threading.Thread(target=monitor,args=(name,list(stocks),done),daemon=True)
    if observe:thread.start()
    print(json.dumps({'starting':name,'options':opts}),flush=True)
    try:
        with (OUT/(name+'-client.log')).open('x',encoding='utf-8') as log:
            proc=subprocess.Popen(cmd,stdout=log,stderr=subprocess.STDOUT)
            while proc.poll() is None:
                try:proc.wait(timeout=10)
                except subprocess.TimeoutExpired:pass
                try:
                    lines=(OUT/(name+'-client.log')).read_text(encoding='utf-8').splitlines()
                    if lines:print(name+' '+lines[-1][:500],flush=True)
                except OSError:pass
            if proc.returncode:raise RuntimeError(f'Load generator failed: {name}, see client log')
        result=json.loads(out.read_text(encoding='utf-8'))
        evidence=settle(name,result,stocks) if stocks else None
    finally:
        done.set()
        if observe:thread.join(20)
    save(name+'-after.json',f.snapshot())
    if stocks:mq_progress(name)
    print(json.dumps({'finished':name,'requests':result['requests'],'success_qps':result['successful_qps'],'p95':result['latency_ms'].get('p95'),'errors':result['unexpected_errors'],'dropped':result['dropped_iterations'],'outcomes':result['outcomes'],'consistency':evidence['pass'] if evidence else None},ensure_ascii=False),flush=True)
    return result,evidence

def capture():
    guard();jar=ROOT/'target/local-perks-0.0.1-SNAPSHOT.jar'
    save('environment.json',{'captured_at':datetime.datetime.now().astimezone().isoformat(),'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'resources':resources(),'mysql_settings':f.sql("SHOW VARIABLES WHERE Variable_name IN ('version','datadir','innodb_buffer_pool_size','innodb_flush_log_at_trx_commit','sync_binlog','log_bin','max_connections');"),'redis_server':info('server'),'redis_persistence':info('persistence'),'broker_config':(RUN/'broker.conf').read_text(),'nginx_config':(RUN/'nginx/nginx.conf').read_text(encoding='utf-8'),'shop_count':int(f.sql('SELECT COUNT(*) FROM tb_shop;')),'users':50000,'boundaries':['Single Windows host; Redis runs in WSL; Nginx for Windows; loopback, no TLS or WAN','One backend, one MySQL, one Redis, one MQ broker; no HA failover claim','Real auth, rate limit, Lua, MQ, SQL; synthetic login sessions; no external payments or AI calls','Redis AOF everysec; MQ SYNC_FLUSH; default 30-minute delayed order closure retained','No production SLA supplied; provisional gates recorded in report; latency histograms round up to 0.1 ms','Do not compare directly with 20261026 ASYNC_FLUSH, AOF-disabled, direct-backend hot-key run']})

def pilot():
    load('pilot-proxy-hot',kind='hot',port=8080,seconds=15,concurrency=50)
    load('pilot-direct-hot',kind='hot',port=8081,seconds=15,concurrency=50)
    seed(910000,2000)
    load('pilot-durable-orders',stocks={910000:2000},kind='seckill',port=8080,concurrency=50,count=2000,users=2000,vouchers='910000')

def cache():
    for c in (50,100,200,400):
        result,_=load(f'cache-proxy-c{c}',kind='hot',port=8080,seconds=60,concurrency=c)
        if result['unexpected_error_rate']>.01:break
    load('cache-direct-c100',kind='hot',port=8081,seconds=60,concurrency=100)
    load('cache-mixed-c100',kind='mixed-cache',port=8080,seconds=120,concurrency=100)
    load('cache-mixed-c200',kind='mixed-cache',port=8080,seconds=120,concurrency=200)

def orders():
    for i,c in enumerate((50,100,200,400)):
        vid=910010+i;seed(vid,20000)
        result,e=load(f'orders-20k-c{c}',stocks={vid:20000},kind='seckill',port=8080,concurrency=c,count=20000,users=20000,vouchers=str(vid))
        if not e['pass']:raise RuntimeError('Order reconciliation failed; do not contaminate subsequent cases with pending work')
        if result['unexpected_error_rate']>.01:break
    seed(910020,10000)
    load('orders-100k-compete',stocks={910020:10000},kind='duplicate',port=8080,concurrency=200,count=100000,users=50000,repeat=2,vouchers='910020')
    seed(910021,100)
    load('orders-abuse-rate',stocks={910021:100},kind='rate',port=8080,concurrency=100,count=1000,users=1,vouchers='910021')

def soak(rate=150,seconds=900,read_rate=900,base=910100,label=''):
    total=rate*seconds;stocks={base+i:min(50000,total-i*50000) for i in range((total+49999)//50000)}
    for vid,stock in stocks.items():seed(vid,stock)
    errors=[]
    def reads():
        try:load(f'cache-mixed-soak-{label}{read_rate}rps-{seconds}s',observe=False,kind='mixed-cache',port=8080,concurrency=100,seconds=seconds,rate=read_rate)
        except Exception as e:
            errors.append(str(e));STOP.write_text('Concurrent read generator failed: '+str(e))
    read_thread=threading.Thread(target=reads,daemon=False)
    if read_rate:read_thread.start()
    try:
        load(f'orders-soak-{label}{rate}rps-{seconds}s',stocks=stocks,kind='seckill',port=8080,concurrency=200,seconds=seconds,rate=rate,users=50000,vouchers=','.join(map(str,stocks)))
    finally:
        if read_rate:read_thread.join(seconds+20)
    if errors:raise RuntimeError(errors)

def control():
    script=ROOT/'tests/prelaunch-nginx-control.ps1'
    cmd=['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(script),'-Mode']
    try:
        subprocess.run(cmd+['Keepalive'],check=True)
        save('nginx-control-config.json',{'configuration':(RUN/'nginx/nginx.conf').read_text(encoding='utf-8'),'changes':['Reuse named upstream with keepalive 128','Clear upstream Connection header'],'scope':'Isolated benchmark config only; production source untouched'})
        load('pilot-keepalive-warm',kind='hot',port=8080,seconds=10,concurrency=50)
        load('cache-keepalive-c100',kind='hot',port=8080,seconds=60,concurrency=100)
        load('cache-keepalive-c400',kind='hot',port=8080,seconds=60,concurrency=400)
        load('cache-mixed-keepalive-c200',kind='mixed-cache',port=8080,seconds=120,concurrency=200)
        soak(rate=150,seconds=900,read_rate=900,base=910200,label='keepalive-')
    finally:
        if (RUN/'nginx/nginx.original.conf').exists():subprocess.run(cmd+['Restore'],check=True)

def control_tuned():
    cmd=['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'tests/prelaunch-nginx-control.ps1'),'-Mode']
    try:
        subprocess.run(cmd+['KeepaliveTuned'],check=True)
        save('nginx-control-tuned-config.json',{'configuration':(RUN/'nginx/nginx.conf').read_text(encoding='utf-8'),'changes':['keepalive 512','keepalive_requests 10000','keepalive_timeout 60s','Clear upstream Connection header'],'scope':'Isolated benchmark config only'})
        deadline=time.time()+180
        while True:
            try:f.snapshot();break
            except Exception:
                if time.time()>deadline:raise
                print('Waiting for host socket resources to recover before measuring',flush=True);time.sleep(10)
        load('pilot-keepalive-v2-warm',kind='hot',port=8080,seconds=10,concurrency=50)
        load('cache-keepalive-v2-c100',kind='hot',port=8080,seconds=60,concurrency=100)
        result,_=load('cache-keepalive-v2-c400',kind='hot',port=8080,seconds=60,concurrency=400)
        if result['unexpected_error_rate']>.001:raise RuntimeError('Tuned entry still exceeds error gate; do not call it ready')
        load('cache-mixed-keepalive-v2-c200',kind='mixed-cache',port=8080,seconds=120,concurrency=200)
        soak(rate=150,seconds=900,read_rate=900,base=910300,label='keepalive-v2-')
    finally:
        if (RUN/'nginx/nginx.original.conf').exists():subprocess.run(cmd+['Restore'],check=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('phase',choices=['prepare','capture','pilot','cache','orders','soak','control','control_tuned']);parser.add_argument('--rate',type=int,default=150);parser.add_argument('--seconds',type=int,default=900);parser.add_argument('--read-rate',type=int,default=900);a=parser.parse_args()
    if a.phase=='soak':soak(a.rate,a.seconds,a.read_rate)
    else:globals()[a.phase]()
