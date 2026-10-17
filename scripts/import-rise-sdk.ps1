param([Parameter(Mandatory=$true)][string]$DemoZip)
$ErrorActionPreference = 'Stop'
$expectedHash = 'e074506fd3a46346272fdd2ab632a59b4cef2597f4cb37fd160737eb536e2b2c'
$repositoryPath = Split-Path -Parent $PSScriptRoot
$libraryPath = Join-Path $repositoryPath 'android-app/app/libs'
$destinationPath = Join-Path $libraryPath 'headsetSdk-release-6.7.aar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $DemoZip).Path)
try {
    $entry = $archive.GetEntry('demo_6_7/app/libs/headsetSdk-release-6.7.aar')
    if ($null -eq $entry) { throw 'The archive does not contain the expected SDK 6.7 AAR.' }
    $memory = [IO.MemoryStream]::new()
    $inputStream = $entry.Open()
    try { $inputStream.CopyTo($memory) } finally { $inputStream.Dispose() }
    $bytes = $memory.ToArray()
    $memory.Dispose()
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $actualHash = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
    if ($actualHash -ne $expectedHash) { throw 'SDK checksum mismatch. No project file has been changed.' }
    New-Item -ItemType Directory -Path $libraryPath -Force | Out-Null
    [IO.File]::WriteAllBytes($destinationPath, $bytes)
    Write-Output "Imported SDK 6.7: $destinationPath"
    Write-Output "SHA-256: $actualHash"
} finally { $archive.Dispose() }
