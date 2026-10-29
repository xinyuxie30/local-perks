"""Black-box check of Tomcat keep-alive using real HTTP, outside measured load windows."""
import http.client, json, pathlib, sys
stage=sys.argv[1]
if stage not in ('before','after','afterbatch','afterbatchretry'):raise ValueError('Matched stage required')
root=pathlib.Path(__file__).resolve().parents[1]
out=root/'output/benchmarks/localperks-optimization-2026-10-28'/stage/'keepalive-probe.json'
ports=[];closes=[]
connection=http.client.HTTPConnection('127.0.0.1',8081,timeout=10)
try:
    for i in range(250):
        if connection.sock is None:connection.connect()
        ports.append(connection.sock.getsockname()[1])
        connection.request('GET','/shop/1',headers={'Connection':'keep-alive'})
        response=connection.getresponse();body=json.loads(response.read())
        if response.status!=200 or not body.get('success'):raise RuntimeError('Health request failed')
        if response.will_close:closes.append(i+1)
finally:connection.close()
result={'requests':250,'tcp_connections':len(set(ports)),'server_closed_after_requests':closes,'client_ports':sorted(set(ports))}
with out.open('x',encoding='utf-8') as f:json.dump(result,f,indent=2)
print(json.dumps(result))
