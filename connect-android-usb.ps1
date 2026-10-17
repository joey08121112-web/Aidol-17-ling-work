[CmdletBinding()]
param(
    [string]$Serial,
    [string]$Adb,
    [switch]$Check
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$aidolUsbPython = Join-Path $PSScriptRoot '.venv/Scripts/python.exe'
$aidolUsbArgs = @()
if (!(Test-Path -LiteralPath $aidolUsbPython)) {
    if (Get-Command py -ErrorAction SilentlyContinue) {
        $aidolUsbPython = 'py'
        $aidolUsbArgs += '-3'
    } elseif (Get-Command python -ErrorAction SilentlyContinue) {
        $aidolUsbPython = 'python'
    } else {
        throw 'Install Python 3.11+ or run start-android-backend.ps1 first.'
    }
}
$aidolUsbArgs += @('-m', 'scripts.android_usb')
if ($Serial) { $aidolUsbArgs += @('--serial', $Serial) }
if ($Adb) { $aidolUsbArgs += @('--adb', $Adb) }
if ($Check) { $aidolUsbArgs += '--check' }
& $aidolUsbPython @aidolUsbArgs
exit $LASTEXITCODE
