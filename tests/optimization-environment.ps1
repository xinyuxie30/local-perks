param(
    [ValidateSet('20261027','20261028-before','20261028-after','20261028-beforeburst','20261028-afterburst','20261028-beforesteadyretry','20261028-aftersteady','20261028-afterbatch','20261028-afterbatchretry','20261028-afterbatchsteady','20261028-afterbatchburst')][string]$StopTag,
    [ValidateSet('before','after','beforeburst','afterburst','beforesteadyretry','aftersteady','afterbatch','afterbatchretry','afterbatchsteady','afterbatchburst')][string]$Stage
)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
if (Test-Path "$project/output/benchmarks/localperks-optimization-2026-10-28/HOLD_AFTER_STAGE") { throw 'Measured stage preserved; environment transition held for additional repair' }
$python='C:\Users\xinyuxie\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$nginx='D:\PyChram_project\Java_learn\Redis\runtime\nginx-1.18.0\nginx.exe'
if ($StopTag) {
    $oldRun=Join-Path $project "tmp\localperks-benchmark-$StopTag"
    # Verify the exact isolated datadir before shutting down shared localhost test ports.
    $sqlArgs=@('--no-defaults','--protocol=TCP','-h127.0.0.1','-P13306','-uroot','--batch','--skip-column-names','-e','SELECT @@datadir;')
    $datadir=& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' @sqlArgs
    if ($LASTEXITCODE -ne 0 -or -not (($datadir -replace '\\\\','\').Contains($oldRun))) { throw 'Wrong MySQL data directory; stop refused' }
    & $nginx -p ($oldRun.Replace('\','/')+'/nginx/') -c nginx.conf -s quit
    $processes=Get-CimInstance Win32_Process -Filter "Name='java.exe'"
    foreach ($process in $processes) {
        $command=$process.CommandLine
        $isApp=$command -like '*13306/localperks_bench*' -and $command.Contains($project)
        $isMq=$command.Contains($oldRun) -and ($command -like '*BrokerStartup*' -or $command -like '*NamesrvStartup*')
        if ($isApp -or $isMq) { Stop-Process -Id $process.ProcessId }
    }
    & 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqladmin.exe' '--no-defaults' '--protocol=TCP' '-h127.0.0.1' '-P13306' '-uroot' 'shutdown'
    & wsl.exe -d Ubuntu --exec redis-cli -h 127.0.0.1 -p 16379 -a local-benchmark-only --no-auth-warning shutdown save
    Start-Sleep -Seconds 3
    # Preserve stopped datasets verbatim while reclaiming their preallocated zero-filled files.
    $resolved=(Resolve-Path -LiteralPath $oldRun).Path
    if (-not $resolved.StartsWith((Join-Path $project 'tmp')+'\') -or ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Unexpected compression target' }
    & "$env:SystemRoot/System32/compact.exe" '/C' "/S:$resolved" '/I' '/Q' *> "$project/output/benchmarks/localperks-optimization-2026-10-28/compact-$StopTag.log"
    if ($LASTEXITCODE -ne 0) { throw 'Stopped dataset compression failed' }
}
if (-not $Stage) { exit }
$disk=[IO.DriveInfo]::new([IO.Path]::GetPathRoot($project))
if ($disk.AvailableFreeSpace / $disk.TotalSize -lt 0.12) { throw 'Insufficient headroom for RocketMQ disk protection; do not benchmark' }
$tag="20261028-$Stage"
$run=Join-Path $project "tmp\localperks-benchmark-$tag"
if (Test-Path -LiteralPath $run) { throw 'Fresh data directory required; refusing overwrite' }
& "$PSScriptRoot\benchmark-start.ps1" -RunTag $tag -Durable
if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw 'Dependency startup failed' }
Start-Sleep -Seconds 12
& $python "$PSScriptRoot\benchmark-fixtures.py" init
if ($LASTEXITCODE -ne 0) { throw 'Fixture initialization failed' }
& $python "$PSScriptRoot\optimization-suite.py" $Stage prepare
if ($LASTEXITCODE -ne 0) { throw 'Dataset preparation failed' }
$appJar=if ($Stage.StartsWith('before')) { "$project\output\benchmarks\localperks-optimization-2026-10-28\before.jar" } else { "$project\target\local-perks-0.0.1-SNAPSHOT.jar" }
& "$PSScriptRoot\benchmark-start.ps1" -AppOnly -RunTag $tag -AppJar $appJar
Start-Sleep -Seconds 15
$config=if ($Stage -eq 'before') { "$project\output\benchmarks\localperks-optimization-2026-10-28\nginx-before.conf" } elseif ($Stage.StartsWith('before')) { "$project\tmp\localperks-benchmark-20261027\nginx\nginx.keepalive-tuned.conf" } else { "$project\nginx-1.18.0\conf\nginx.conf" }
& "$PSScriptRoot\benchmark-nginx.ps1" -RunTag $tag -SourceConfig $config
Start-Sleep -Seconds 3
$map=@{}
foreach ($portName in @{backend=8081;mysql=13306;broker=20911;namesrv=19876}.GetEnumerator()) {
    $listener=Get-NetTCPConnection -State Listen -LocalPort $portName.Value | Select-Object -First 1
    $map[$portName.Name]=[int]$listener.OwningProcess
}
$master=[int](Get-Content "$run/nginx/logs/nginx.pid")
$map["nginx-$master"]=$master
Get-CimInstance Win32_Process -Filter "ParentProcessId=$master" | Where-Object Name -eq nginx.exe | ForEach-Object { $map["nginx-$($_.ProcessId)"]=[int]$_.ProcessId }
$map | ConvertTo-Json | Set-Content "$run/process-map.json" -Encoding UTF8
Invoke-WebRequest -Uri 'http://127.0.0.1:8080/shop/1' -UseBasicParsing -TimeoutSec 20 | Select-Object StatusCode
