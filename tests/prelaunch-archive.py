"""Freeze bounded copies of the local benchmark logs/configuration after traffic ends."""
import datetime, gzip, hashlib, json, pathlib, shutil, subprocess

ROOT=pathlib.Path(__file__).resolve().parents[1]
RUN=ROOT/'tmp/localperks-benchmark-20261027'
OUT=ROOT/'output/benchmarks/localperks-prelaunch-2026-10-27'
DEST=OUT/'provenance'
DEST.mkdir(exist_ok=False)

def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
    return h.hexdigest()

entries=[]
for relative in ['backend.gc.log','backend.out.log','backend.err.log','broker.conf','namesrv.conf','process-map.json','nginx/nginx.conf','nginx/nginx.original.conf','nginx/nginx.keepalive.conf','nginx/nginx.keepalive-tuned.conf','nginx/logs/error.log','nginx/logs/access.log']:
    source=RUN/relative
    if not source.exists():continue
    size=source.stat().st_size
    target=DEST/(relative.replace('/','-')+'.gz')
    raw_hash=hashlib.sha256();remaining=size
    with source.open('rb') as stream,gzip.open(target,'xb',compresslevel=1) as zipped:
        while remaining:
            block=stream.read(min(1024*1024,remaining))
            if not block:break
            raw_hash.update(block);zipped.write(block);remaining-=len(block)
    entries.append({'source':str(source),'copied_bytes':size-remaining,'source_size_at_start':size,'snapshot_sha256':raw_hash.hexdigest(),'archive':target.name,'archive_sha256':digest(target)})
for pattern in ['prelaunch-*','benchmark-start.ps1','benchmark-nginx.ps1','benchmark-fixtures.py']:
    for source in (ROOT/'tests').glob(pattern):
        if not source.is_file():continue
        target=DEST/source.name
        if target.exists():continue
        shutil.copyfile(source,target)
        entries.append({'source':str(source),'archive':target.name,'snapshot_sha256':digest(target)})

active=digest(RUN/'nginx/nginx.conf');original=digest(RUN/'nginx/nginx.original.conf')
restoration={'observed_at':datetime.datetime.now().astimezone().isoformat(),'nginx_config_matches_original':active==original,'active_sha256':active,'original_sha256':original,'services':'Isolated benchmark services and data retained; load generators have ended.','production_deployment_config_modified':False}
with (OUT/'restoration.json').open('x',encoding='utf-8') as stream:json.dump(restoration,stream,ensure_ascii=False,indent=2)
source_hashes={str(p.relative_to(ROOT)):digest(p) for p in (ROOT/'src').rglob('*') if p.is_file() and p.suffix in ('.java','.yml','.yaml','.xml','.lua')}
git_head=subprocess.run(['git','rev-parse','HEAD'],cwd=ROOT,capture_output=True,text=True).stdout.strip()
git_status=subprocess.run(['git','status','--short'],cwd=ROOT,capture_output=True,text=True).stdout
with (DEST/'manifest.json').open('x',encoding='utf-8') as stream:json.dump({'captured_at':restoration['observed_at'],'note':'Logs copied only up to their size at copy start; services may append afterward. No database backup is implied. Source hashes reflect working tree, including pre-existing changes.','git_head':git_head,'git_status':git_status,'source_sha256':source_hashes,'files':entries},stream,ensure_ascii=False,indent=2)
print(json.dumps({'archived_files':len(entries),'configuration_restored':active==original},ensure_ascii=False))
