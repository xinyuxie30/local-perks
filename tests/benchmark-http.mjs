// Closed-loop HTTP/1.1 keep-alive load; reads actual JSON business outcomes.
// No mock server, automatic request retries, external calls, or model requests.
import http from 'node:http';
import fs from 'node:fs';
import zlib from 'node:zlib';
import {performance, monitorEventLoopDelay} from 'node:perf_hooks';
const args = Object.fromEntries(process.argv.slice(2).map(x => x.replace(/^--/, '').split('=')));
const port = Number(args.port || 8081), concurrency = Number(args.concurrency || 100);
const seconds = Number(args.seconds || 30), count = Number(args.count || 0);
const kind = args.kind || 'cache', voucher = Number(args.voucher || 0), users = Number(args.users || count);
if (![8081,8082,8080].includes(port) || concurrency < 1 || concurrency > 200 || seconds > 120 || count > 50000 || !args.output) throw Error('Invalid bounded local benchmark configuration');
if (!['cache','seckill','rate'].includes(kind) || (kind !== 'cache' && voucher < 900000)) throw Error('Only isolated benchmark vouchers are accepted');
const agent = new http.Agent({keepAlive:true, maxSockets:concurrency, maxFreeSockets:concurrency});
const rows = [], outcomes = {}, statusCounts = {}, orderIds = [];
let next = 0;
const start = performance.now(), startEpoch = Date.now(), cpuStart = process.cpuUsage();
const loop = monitorEventLoopDelay({resolution:20}); loop.enable();
function request(i) {
  return new Promise(resolve => {
    const t = performance.now();
    const isCache = kind === 'cache';
    const path = isCache ? '/shop/1' : `/voucher-order/seckill/${voucher}`;
    const user = kind === 'rate' ? 0 : Math.floor(i / Number(args.repeat || 1)) % users;
    const headers = isCache ? {} : {authorization:`lp-bench-${user}`, 'content-length':'0'};
    let done = false;
    function finish(status, body, error) {
      if (done) return; done = true;
      const ms = performance.now() - t;
      let outcome = error || 'invalid_json', orderId = null;
      try {
        const parsed = JSON.parse(body);
        if (status === 200 && parsed.success === true && parsed.data != null) {
          outcome = 'success'; if (!isCache) orderId = String(parsed.data);
        } else if (status === 429) outcome = 'rate_limited';
        else if (parsed.errorMsg === '库存不足') outcome = 'sold_out';
        else if (parsed.errorMsg === '不能重复下单') outcome = 'duplicate';
        else outcome = `unexpected_business:${parsed.errorMsg || status}`;
      } catch {}
      rows.push([i, performance.now()-start, ms, status, outcome]);
      outcomes[outcome] = (outcomes[outcome] || 0) + 1;
      statusCounts[status] = (statusCounts[status] || 0) + 1;
      if (orderId) orderIds.push(orderId);
      resolve();
    }
    const req = http.request({host:'127.0.0.1',port,path,method:isCache?'GET':'POST',headers,agent}, res => {
      let data='';res.setEncoding('utf8');res.on('data', chunk => data += chunk);
      res.on('end', () => finish(res.statusCode,data));res.on('error', () => finish(res.statusCode,'','response_error'));
    });
    req.setTimeout(10000, () => req.destroy(new Error('timeout')));
    req.on('error', e => finish(0,'',e.message === 'timeout'?'timeout':'transport_error'));
    req.end();
  });
}
async function worker() {
  while (count ? next < count : performance.now() - start < seconds * 1000) {
    const i = next++;
    await request(i);
  }
}
await Promise.all(Array.from({length:concurrency},worker));
const elapsed = (performance.now()-start)/1000, cpu=process.cpuUsage(cpuStart);
loop.disable();agent.destroy();
function latency(values) {
  values.sort((a,b) => a-b);
  const p = q => values[Math.max(0,Math.ceil(values.length*q)-1)] || 0;
  return {p50:p(.5),p95:p(.95),p99:p(.99),max:p(1)};
}
const unexpected = rows.filter(r => !['success','sold_out','duplicate','rate_limited'].includes(r[4])).length;
const summary = {started_at:new Date(startEpoch).toISOString(),started_at_ms:startEpoch,elapsed_seconds:elapsed,
  target:`http://127.0.0.1:${port}`,kind,voucher,concurrency,users,repeat:Number(args.repeat||1),configured_seconds:count?null:seconds,
  requests:rows.length,outcomes,http_status:statusCounts,total_qps:rows.length/elapsed,successful_qps:(outcomes.success||0)/elapsed,
  unexpected_errors:unexpected,unexpected_error_rate:unexpected/rows.length,
  unsuccessful_rate:1-(outcomes.success||0)/rows.length,latency_ms:latency(rows.map(r=>r[2])),
  successful_latency_ms:latency(rows.filter(r=>r[4]==='success').map(r=>r[2])),
  client_cpu_seconds:(cpu.user+cpu.system)/1e6,client_event_loop_p99_ms:loop.percentile(99)/1e6,
  accepted_order_ids:orderIds,raw_rows_columns:['request_index','completed_since_start_ms','latency_ms','http_status','outcome']};
fs.writeFileSync(args.output,JSON.stringify(summary,null,2),{flag:'wx'});
fs.writeFileSync(args.output+'.raw.json.gz',zlib.gzipSync(JSON.stringify(rows)),{flag:'wx'});
console.log(JSON.stringify({...summary,accepted_order_ids:`${orderIds.length} IDs saved in result file`}));
