"""Run repeatable cache comparisons and seckill batches on the isolated stack."""
import argparse
import importlib.util
import json
import pathlib
import subprocess
import time

spec = importlib.util.spec_from_file_location('fixtures', pathlib.Path(__file__).with_name('benchmark-fixtures.py'))
f = importlib.util.module_from_spec(spec)
spec.loader.exec_module(f)
OUT = f.ROOT / 'output/benchmarks/localperks-2026-10-26'
OUT.mkdir(parents=True, exist_ok=True)
NODE = r'C:\Program Files\nodejs\node.exe'


def save(name, value):
    with (OUT / name).open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)


def load(name, **options):
    output = OUT / (name + '.json')
    cmd = [NODE, str(f.ROOT / 'tests/benchmark-http.mjs'), f'--output={output}']
    cmd += [f'--{key}={value}' for key, value in options.items()]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)
    return json.loads(output.read_text(encoding='utf-8'))


def cache():
    deadline=time.time()+60
    while True:
        try:
            f.http('/shop/1',8082)
            break
        except Exception:
            if time.time()>deadline: raise
            time.sleep(1)
    f.Redis().command('PUBLISH','cache:invalidate:shop','1')
    time.sleep(.2)
    for port in (8081, 8082):
        assert f.http('/shop/1', port)['id'] == 1
        load(f'warmup-cache-{port}', port=port, seconds=10, concurrency=100)
    for run in range(1, 4):
        for label, port in ([('l2',8082),('l1-l2',8081)] if run % 2 else [('l1-l2',8081),('l2',8082)]):
            name = f'cache-{label}-r{run}'
            before = f.snapshot(port=port)
            result = load(name, port=port, concurrency=100, seconds=30)
            after = f.snapshot(port=port)
            deltas = {'l1_hits': after['l1']['hitCount']-before['l1']['hitCount'],
                      'l1_misses': after['l1']['missCount']-before['l1']['missCount'],
                      'shop_rows_read': after['shop_rows_read']-before['shop_rows_read'],
                      'redis_get_calls': int(after['redis_commandstats'].get('get',{}).get('calls',0))-
                                         int(before['redis_commandstats'].get('get',{}).get('calls',0))}
            save(name+'-evidence.json', {'before':before,'after':after,'deltas':deltas})
            print(json.dumps({'case':name,'qps':result['successful_qps'],'p95':result['latency_ms']['p95'],
                              'p99':result['latency_ms']['p99'],'outcomes':result['outcomes'],'deltas':deltas}), flush=True)
            assert result['unexpected_errors']==0 and result['outcomes'].get('success')==result['requests']
            assert (deltas['l1_hits']==0 if label=='l2' else deltas['l1_misses']==0), deltas
            assert deltas['redis_get_calls']==(result['requests'] if label=='l2' else 0), deltas


def batch(name, voucher, stock, users, count, repeat=1, kind='seckill', concurrency=100):
    f.seed(voucher, stock, users)
    before = f.snapshot(voucher)
    result = load(name, kind=kind, voucher=voucher, users=users, count=count, repeat=repeat, concurrency=concurrency)
    accepted = set(result['accepted_order_ids'])
    first_count = int(f.sql(f'SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id={voucher};'))
    deadline = time.time()+120
    while True:
        n = int(f.sql(f'SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id={voucher};'))
        if n >= len(accepted):
            break
        if time.time() >= deadline:
            raise RuntimeError('MQ did not drain in 120 seconds')
        time.sleep(.25)
    settled_epoch = time.time()
    after = f.snapshot(voucher)
    persisted = set(f.sql(f'SELECT id FROM tb_voucher_order WHERE voucher_id={voucher};').splitlines())
    r = f.Redis()
    ids = sorted(accepted)
    pending = 0
    for offset in range(0,len(ids),500):
        chunk=ids[offset:offset+500]
        r.pipeline([('GET','seckill:queue:'+oid) for oid in chunk])
        pending += sum(str(r.read()).startswith('PENDING:') for _ in chunk)
    v=after['voucher']
    consistency={'accepted_ids_match_persisted':accepted==persisted,
                 'duplicate_groups':after['duplicate_user_voucher_groups'],
                 'pending_accepted_queue_keys':pending,
                 'stock_conserved':v['dbStock']+v['orderCount']==stock,
                 'redis_stock_equals_db':int(v['redisStock'])==v['dbStock'],
                 'no_oversell':v['orderCount']<=stock and v['dbStock']>=0,
                 'occupied_equals_orders':v['occupiedUsers']==v['orderCount']}
    elapsed=settled_epoch-result['started_at_ms']/1000
    evidence={'before':before,'after':after,'consistency':consistency,
              'first_observed_order_count_after_http_batch':first_count,
              'batch_completion_seconds_upper_bound':elapsed,
              'persisted_orders_per_batch_wall_second':len(persisted)/elapsed,
              'poll_interval_seconds':.25}
    save(name+'-evidence.json', evidence)
    print(json.dumps({'case':name,'http_success_qps':result['successful_qps'],
                      'p95_ms':result['latency_ms']['p95'],'outcomes':result['outcomes'],
                      'completion_seconds':elapsed,'persisted_batch_tps':len(persisted)/elapsed,
                      'consistency':consistency}), flush=True)
    assert result['unexpected_errors']==0 and accepted==persisted
    assert consistency['no_oversell'] and consistency['stock_conserved'] and consistency['redis_stock_equals_db']
    assert consistency['duplicate_groups']==0 and consistency['occupied_equals_orders']
    return result


def seckill():
    batch('warmup-seckill',900100,200,200,200,concurrency=20)
    for run in range(1,4):
        batch(f'seckill-unique-r{run}',900100+run,5000,5000,5000)
    batch('seckill-oversubscribe',900110,1000,2000,4000,repeat=2)
    batch('seckill-rate-limit',900111,10,1,100,kind='rate')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('suite',choices=['cache','seckill'])
    args=parser.parse_args()
    cache() if args.suite=='cache' else seckill()
