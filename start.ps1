param([switch]$Dev, [switch]$Check, [switch]$InstallOnly, [switch]$Reload)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'start-android-backend.ps1') -Dev:$Dev -Check:$Check -InstallOnly:$InstallOnly -Reload:$Reload
exit $LASTEXITCODE
