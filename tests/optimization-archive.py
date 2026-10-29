"""Snapshot measured configuration, implementation and bounded logs after load ends."""
import datetime, gzip, hashlib, json, pathlib, shutil, subprocess
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'output/benchmarks/localperks-optimization-2026-10-28'
DEST=OUT/'archive'
STAGES=('before','after','aftersteady','afterbatch','afterbatchretry','beforesteadyretry','afterbatchsteady','beforeburst','afterbatchburst')
if not all((OUT/s/'environment.json').exists() for s in STAGES):raise RuntimeError('Incomplete run')
if not (OUT/'afterbatchburst/background-final.json').exists():raise RuntimeError('Load has not finished')
DEST.mkdir(exist_ok=False)
def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()
entries=[]
for stage in STAGES:
    run=ROOT/('tmp/localperks-benchmark-20261028-'+stage)
    for relative in ('backend.gc.log','backend.out.log','backend.err.log','broker.conf','namesrv.conf','process-map.json','nginx/nginx.conf','nginx/logs/error.log'):
        source=run/relative
        if not source.exists():continue
        size=source.stat().st_size;remaining=size;raw_hash=hashlib.sha256()
        target=DEST/(stage+'-'+relative.replace('/','-')+'.gz')
        with source.open('rb') as stream,gzip.open(target,'xb',compresslevel=1) as zipped:
            while remaining:
                block=stream.read(min(1024*1024,remaining))
                if not block:break
                raw_hash.update(block);zipped.write(block);remaining-=len(block)
        entries.append({'source':str(source.relative_to(ROOT)),'archive':target.name,'source_size_at_start':size,'copied_bytes':size-remaining,'snapshot_sha256':raw_hash.hexdigest(),'archive_sha256':digest(target)})
sources=list((ROOT/'tests').glob('optimization-*'))+list((ROOT/'tests').glob('prelaunch-*'))
sources += [ROOT/'src/main/java/com/hmdp/service/OrderBatchWriter.java', ROOT/'src/main/java/com/hmdp/mq/OrderPersistenceBatcher.java', ROOT/'src/main/java/com/hmdp/mq/SeckillOrderConsumer.java']
sources += [ROOT/'tmp/benchmark-close-tools/BenchmarkCloseLoad.class']
sources += [ROOT/p for p in ('tests/BenchmarkCloseLoad.java','tests/benchmark-start.ps1','tests/benchmark-fixtures.py','tests/benchmark-nginx.ps1','tests/keepalive-probe.py','tests/nginx-upstream-failure.ps1','src/main/java/com/hmdp/config/HttpConnectionConfig.java','src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java','src/main/java/com/hmdp/utils/RedisConstants.java','src/main/resources/seckill.lua','src/test/java/com/hmdp/OrderStateMachineTest.java','src/test/java/com/hmdp/OrderStatusRaceTest.java','src/test/java/com/hmdp/HttpConnectionConfigTest.java','nginx-1.18.0/conf/nginx.conf')]
for source in sources:
    if not source.is_file():continue
    target=DEST/'source'/source.relative_to(ROOT)
    target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
    entries.append({'source':str(source.relative_to(ROOT)),'archive':str(target.relative_to(DEST)),'snapshot_sha256':digest(target)})
hashes={str(p.relative_to(ROOT)):digest(p) for p in (ROOT/'src').rglob('*') if p.is_file() and p.suffix in ('.java','.lua','.yaml','.yml','.xml')}
git=lambda *a:subprocess.run(['git',*a],cwd=ROOT,capture_output=True,encoding='utf-8',errors='replace').stdout
manifest={'captured_at':datetime.datetime.now().astimezone().isoformat(),'git_head':git('rev-parse','HEAD').strip(),'git_status':git('status','--short'),'note':'Source hashes include existing user changes. Logs bounded at copy-start size. Databases preserved in isolated directories; no production deployment or resume edits.','jars':{v:digest(OUT/(v+'.jar')) for v in ('before','after','after-batch')},'source_sha256':hashes,'files':entries}
(DEST/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'archived_files':len(entries),'jars':manifest['jars']}),flush=True)
