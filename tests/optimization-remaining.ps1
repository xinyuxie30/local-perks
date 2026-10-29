$ErrorActionPreference='Stop'
$python='C:\Users\xinyuxie\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$project=Split-Path $PSScriptRoot -Parent
$out=Join-Path $project 'output/benchmarks/localperks-optimization-2026-10-28'
if (-not (Test-Path "$out/beforesteadyretry/background-final.json")) { throw 'Valid baseline must finish first' }
$previous='afterbatch'
foreach ($stage in @('afterbatchretry','afterbatchsteady','beforeburst','afterbatchburst')) {
    & "$PSScriptRoot/optimization-environment.ps1" -StopTag "20261028-$previous" -Stage $stage
    if ($LASTEXITCODE -ne 0) { throw "Environment failed: $stage" }
    $phase=if ($stage -eq 'afterbatchretry') { 'formal' } elseif ($stage.EndsWith('steady')) { 'steady' } else { 'burst' }
    & $python "$PSScriptRoot/optimization-suite.py" $stage $phase
    if ($LASTEXITCODE -ne 0) { throw "Benchmark failed: $stage; retained all evidence" }
    if ($stage -eq 'afterbatchretry') {
        & $python "$PSScriptRoot/keepalive-probe.py" afterbatchretry
        if ($LASTEXITCODE -ne 0) { throw 'After keepalive probe failed' }
    }
    $previous=$stage
}
& $python "$PSScriptRoot/optimization-archive.py"
if ($LASTEXITCODE -ne 0) { throw 'Archiving failed; workloads retained' }
& $python "$PSScriptRoot/optimization-report.py"
if ($LASTEXITCODE -ne 0) { throw 'Report audit failed; workloads retained' }
'All matched workloads and raw-data audits completed. Isolated afterbatchburst environment remains running.'
