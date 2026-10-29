"""Matched before/after workload. Reuses the unchanged, audited HTTP generator."""
import argparse, hashlib, importlib.util, json, pathlib, subprocess, threading, time

ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE=ROOT/'output/benchmarks/localperks-optimization-2026-10-28'
spec=importlib.util.spec_from_file_location('previous_suite',ROOT/'tests/prelaunch-suite.py')
s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)

def configure(stage):
    assert stage in ('probe','before','after','beforeburst','afterburst','beforesteady','beforesteadyretry','aftersteady','afterbatch','afterbatchretry','afterbatchsteady','afterbatchburst')
    tag='20261027' if stage=='probe' else '20261028-'+stage
    s.OUT=BASE/stage;s.OUT.mkdir(exist_ok=True);s.RUN=ROOT/('tmp/localperks-benchmark-'+tag);s.STOP=s.OUT/'STOP'
    def guard():
        if 'localperks-benchmark-'+tag not in s.f.sql('SELECT @@datadir;') or s.f.DB!='localperks_bench':
            raise RuntimeError('Wrong isolated data directory')
    s.guard=guard;guard()

def capture(stage):
    jar=BASE/'before.jar' if stage.startswith('before') else ROOT/'target/local-perks-0.0.1-SNAPSHOT.jar'
    s.save('environment.json',{'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'mysql_settings':s.f.sql("SHOW VARIABLES WHERE Variable_name IN ('version','datadir','innodb_buffer_pool_size','innodb_flush_log_at_trx_commit','sync_binlog','log_bin','max_connections');"),'redis_persistence':s.info('persistence'),'broker_config':(s.RUN/'broker.conf').read_text(),'nginx_config':(s.RUN/'nginx/nginx.conf').read_text(encoding='utf-8-sig'),'shop_count':int(s.f.sql('SELECT COUNT(*) FROM tb_shop;')),'users':50000,'resources':s.resources(),'generator_sha256':hashlib.sha256((ROOT/'tests/prelaunch-http.mjs').read_bytes()).hexdigest()})

def seed_closures():
    # Pre-existing unpaid orders have already reached the unchanged 30-minute deadline.
    # Replay their due MQ messages at exactly the same offered rate in both variants.
    s.guard();r=s.f.Redis()
    for v in (919900,919901):
        s.seed(v,27000)
        for off in range(0,27000,500):
            rows=','.join(f"({770000000000000000+(v-919900)*27000+i},{1000000+i},{v},1,1,NOW())" for i in range(off,off+500))
            s.f.sql('INSERT INTO tb_voucher_order(id,user_id,voucher_id,pay_type,status,create_time) VALUES '+rows+';')
            r.command('SADD','seckill:order:'+str(v),*[1000000+i for i in range(off,off+500)])
        s.f.sql(f'UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id={v};')
        r.command('SET','seckill:stock:'+str(v),0)
    # Publish overdue timestamps only after SQL and Redis fixtures are coherent;
    # otherwise the regular reconciliation timer could close half-initialized fixtures.
    s.f.sql('UPDATE tb_voucher_order SET create_time=NOW()-INTERVAL 31 MINUTE WHERE voucher_id IN (919900,919901);')

def run_soak(burst=False):
    seed_closures()
    java=str(s.JAVA_HOME/'bin/java.exe')
    cp=str(ROOT/'tmp/benchmark-close-tools')+';'+str(ROOT/'tmp/benchmark-close-tools/lib/*')
    with (s.OUT/'background-close-client.log').open('x') as log:
        command=[java,'-Xmx128m','-cp',cp,'BenchmarkCloseLoad',str(s.OUT/'background-close.raw.ndjson')]
        if burst:command.append('burst')
        proc=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT)
        try:s.soak(rate=150,seconds=300 if burst else 900,read_rate=900,base=910300,label='burst-' if burst else 'matched-')
        finally:
            try:proc.wait(timeout=30)
            except subprocess.TimeoutExpired:proc.terminate();raise RuntimeError('Background sender exceeded measurement window')
        if proc.returncode:raise RuntimeError('Background sender failed')
    s.save('background-final.json',{'orders':s.f.sql('SELECT voucher_id,status,COUNT(*) FROM tb_voucher_order WHERE voucher_id IN (919900,919901) GROUP BY voucher_id,status;'),'snapshots':{str(v):s.f.snapshot(v) for v in (919900,919901)}})

def formal(stage):
    capture(stage)
    s.load('warmup',kind='hot',port=8080,seconds=10,concurrency=50)
    for c in (50,100,200,400):s.load(f'cache-proxy-c{c}',kind='hot',port=8080,seconds=60,concurrency=c)
    # Stable ingress for both backend variants isolates the transactional change.
    if stage=='before':
        source=ROOT/'tmp/localperks-benchmark-20261027/nginx/nginx.keepalive-tuned.conf'
        active=s.RUN/'nginx/nginx.conf';active.write_bytes(source.read_bytes())
        exe=ROOT.parent/'runtime/nginx-1.18.0/nginx.exe'
        subprocess.run([str(exe),'-p',str(active.parent).replace('\\','/')+'/','-c','nginx.conf','-t'],check=True)
        subprocess.run([str(exe),'-p',str(active.parent).replace('\\','/')+'/','-c','nginx.conf','-s','reload'],check=True)
        time.sleep(60)
        s.save('transaction-ingress.json',{'config':active.read_text(encoding='utf-8'),'reason':'Use previous successful keepalive-v2 ingress for a fair backend-only comparison'})
    transactions()

def transactions():
    s.load('cache-mixed-c200',kind='mixed-cache',port=8080,seconds=120,concurrency=200)
    s.orders()
    # Sustained tests use separate, fresh beforesteadyretry/aftersteady datasets.

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('stage',choices=['probe','before','after','beforeburst','afterburst','beforesteadyretry','aftersteady','afterbatch','afterbatchretry','afterbatchsteady','afterbatchburst']);p.add_argument('phase',choices=['prepare','formal','probe','transactions','burst','steady']);a=p.parse_args();configure(a.stage)
    if a.phase=='prepare':s.prepare()
    elif a.phase=='probe':
        s.seed(918000,20000)
        s.load('orders-20k-probe',stocks={918000:20000},kind='seckill',port=8080,concurrency=200,count=20000,users=20000,vouchers='918000')
    elif a.phase=='transactions':
        active=s.RUN/'nginx/nginx.conf'
        config=active.read_text(encoding='utf-8')
        if 'keepalive 512' not in config:raise RuntimeError('Stable ingress is required for transaction comparison')
        if not (s.OUT/'transaction-ingress.json').exists():s.save('transaction-ingress.json',{'config':config,'reason':'Resumed after metadata encoding error; no measured case overwritten'})
        transactions()
    elif a.phase in ('burst','steady'):
        capture(a.stage)
        s.load('warmup',kind='hot',port=8080,seconds=10,concurrency=50)
        run_soak(burst=a.phase=='burst')
    else:formal(a.stage)
