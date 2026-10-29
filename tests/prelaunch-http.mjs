// Bounded localhost load generator: closed-loop concurrency or open-loop arrivals.
// Streams every completed request to gzip NDJSON; never retries HTTP requests.
import http from 'node:http';
import fs from 'node:fs';
import zlib from 'node:zlib';
import {performance, monitorEventLoopDelay} from 'node:perf_hooks';
import {finished} from 'node:stream/promises';
const a = Object.fromEntries(process.argv.slice(2).map(s => {const i=s.indexOf('='); return [s.slice(2,i),s.slice(i+1)];}));
const port=+(a.port||8080), concurrency=+(a.concurrency||100), seconds=+(a.seconds||60), count=+(a.count||0), rate=+(a.rate||0);
const kind=a.kind||'hot', users=+(a.users||50000), repeat=+(a.repeat||1), vouchers=(a.vouchers||'').split(',').filter(Boolean).map(Number);
if (![8080,8081,8082].includes(port) || concurrency<1 || concurrency>400 || seconds<1 || seconds>900 || count>250000 || rate>25000 || !a.output) throw Error('Outside bounded localhost load limits');
if (!['hot','mixed-cache','cold-cache','seckill','duplicate','rate'].includes(kind) || (['seckill','duplicate','rate'].includes(kind) && (!vouchers.length||vouchers.some(v=>v<910000)))) throw Error('Invalid benchmark-only target');
if (fs.existsSync(a.output)||fs.existsSync(a.output+'.raw.ndjson.gz')) throw Error('Refuse to overwrite evidence');
const sink=fs.createWriteStream(a.output+'.raw.ndjson.gz',{flags:'wx'}), gz=zlib.createGzip({level:1}); gz.pipe(sink);
const agent=new http.Agent({keepAlive:true,maxSockets:concurrency,maxFreeSockets:concurrency});
const outcomes={},statuses={},windows=new Map(),hist=new Map(),successHist=new Map(),scheduleHist=new Map(),accepted=[];
let next=0,active=0,scheduled=0,dropped=0,completed=0,aborted=false,bytes=0,raw=[],transportSamples=[];
const start=performance.now(),epoch=Date.now(),cpuStart=process.cpuUsage(),loop=monitorEventLoopDelay({resolution:20});loop.enable();
function addHist(h,ms){const b=Math.ceil(ms*10);h.set(b,(h.get(b)||0)+1);}
function percentiles(h){const n=[...h.values()].reduce((s,v)=>s+v,0);let c=0,ix=0;const q=[.5,.95,.99,1],out={count:n};for(const [b,v]of [...h.entries()].sort((x,y)=>x[0]-y[0])){c+=v;while(ix<q.length&&c>=Math.ceil(n*q[ix]))out[['p50','p95','p99','max'][ix++]]=b/10;}return out;}
function windowAt(t){const k=Math.floor(t/10000);if(!windows.has(k))windows.set(k,{start_seconds:k*10,requests:0,outcomes:{},hist:new Map(),successHist:new Map()});return windows.get(k);}
function flush(){if(raw.length){gz.write(raw.join(''));raw=[];}}
function request(i){return new Promise(resolve=>{
  active++;const t=performance.now(),isRead=['hot','mixed-cache','cold-cache'].includes(kind);
  if(rate)addHist(scheduleHist,Math.max(0,t-start-i*1000/rate));
  let shop=1,expectedMissing=false;
  if(kind==='mixed-cache'){const n=(Math.imul(i+1,2654435761)>>>0);expectedMissing=i%10===9;shop=expectedMissing?90000000+(n%10000):10000+(n%(i%10<7?100:20000));}
  if(kind==='cold-cache')shop=10000+i%20000;
  const user=kind==='rate'?0:Math.floor(i/repeat)%users,vid=vouchers[Math.floor(Math.floor(i/repeat)/users)%vouchers.length];
  const path=isRead?`/shop/${shop}`:`/voucher-order/seckill/${vid}`;
  let done=false;
  function finish(status,body,error){if(done)return;done=true;const end=performance.now(),ms=end-t;let outcome=error||'invalid_json',oid=null;
    try{const p=JSON.parse(body);if(status===200&&p.success===true&&p.data!=null){outcome=expectedMissing?'unexpected_existing_shop':'success';if(!isRead)oid=String(p.data);else if(Number(p.data.id)!==shop)outcome='wrong_shop';}
      else if(status===429)outcome='rate_limited';else if(p.errorMsg==='库存不足')outcome='sold_out';else if(p.errorMsg==='不能重复下单')outcome='duplicate';else if(isRead&&expectedMissing&&p.errorMsg==='店铺不存在！')outcome='not_found';else if(isRead&&expectedMissing&&String(p.errorMsg).includes('不存在'))outcome='not_found';else outcome=`business:${p.errorMsg||status}`;}catch{}
    outcomes[outcome]=(outcomes[outcome]||0)+1;statuses[status]=(statuses[status]||0)+1;completed++;addHist(hist,ms);
    const w=windowAt(end-start);w.requests++;w.outcomes[outcome]=(w.outcomes[outcome]||0)+1;addHist(w.hist,ms);
    if(outcome==='success'){addHist(successHist,ms);addHist(w.successHist,ms);}
    if(oid)accepted.push({id:oid,voucher:vid,user:1000000+user,started_at_ms:epoch+t-start,accepted_at_ms:epoch+end-start});
    if(error&&transportSamples.length<20)transportSamples.push({error,detail:body});
    raw.push(JSON.stringify([i,+(t-start).toFixed(3),+ms.toFixed(3),status,outcome,vid||shop,oid])+'\n');if(raw.length>=2048)flush();
    active--;resolve();
  }
  const req=http.request({host:'127.0.0.1',port,path,method:isRead?'GET':'POST',agent,headers:isRead?{}:{authorization:`lp-bench-${user}`,'content-length':'0'}},res=>{let body='';res.setEncoding('utf8');res.on('data',s=>{body+=s;bytes+=Buffer.byteLength(s);});res.on('end',()=>finish(res.statusCode,body));res.on('error',e=>finish(res.statusCode,e.message,'response_error'));});
  req.setTimeout(10000,()=>req.destroy(new Error('timeout')));req.on('error',e=>finish(0,e.code||e.message,e.message==='timeout'?'timeout':'transport_error'));req.end();
});}
const stopFile=a.stopfile;
const ticker=setInterval(()=>{if(stopFile&&fs.existsSync(stopFile))aborted=true;const secs=(performance.now()-start)/1000;console.log(JSON.stringify({progress:true,seconds:+secs.toFixed(1),completed,success:outcomes.success||0,active,dropped,outcomes}));},10000);
if(rate){const total=count||Math.floor(rate*seconds);while(next<total&&!aborted){const now=performance.now()-start;const due=Math.min(total,Math.floor(now*rate/1000)+1);while(next<due){const i=next++;scheduled++;if(active>=concurrency||gz.writableLength>16*1024*1024){dropped++;continue;}void request(i);}if(now>=seconds*1000)break;await new Promise(r=>setTimeout(r,5));}scheduled+=total-next;dropped+=total-next;while(active)await new Promise(r=>setTimeout(r,10));}
else await Promise.all(Array.from({length:concurrency},async()=>{while(!aborted&&(count?next<count:performance.now()-start<seconds*1000)){if(gz.writableLength>16*1024*1024){await new Promise(r=>setTimeout(r,5));continue;}const i=next++;scheduled++;await request(i);}}));
clearInterval(ticker);const elapsed=(performance.now()-start)/1000,cpu=process.cpuUsage(cpuStart);loop.disable();agent.destroy();flush();gz.end();await finished(sink);
const allowed=['success','sold_out','duplicate','rate_limited','not_found'],unexpected=Object.entries(outcomes).filter(([k])=>!allowed.includes(k)).reduce((s,[,v])=>s+v,0);
const summary={kind,target:`http://127.0.0.1:${port}`,concurrency,arrival_model:rate?'open-loop':'closed-loop',target_rate:rate||null,configured_seconds:seconds,configured_count:count||null,started_at:new Date(epoch).toISOString(),started_at_ms:epoch,elapsed_seconds:elapsed,scheduled,dropped_iterations:dropped,requests:completed,outcomes,http_status:statuses,unexpected_errors:unexpected,unexpected_error_rate:unexpected/Math.max(1,completed),total_qps:completed/elapsed,successful_qps:(outcomes.success||0)/elapsed,latency_ms:percentiles(hist),successful_latency_ms:percentiles(successHist),latency_histogram_resolution_ms:.1,client_cpu_seconds:(cpu.user+cpu.system)/1e6,client_event_loop_p99_ms:loop.percentile(99)/1e6,response_bytes:bytes,aborted,transport_samples:transportSamples,windows:[...windows.values()].map(w=>({...w,latency_ms:percentiles(w.hist),successful_latency_ms:percentiles(w.successHist),hist:undefined,successHist:undefined})),accepted_orders:accepted,raw_columns:['index','started_since_start_ms','latency_ms','http_status','outcome','voucher_or_shop','order_id']};
summary.configuration=a;summary.arrival_schedule_lag_ms=rate?percentiles(scheduleHist):null;
fs.writeFileSync(a.output,JSON.stringify(summary,null,2),{flag:'wx'});console.log(JSON.stringify({...summary,windows:summary.windows.length,accepted_orders:accepted.length}));
