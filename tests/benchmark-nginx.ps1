param([ValidatePattern('^\d{8}(-[a-z]+)?$')][string]$RunTag = '20261026', [string]$SourceConfig = '')
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$run = Join-Path $project "tmp\localperks-benchmark-$RunTag\nginx"
$exe = 'D:\PyChram_project\Java_learn\Redis\runtime\nginx-1.18.0\nginx.exe'
New-Item -ItemType Directory -Force "$run\logs", "$run\temp" | Out-Null
$configPath = if ($SourceConfig) { $SourceConfig } else { "$project\nginx-1.18.0\conf\nginx.conf" }
$source = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8
$source = $source.Replace('include       mime.types;', ('include "' + $project.Replace('\','/') + '/nginx-1.18.0/conf/mime.types";'))
$source = $source.Replace('listen       8080;', 'listen       127.0.0.1:8080;')
$source = $source.Replace('root   html/hmdp;', ('root "' + $project.Replace('\','/') + '/nginx-1.18.0/html/hmdp";'))
[IO.File]::WriteAllText("$run\nginx.conf", $source, [Text.UTF8Encoding]::new($false))
$prefix = $run.Replace('\','/') + '/'
& $exe -p $prefix -c nginx.conf -t
if ($LASTEXITCODE -ne 0) { throw 'Nginx configuration check failed' }
$p = Start-Process $exe -ArgumentList @('-p',$prefix,'-c','nginx.conf') -WindowStyle Hidden -WorkingDirectory $run -PassThru
$p.Id | Set-Content "$run\launcher.pid"
Write-Output 'LocalPerks frontend: http://127.0.0.1:8080 (isolated benchmark database)'
