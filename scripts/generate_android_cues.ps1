# Run with Windows PowerShell 5.1 and the installed Microsoft Huihui Desktop voice.
# These are fixed product assets, not user recordings. Normal Android builds use
# the checked-in WAVs and do not need Windows or a speech engine.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$aidolRoot = Split-Path -Parent $PSScriptRoot
$aidolCues = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'android_cues.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$aidolOutput = Join-Path $aidolRoot 'android-app/app/src/main/res/raw'
New-Item -ItemType Directory -Path $aidolOutput -Force | Out-Null
$aidolSynth = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $aidolSynth.SelectVoice($aidolCues.voice)
    $aidolSynth.Rate = 1
    $aidolSynth.Volume = 100
    $aidolFormat = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(
        $aidolCues.sample_rate,
        [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
        [System.Speech.AudioFormat.AudioChannel]::Mono)
    foreach ($aidolPhrase in $aidolCues.phrases.PSObject.Properties) {
        if ($aidolPhrase.Name -notmatch '^cue_[a-z]+$') { throw 'Invalid cue resource name' }
        $aidolPath = Join-Path $aidolOutput ($aidolPhrase.Name + '.wav')
        $aidolSynth.SetOutputToWaveFile($aidolPath, $aidolFormat)
        $aidolSynth.Speak([string]$aidolPhrase.Value)
        $aidolSynth.SetOutputToNull()
        Write-Host ('Generated fixed product cue: ' + $aidolPhrase.Name)
    }
} finally {
    $aidolSynth.Dispose()
}
