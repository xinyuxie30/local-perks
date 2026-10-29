"""LocalPerks read-only HTTP benchmark; no model calls or database fixture writes.

Example: python tests/http-read-benchmark.py --url http://127.0.0.1:8081/shop/1
Only /shop/<id> or /api/shop/<id> on loopback are allowed. The endpoint must
return HTTP 200, success=true and non-null data. This is a warmed shop-read
benchmark, NOT a seckill/order-throughput benchmark. Client and server share
the machine. urllib creates connections per request (no keep-alive pool).
"""
import argparse
import concurrent.futures
import datetime
import json
import math
import pathlib
import platform
import re
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter


def request(url):
    start = time.perf_counter()
    status = 'transport_error'
    ok = False
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(url, timeout=5) as response:
            status = str(response.status)
            data = json.loads(response.read())
            ok = response.status == 200 and data.get('success') is True and data.get('data') is not None
            if not ok:
                status += '_business_failure'
    except urllib.error.HTTPError as exc:
        status = str(exc.code)
    except (OSError, ValueError, AttributeError):
        pass
    return (time.perf_counter()-start)*1000, ok, status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8081/shop/1')
    parser.add_argument('--concurrency', type=int, default=20)
    parser.add_argument('--seconds', type=int, default=30)
    parser.add_argument('--warmup', type=int, default=30, help='Sequential warm-up requests, excluded from measurements')
    parser.add_argument('--output', default='output/benchmarks/shop-read.json')
    parser.add_argument('--preflight-only', action='store_true')
    args = parser.parse_args()
    url = urllib.parse.urlsplit(args.url)
    if url.scheme != 'http' or url.hostname not in ('127.0.0.1', 'localhost', '::1') or not re.fullmatch(r'/(api/)?shop/[1-9][0-9]*', url.path) or url.query or url.fragment or url.username:
        parser.error('Only local HTTP shop detail GET endpoints are supported.')
    if not 1 <= args.concurrency <= 200 or not 1 <= args.seconds <= 300 or not 1 <= args.warmup <= 1000:
        parser.error('Use concurrency 1..200, seconds 1..300 and warmup 1..1000.')
    if not request(args.url)[1]:
        raise SystemExit('Preflight failed: backend unreachable or shop response invalid. No benchmark result generated.')
    if args.preflight_only:
        print('Preflight passed; no load was generated.')
        return
    for _ in range(args.warmup):
        if not request(args.url)[1]:
            raise SystemExit('Warm-up failed. No benchmark result generated.')
    started = time.perf_counter()
    deadline = started + args.seconds
    def worker():
        rows = []
        while time.perf_counter() < deadline:
            rows.append(request(args.url))
        return rows
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(worker) for _ in range(args.concurrency)]
        rows = [row for future in futures for row in future.result()]
    elapsed = time.perf_counter()-started
    latencies = sorted(row[0] for row in rows)
    successes = sum(row[1] for row in rows)
    percentile = lambda p: latencies[max(0, math.ceil(len(latencies)*p)-1)]
    report = dict(timestamp=datetime.datetime.now().astimezone().isoformat(), url=args.url,
        scenario='warm shop detail GET; same-machine client/server; no keep-alive',
        client_platform=platform.platform(), concurrency=args.concurrency, configured_seconds=args.seconds,
        warmup_requests=args.warmup, elapsed_seconds=elapsed, requests=len(rows), business_successes=successes,
        successful_qps=successes/elapsed, total_qps=len(rows)/elapsed, error_rate=(len(rows)-successes)/len(rows),
        latency_ms_all_requests=dict(p50=percentile(.5), p95=percentile(.95), p99=percentile(.99), mean=statistics.mean(latencies)),
        status_counts=dict(Counter(row[2] for row in rows)),
        caveats=['No seckill writes tested', 'Cache hit tier is not instrumented', 'Closed-loop load, not production capacity', 'Client creates a new connection per request'])
    dest = pathlib.Path(args.output)
    dest.parent.mkdir(parents=True, exist_ok=True)
    with dest.open('x', encoding='utf-8') as file:
        json.dump(report, file, ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
