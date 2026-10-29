param([ValidateSet('Keepalive','KeepaliveTuned','Restore')][string]$Mode)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$run = Join-Path $project 'tmp\localperks-benchmark-20261027\nginx'
$exe = 'D:\PyChram_project\Java_learn\Redis\runtime\nginx-1.18.0\nginx.exe'
$active = Join-Path $run 'nginx.conf'
$original = Join-Path $run 'nginx.original.conf'
$prefix = $run.Replace('\','/') + '/'
if ($Mode -in @('Keepalive','KeepaliveTuned')) {
    if ($Mode -eq 'Keepalive') {
        if (Test-Path -LiteralPath $original) { throw 'Control backup already exists; refusing to overwrite it.' }
        Copy-Item -LiteralPath $active -Destination $original
    } elseif (-not (Test-Path -LiteralPath $original)) { throw 'Original backup required for tuned control.' }
    $source = Get-Content -LiteralPath $original -Raw -Encoding UTF8
    $source = $source.Replace('proxy_pass http://127.0.0.1:8081;', "proxy_set_header Connection `"`";`r`n            proxy_pass http://backend;")
    $settings = if ($Mode -eq 'Keepalive') { "keepalive 128;" } else { "keepalive 512;`r`n        keepalive_requests 10000;`r`n        keepalive_timeout 60s;" }
    $source = $source.Replace('upstream backend {', "upstream backend {`r`n        $settings")
    $variant = if ($Mode -eq 'Keepalive') { 'nginx.keepalive.conf' } else { 'nginx.keepalive-tuned.conf' }
    if (Test-Path -LiteralPath (Join-Path $run $variant)) { throw 'Control variant already exists; evidence is immutable.' }
    [IO.File]::WriteAllText((Join-Path $run $variant), $source, [Text.UTF8Encoding]::new($false))
    & $exe -p $prefix -c $variant -t
    if ($LASTEXITCODE -ne 0) { throw 'Control configuration validation failed; active config unchanged.' }
    Copy-Item -LiteralPath (Join-Path $run $variant) -Destination $active -Force
} else {
    if (-not (Test-Path -LiteralPath $original)) { throw 'No original configuration to restore.' }
    Copy-Item -LiteralPath $original -Destination $active -Force
}
& $exe -p $prefix -c nginx.conf -t
if ($LASTEXITCODE -ne 0) { throw 'Active benchmark configuration validation failed.' }
& $exe -p $prefix -c nginx.conf -s reload
if ($LASTEXITCODE -ne 0) { throw 'Benchmark Nginx reload failed.' }
Start-Sleep -Seconds 3
$mapPath = Join-Path (Split-Path $run -Parent) 'process-map.json'
$oldMap = Get-Content -LiteralPath $mapPath -Raw | ConvertFrom-Json
$map = @{}
$oldMap.PSObject.Properties | Where-Object { $_.Name -notlike 'nginx-*' } | ForEach-Object { $map[$_.Name] = [int]$_.Value }
$masterPid = [int](Get-Content (Join-Path $run 'logs\nginx.pid'))
$map["nginx-$masterPid"] = $masterPid
Get-CimInstance Win32_Process -Filter "ParentProcessId=$masterPid" | Where-Object { $_.Name -eq 'nginx.exe' } | ForEach-Object { $map["nginx-$($_.ProcessId)"] = [int]$_.ProcessId }
$map | ConvertTo-Json | Set-Content -LiteralPath $mapPath -Encoding UTF8
Write-Output "Isolated Nginx mode: $Mode"
