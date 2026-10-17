[CmdletBinding()]
param(
    [switch]$Dev,
    [switch]$Check,
    [switch]$InstallOnly,
    [switch]$Reload
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
# Reuse the shared Python dependency setup before starting the account API.
& (Join-Path $PSScriptRoot 'scripts/start.ps1') -Dev:$Dev -Check:$Check -InstallOnly
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($Check) {
    Write-Host 'Local Android API: http://127.0.0.1:8108'
    Write-Host 'Android USB server: http://127.0.0.1:8108 (requires adb reverse tcp:8108 tcp:8108)'
    Write-Host "Isolated local accounts and data: $(Join-Path $PSScriptRoot 'data/android-local')"
    exit 0
}
if ($InstallOnly) { exit 0 }
$aidolBackendArgs = @('-m', 'scripts.android_backend')
if ($Reload) { $aidolBackendArgs += '--reload' }
& (Join-Path $PSScriptRoot '.venv/Scripts/python.exe') @aidolBackendArgs
exit $LASTEXITCODE
