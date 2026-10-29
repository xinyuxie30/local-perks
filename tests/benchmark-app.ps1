param([switch]$Baseline)
$ErrorActionPreference = 'Stop'
# Reuse only the app launcher, without starting another dependency instance.
if ($Baseline) { & (Join-Path $PSScriptRoot 'benchmark-start.ps1') -BaselineOnly }
else { & (Join-Path $PSScriptRoot 'benchmark-start.ps1') -AppOnly }
