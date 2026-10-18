# Compatibility entry point. Generate nonverbal earcons; never synthesize speech.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
python (Join-Path $PSScriptRoot 'generate_android_cues.py')
if ($LASTEXITCODE -ne 0) { throw 'Earcon generation failed; install Python 3 and retry.' }
