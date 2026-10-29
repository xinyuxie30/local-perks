"""Fixtures and measurements for the isolated localhost:13306/16379 stack only.

Never imports into hmdp or connects to the normal development ports.
Uses the installed mysql CLI and a small RESP client; no third-party packages.
"""
import argparse
import datetime
import json
import pathlib
import re
import socket
import subprocess
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
MYSQL = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
DB = 'localperks_bench'


def sql(statement, database=DB):
    args = [MYSQL, '--no-defaults', '--protocol=TCP', '-h127.0.0.1', '-P13306',
            '-uroot', '--default-character-set=utf8mb4', '--batch', '--skip-column-names']
    if database:
        args.append(database)
    result = subprocess.run(args, input=statement.encode('utf-8'), capture_output=True, check=True)
    return result.stdout.decode('utf-8').strip()


class Redis:
    def __init__(self):
        self.sock = socket.create_connection(('127.0.0.1', 16379), 10)
        self.reader = self.sock.makefile('rb')
        self.command('AUTH', 'local-benchmark-only')

    def read(self):
        line = self.reader.readline()
        kind, data = line[:1], line[1:-2]
        if kind == b'-':
            raise RuntimeError(data.decode())
        if kind == b'+':
            return data.decode()
        if kind == b':':
            return int(data)
        if kind == b'$':
            n = int(data)
            if n < 0:
                return None
            value = self.reader.read(n)
            self.reader.read(2)
            return value.decode()
        if kind == b'*':
            return [self.read() for _ in range(int(data))]
        raise RuntimeError('Unexpected Redis reply')

    def command(self, *args):
        self.pipeline([args])
        return self.read()

    def pipeline(self, commands):
        payload = bytearray()
        for args in commands:
            payload.extend(f'*{len(args)}\r\n'.encode())
            for arg in args:
                value = str(arg).encode()
                payload.extend(f'${len(value)}\r\n'.encode() + value + b'\r\n')
        self.sock.sendall(payload)


def http(path, port=8081, method='GET'):
    request = urllib.request.Request(f'http://127.0.0.1:{port}{path}',
                                     headers={'authorization': 'lp-bench-admin'}, method=method)
    with urllib.request.urlopen(request, timeout=15) as response:
        result = json.load(response)
    if not result.get('success'):
        raise RuntimeError(result)
    return result.get('data')


def initialize():
    existing = sql("SHOW DATABASES LIKE 'localperks_bench';", None)
    if existing:
        raise RuntimeError('Benchmark schema already exists. Refusing to reimport/drop its tables.')
    sql(f'CREATE DATABASE {DB} CHARACTER SET utf8mb4;', None)
    for name in ('hmdp.sql', 'V2__trade.sql', 'V3__comment.sql', 'V4__stock_restore_outbox.sql'):
        source = (ROOT / 'src/main/resources/db' / name).read_text(encoding='utf-8-sig')
        source = re.sub(r'(?im)^USE\s+hmdp\s*;', f'USE {DB};', source)
        sql("SET SESSION sql_mode='NO_ENGINE_SUBSTITUTION';\n" + source)
    sql("INSERT INTO tb_user(id,phone,nick_name) VALUES(1011,'19999999999','benchmark-admin');")
    redis = Redis()
    redis.command('HSET', 'login:token:lp-bench-admin', 'id', 1011, 'nickName', 'benchmark-admin')
    redis.command('EXPIRE', 'login:token:lp-bench-admin', 86400)
    print(json.dumps({'database': DB, 'shop_count': int(sql('SELECT COUNT(*) FROM tb_shop;'))}))


def seed(voucher, stock, users):
    assert voucher >= 900000 and 1 <= stock <= 50000 and 1 <= users <= 50000
    if int(sql(f'SELECT COUNT(*) FROM tb_voucher WHERE id={voucher};')):
        raise RuntimeError('Voucher already exists; choose a fresh ID to preserve measurements.')
    sql(f"INSERT INTO tb_voucher(id,shop_id,title,pay_value,actual_value,type,status) "
        f"VALUES({voucher},1,'BENCHMARK-{voucher}',1,100,1,1);"
        f"INSERT INTO tb_seckill_voucher(voucher_id,stock,total_stock,begin_time,end_time) "
        f"VALUES({voucher},{stock},{stock},NOW()-INTERVAL 1 DAY,NOW()+INTERVAL 1 DAY);")
    redis = Redis()
    for offset in range(0, users, 500):
        ids = range(offset, min(users, offset + 500))
        values = ','.join(f"({1000000+i},'{18000000000+i}','bench-{i}')" for i in ids)
        sql('INSERT IGNORE INTO tb_user(id,phone,nick_name) VALUES ' + values + ';')
        commands = []
        for i in ids:
            commands.extend([('HSET', f'login:token:lp-bench-{i}', 'id', 1000000+i, 'nickName', f'bench-{i}'),
                             ('EXPIRE', f'login:token:lp-bench-{i}', 86400)])
        redis.pipeline(commands)
        for _ in commands:
            redis.read()
    return http(f'/admin/seckill/{voucher}/warmup', method='POST')


def snapshot(voucher=None, port=8081):
    redis = Redis()
    commands = {}
    for line in redis.command('INFO', 'commandstats').splitlines():
        if line.startswith('cmdstat_'):
            name, fields = line.split(':', 1)
            commands[name[8:]] = dict(item.split('=') for item in fields.split(','))
    result = {'at': datetime.datetime.now().astimezone().isoformat(),
              'redis_commandstats': commands, 'l1': http('/admin/cache/stats', port),
              'shop_rows_read': int(sql("SELECT COALESCE(SUM(COUNT_READ),0) FROM "
                                        "performance_schema.table_io_waits_summary_by_table "
                                        "WHERE OBJECT_SCHEMA='localperks_bench' AND OBJECT_NAME='tb_shop';"))}
    if voucher:
        result['voucher'] = http(f'/admin/seckill/{voucher}/stats', port)
        result['distinct_order_users'] = int(sql(f'SELECT COUNT(DISTINCT user_id) FROM tb_voucher_order WHERE voucher_id={voucher};'))
        result['duplicate_user_voucher_groups'] = int(sql('SELECT COUNT(*) FROM (SELECT user_id,voucher_id FROM tb_voucher_order '
                                                        f'WHERE voucher_id={voucher} GROUP BY user_id,voucher_id HAVING COUNT(*)>1) t;'))
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'seed', 'snapshot'])
    parser.add_argument('--voucher', type=int)
    parser.add_argument('--stock', type=int, default=1000)
    parser.add_argument('--users', type=int, default=2000)
    parser.add_argument('--port', type=int, default=8081)
    parser.add_argument('--output')
    args = parser.parse_args()
    if args.action == 'init':
        initialize()
        sys.exit()
    result = seed(args.voucher, args.stock, args.users) if args.action == 'seed' else snapshot(args.voucher, args.port)
    if args.output:
        with open(args.output, 'x', encoding='utf-8') as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False))
