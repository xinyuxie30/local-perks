$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$run=Join-Path $project 'tmp\nginx-failure-regression-20261028-v2'
if (Test-Path -LiteralPath $run) { throw 'Fresh regression directory required' }
if (Get-NetTCPConnection -State Listen -LocalPort 18089 -ErrorAction SilentlyContinue) { throw 'Test port already occupied' }
New-Item -ItemType Directory -Path "$run\logs","$run\temp" | Out-Null
$source=Get-Content "$project/nginx-1.18.0/conf/nginx.conf" -Raw -Encoding UTF8
$source=$source.Replace('include       mime.types;', 'include "'+$project.Replace('\','/')+'/nginx-1.18.0/conf/mime.types";')
$source=$source.Replace('listen       8080;', 'listen       127.0.0.1:18089;')
$source=$source.Replace('server 127.0.0.1:8081 ', 'server 127.0.0.1:1 ')
[IO.File]::WriteAllText("$run/nginx.conf",$source,[Text.UTF8Encoding]::new($false))
$exe='D:\PyChram_project\Java_learn\Redis\runtime\nginx-1.18.0\nginx.exe'
$prefix=$run.Replace('\','/')+'/'
& $exe -p $prefix -c nginx.conf -t
if ($LASTEXITCODE -ne 0) { throw 'Invalid regression config' }
Start-Process -FilePath $exe -ArgumentList @('-p',$prefix,'-c','nginx.conf') -WorkingDirectory $run -WindowStyle Hidden
try {
    Start-Sleep -Seconds 2
    $results=@()
    foreach ($path in @('/shop/1','/api/shop/1')) {
        $response=Invoke-WebRequest -Uri ("http://127.0.0.1:18089"+$path) -SkipHttpErrorCheck -TimeoutSec 10
        $body=$response.Content | ConvertFrom-Json
        if ($response.StatusCode -ne 503 -or $body.success -ne $false -or -not $body.errorMsg) { throw 'Wrong upstream failure contract' }
        $results += @{path=$path;status=$response.StatusCode;body=$body}
    }
    $results | ConvertTo-Json -Depth 5 | Set-Content "$project/output/benchmarks/localperks-optimization-2026-10-28/nginx-failure-regression.json" -Encoding UTF8
} finally { & $exe -p $prefix -c nginx.conf -s quit }
