$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$out=Join-Path $project 'output\benchmarks\localperks-optimization-2026-10-28'
$python='C:\Users\xinyuxie\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
if (-not (Test-Path "$out/before/orders-soak-matched-150rps-900s-evidence.json")) { throw 'Wait for baseline HTTP completion' }
if (-not (Test-Path "$out/before/keepalive-probe.json")) {
    & $python "$PSScriptRoot/keepalive-probe.py" before
    if ($LASTEXITCODE -ne 0) { throw 'Keepalive probe failed' }
}
if (-not (Test-Path "$out/nginx-failure-regression.json")) { & "$PSScriptRoot/nginx-upstream-failure.ps1" }
$appPid=[int](Get-Content "$project/tmp/localperks-benchmark-20261028-aftersteady/backend.pid")
$app=Get-CimInstance Win32_Process -Filter "ProcessId=$appPid"
if ($app) {
    if (-not $app.CommandLine.Contains("$project\target\local-perks-0.0.1-SNAPSHOT.jar")) { throw 'Baseline process identity mismatch' }
    Stop-Process -Id $appPid
}
$env:JAVA_HOME='D:\PyChram_project\Java_learn\Redis\runtime\zulu8.96.0.205-ca-jdk8.0.504-win_x64'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:13306/localperks_bench?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true'
$env:SPRING_DATASOURCE_USERNAME='root'
$env:DB_PASSWORD=''
$env:SPRING_REDIS_HOST='127.0.0.1'
$env:SPRING_REDIS_PORT='16379'
$env:SPRING_REDIS_PASSWORD='local-benchmark-only'
$env:REDIS_PORT='16379'
$env:REDIS_PASSWORD='local-benchmark-only'
$env:ROCKETMQ_NAME_SERVER='127.0.0.1:19876'
$env:RUN_REDIS_SCRIPT_TESTS='true'
$maven='D:\PyChram_project\Java_learn\Redis\runtime\apache-maven-3.9.16\bin\mvn.cmd'
& $maven '-Dtest=OrderStateMachineTest,OrderStatusRaceTest,HttpConnectionConfigTest,SeckillConcurrencyTests,TradeReconcileSafetyTests,StockWarmupScriptTest' '-Dtrade.close-delay-level=16' test *> "$out/regression-batch.log"
if ($LASTEXITCODE -ne 0) { throw 'Regression tests failed; do not benchmark failed build' }
& $maven -DskipTests package *> "$out/package-batch.log"
if ($LASTEXITCODE -ne 0) { throw 'Package failed' }
if (Test-Path "$out/after-batch.jar") { throw 'Do not overwrite captured JAR' }
Copy-Item "$project/target/local-perks-0.0.1-SNAPSHOT.jar" "$out/after-batch.jar"
Copy-Item "$project/target/surefire-reports" "$out/test-reports-batch" -Recurse
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp "$project/tmp/benchmark-close-tools/lib/*" -d "$project/tmp/benchmark-close-tools" "$PSScriptRoot/BenchmarkCloseLoad.java"
if ($LASTEXITCODE -ne 0) { throw 'Background replay tool compilation failed' }
'Final regression and package complete'
