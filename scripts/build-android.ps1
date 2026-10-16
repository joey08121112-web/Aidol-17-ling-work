param([string]$JavaHome = $env:JAVA_HOME, [string]$AndroidHome = $env:ANDROID_HOME,
      [string]$KeyStore = $env:AIDOL_KEYSTORE, [string]$PasswordFile = '', [switch]$DebugBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$JavaHome -or !$AndroidHome) { throw 'Set JAVA_HOME (JDK17) and ANDROID_HOME (SDK35).' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidHome
$variant = if ($DebugBuild) { 'Debug' } else { 'Release' }
if (!$DebugBuild) {
    if (!$KeyStore -or !(Test-Path -LiteralPath $KeyStore)) { throw 'Provide the existing release keystore; keep it for future upgrades.' }
    $env:AIDOL_KEYSTORE = (Resolve-Path -LiteralPath $KeyStore).Path
    if ($PasswordFile) { $env:AIDOL_KEYSTORE_PASSWORD = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $PasswordFile).Path).Trim() }
    if (!$env:AIDOL_KEYSTORE_PASSWORD) { throw 'Provide AIDOL_KEYSTORE_PASSWORD or -PasswordFile.' }
}
try {
    Push-Location (Join-Path $projectRoot 'android-app')
    & .\gradlew.bat "assemble$variant" testDebugUnitTest lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
    $apk = Join-Path $projectRoot "android-app/app/build/outputs/apk/$($variant.ToLower())/app-$($variant.ToLower()).apk"
    Copy-Item -LiteralPath $apk -Destination (Join-Path $projectRoot 'android-app/aidol-android.apk')
    Get-FileHash -LiteralPath (Join-Path $projectRoot 'android-app/aidol-android.apk') -Algorithm SHA256
} finally {
    Pop-Location
    Remove-Item Env:AIDOL_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
}
