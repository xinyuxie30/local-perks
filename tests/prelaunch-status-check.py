"""Read-only spot check of acknowledged orders after the sustained test's drain window."""
import importlib.util, json, pathlib, sys, time, urllib.request

ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'output/benchmarks/localperks-prelaunch-2026-10-27'
spec=importlib.util.spec_from_file_location('suite',ROOT/'tests/prelaunch-suite.py')
s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s);s.guard()
name='orders-soak-keepalive-v2-150rps-900s'
r=json.loads((OUT/(name+'.json')).read_text())
e=json.loads((OUT/(name+'-evidence.json')).read_text())
missing=set(e['missing_ids'])
accepted=sorted(r['accepted_orders'],key=lambda x:x['accepted_at_ms'])
samples=[x for x in accepted if x['id'] in missing][:20]+[x for x in accepted if x['id'] not in missing][-5:]
fresh='--fresh' in sys.argv
selection='Up to 20 oldest missing IDs from saved reconciliation plus 5 persisted controls; not a population estimate.'
if fresh:
    live_ids=set(s.f.sql('SELECT id FROM tb_voucher_order WHERE voucher_id IN (910300,910301,910302);').splitlines())
    cutoff=time.time()*1000-620000
    eligible=[x for x in accepted if x['id'] not in live_ids and x['accepted_at_ms']<cutoff]
    step=max(1,len(eligible)//20)
    samples=eligible[::step][:20]+[x for x in accepted if x['id'] in live_ids][-5:]
    selection=f'Fresh SQL ID snapshot; {len(eligible)} unpersisted acknowledged IDs older than 620 seconds, up to 20 spread samples plus 5 persisted controls; not a population estimate.'
rows=[]
redis=s.f.Redis()
for x in samples:
    oid=x['id']
    before=s.f.sql(f'SELECT COUNT(*) FROM tb_voucher_order WHERE id={int(oid)};')
    queued=redis.command('GET','seckill:queue:'+oid)
    ttl=redis.command('TTL','seckill:queue:'+oid)
    req=urllib.request.Request('http://127.0.0.1:8080/voucher-order/status/'+oid,headers={'authorization':'lp-bench-'+str(x['user']-1000000)})
    at=time.time()*1000
    try:
        with urllib.request.urlopen(req,timeout=10) as response:body=json.load(response)
    except Exception as exc:body={'probe_error':str(exc)}
    after=s.f.sql(f'SELECT COUNT(*) FROM tb_voucher_order WHERE id={int(oid)};')
    rows.append({'order_id':oid,'voucher':x['voucher'],'user':x['user'],'observed_at_ms':at,'seconds_since_ack':(at-x['accepted_at_ms'])/1000,'db_exists_before':before=='1','db_exists_after':after=='1','queue_value':queued,'queue_ttl_seconds':ttl,'status_response':body})
result={'case':name,'read_only':True,'selection':selection,'samples':rows,'acknowledged_but_not_found_samples':sum(not x['db_exists_before'] and not x['db_exists_after'] and (x['status_response'].get('data') or {}).get('state')=='NOT_FOUND' for x in rows)}
output='post-soak-order-status-probes-fresh.json' if fresh else 'post-soak-order-status-probes.json'
with (OUT/output).open('x',encoding='utf-8') as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
print(json.dumps({'samples':len(rows),'acknowledged_but_not_found_samples':result['acknowledged_but_not_found_samples']},ensure_ascii=False))
