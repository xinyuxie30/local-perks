param([switch]$AppOnly, [switch]$BaselineOnly, [ValidatePattern('^\d{8}(-[a-z]+)?$')][string]$RunTag = '20261026', [switch]$Durable,
    [string]$AppJar = '', [ValidatePattern('^[a-z-]+$')][string]$AppName = 'backend')
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$run = Join-Path $project "tmp\localperks-benchmark-$RunTag"
$java = 'D:\PyChram_project\Java_learn\Redis\runtime\zulu8.96.0.205-ca-jdk8.0.504-win_x64\bin\java.exe'
$mq = 'D:\tools\rocketmq\rocketmq-all-4.9.7-bin-release'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe'
New-Item -ItemType Directory -Force $run | Out-Null
function Launch($name, $exe, $arguments) {
    $p = Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $project -WindowStyle Hidden -PassThru -RedirectStandardOutput "$run\$name.out.log" -RedirectStandardError "$run\$name.err.log"
    $p.Id | Set-Content "$run\$name.pid"
    Write-Output "$name PID=$($p.Id)"
}
function App($name, $port, $maxSize) {
    $jarPath = if ($AppJar) { (Resolve-Path -LiteralPath $AppJar).Path } else { "$project\target\local-perks-0.0.1-SNAPSHOT.jar" }
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "App port $port already occupied" }
    if (Test-Path -LiteralPath "$run\$name.out.log") { throw 'App log exists; choose a fresh AppName instead of overwriting evidence.' }
    $env:DB_PASSWORD = ''
    $args = @('-Xms512m','-Xmx1g','-Dfile.encoding=UTF-8','-jar',"`"$jarPath`"",'--server.address=127.0.0.1',"--server.port=$port",'--spring.datasource.url=jdbc:mysql://127.0.0.1:13306/localperks_bench?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true','--spring.datasource.username=root','--spring.datasource.password=','--spring.redis.host=127.0.0.1','--spring.redis.port=16379','--spring.redis.password=local-benchmark-only','--rocketmq.name-server=127.0.0.1:19876',"--app.cache.shop-max-size=$maxSize",'--logging.level.com.hmdp=INFO','--seckill.mode=A','--trade.close-delay-level=16','--trade.close-delay-minutes=30')
    $args = @('-XX:+PrintGCDetails','-XX:+PrintGCDateStamps',"-Xloggc:$run\$name.gc.log") + $args
    if ($maxSize -eq 0) { $args += '--app.cache.shop-local-enabled=false' }
    Launch $name $java $args
}
if ($BaselineOnly) { App 'backend-baseline' 8082 0; exit }
if ($AppOnly) { App $AppName 8081 10000; exit }
$occupied = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in @(13306,16379,19876,20911) }
if ($occupied) { throw 'Isolated dependency ports already occupied; refusing to launch duplicate services.' }
if (-not (Test-Path "$run\mysql-data\auto.cnf")) {
    New-Item -ItemType Directory -Force "$run\mysql-data" | Out-Null
    & $mysql --no-defaults --initialize-insecure "--datadir=$run\mysql-data" --console 2>&1 | Out-File "$run\mysql-init.log"
    if ($LASTEXITCODE -ne 0) { throw 'Isolated MySQL initialization failed; inspect mysql-init.log.' }
}
Launch 'mysql' $mysql @('--no-defaults',"--datadir=`"$run\mysql-data`"",'--port=13306','--bind-address=127.0.0.1','--mysqlx=OFF','--innodb-buffer-pool-size=512M','--console')
$wslRun = "/mnt/d/PyChram_project/Java_learn/Redis/local-perks/tmp/localperks-benchmark-$RunTag"
$appendOnly = if ($Durable) { 'yes' } else { 'no' }
Launch 'redis' 'wsl.exe' @('-d','Ubuntu','--exec','redis-server','--bind','127.0.0.1','--port','16379','--dir',$wslRun,'--dbfilename','benchmark.rdb','--appendonly',$appendOnly,'--appendfsync','everysec','--requirepass','local-benchmark-only')
$flushDiskType = if ($Durable) { 'SYNC_FLUSH' } else { 'ASYNC_FLUSH' }
$store = ($run.Replace('\','/') + '/mq-store')
@"
listenPort=20911
namesrvAddr=127.0.0.1:19876
brokerIP1=127.0.0.1
brokerClusterName=LocalPerksBenchmark
brokerName=benchmark-broker
brokerId=0
brokerRole=ASYNC_MASTER
flushDiskType=$flushDiskType
autoCreateTopicEnable=true
autoCreateSubscriptionGroup=true
storePathRootDir=$store
storePathCommitLog=$store/commitlog
mapedFileSizeCommitLog=67108864
diskMaxUsedSpaceRatio=95
"@ | Set-Content "$run\broker.conf" -Encoding ascii
'listenPort=19876' | Set-Content "$run\namesrv.conf" -Encoding ascii
Launch 'namesrv' $java @('-Xms128m','-Xmx256m',"-Drocketmq.home.dir=$mq",'-cp',"`"$mq\conf;$mq\lib\*`"",'org.apache.rocketmq.namesrv.NamesrvStartup','-c',"`"$run\namesrv.conf`"")
Launch 'broker' $java @('-Xms256m','-Xmx512m',"-Drocketmq.home.dir=$mq",'-cp',"`"$mq\conf;$mq\lib\*`"",'org.apache.rocketmq.broker.BrokerStartup','-c',"`"$run\broker.conf`"")
# Initialize the isolated schema with benchmark-fixtures.py before starting the app.
Write-Output 'Dependencies launched. Run benchmark-fixtures.py init, then benchmark-app.ps1.'
