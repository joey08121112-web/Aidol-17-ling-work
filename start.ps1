param([switch]$Dev, [switch]$Check, [switch]$InstallOnly)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'scripts/start.ps1') -Dev:$Dev -Check:$Check -InstallOnly:$InstallOnly
exit $LASTEXITCODE
