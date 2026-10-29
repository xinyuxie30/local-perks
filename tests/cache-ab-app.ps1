param([ValidateSet('false','true')][string]$Enabled, [ValidatePattern('^[a-z-]+$')][string]$Slot)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$run=Join-Path $project 'tmp/localperks-benchmark-20261028-afterbatchburst'
$out=Join-Path $project 'output/benchmarks/localperks-cache-ab-2026-10-28'
$jar=Join-Path $project 'output/benchmarks/localperks-optimization-2026-10-28/after-batch.jar'
$java='D:\PyChram_project\Java_learn\Redis\runtime\zulu8.96.0.205-ca-jdk8.0.504-win_x64\bin\java.exe'
if ((Get-FileHash -LiteralPath $jar).Hash -ne '9071ccfd4ede7ca37c0f4408b396dcf2b7a692b094e0c88e21d84ea11ad09a3f') { throw 'Unexpected benchmark JAR' }
if (-not (Test-Path -LiteralPath $out)) { throw 'Plan/output directory must already exist' }
if (Test-Path -LiteralPath "$out/$Slot-app.out.log") { throw 'Refuse to overwrite application evidence' }
$listener=Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue
if ($listener) {
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener[0].OwningProcess)"
    if ($process.Name -ne 'java.exe' -or -not $process.CommandLine.Contains($project) -or -not $process.CommandLine.Contains('13306/localperks_bench') -or -not $process.CommandLine.Contains('--server.port=8081')) { throw 'Refuse to stop a non-benchmark application' }
    $process.CommandLine | Set-Content "$out/$Slot-previous-command.txt" -Encoding utf8
    Stop-Process -Id $process.ProcessId
    Wait-Process -Id $process.ProcessId -Timeout 15 -ErrorAction SilentlyContinue
}
$env:DB_PASSWORD=''
$appArgs=@('-XX:+PrintGCDetails','-XX:+PrintGCDateStamps',"-Xloggc:$out/$Slot-app.gc.log",'-Xms512m','-Xmx1g','-Dfile.encoding=UTF-8','-jar',"`"$jar`"",'--server.address=127.0.0.1','--server.port=8081','--spring.datasource.url=jdbc:mysql://127.0.0.1:13306/localperks_bench?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true','--spring.datasource.username=root','--spring.datasource.password=','--spring.redis.host=127.0.0.1','--spring.redis.port=16379','--spring.redis.password=local-benchmark-only','--rocketmq.name-server=127.0.0.1:19876','--app.cache.shop-max-size=10000',"--app.cache.shop-local-enabled=$Enabled",'--logging.level.com.hmdp=INFO','--seckill.mode=A','--trade.close-delay-level=16','--trade.close-delay-minutes=30')
$appArgs | ConvertTo-Json | Set-Content "$out/$Slot-app-args.json" -Encoding utf8
$p=Start-Process -FilePath $java -ArgumentList $appArgs -WorkingDirectory $project -WindowStyle Hidden -PassThru -RedirectStandardOutput "$out/$Slot-app.out.log" -RedirectStandardError "$out/$Slot-app.err.log"
$p.Id | Set-Content "$out/$Slot-app.pid"
$map=Get-Content "$run/process-map.json" -Raw | ConvertFrom-Json
$map.backend=$p.Id
$map | ConvertTo-Json | Set-Content "$run/process-map.json" -Encoding utf8
$p.Id | Set-Content "$run/backend.pid"
$deadline=(Get-Date).AddSeconds(180)
do {
    if ($p.HasExited) { throw 'Application exited during startup' }
    $ready=Select-String -LiteralPath "$out/$Slot-app.out.log" -Pattern '店铺布隆过滤器就绪' -Quiet
    if ($ready) {
        $health=Invoke-RestMethod -Uri 'http://127.0.0.1:8080/shop/1' -TimeoutSec 10
        if ($health.success -and $health.data.id -eq 1) { Write-Output "READY slot=$Slot enabled=$Enabled pid=$($p.Id)"; exit 0 }
    }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)
throw 'Application did not finish Bloom initialization in 180 seconds'
