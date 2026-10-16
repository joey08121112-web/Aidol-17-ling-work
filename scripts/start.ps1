param([switch]$Dev, [switch]$Check, [switch]$InstallOnly)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$aidolPython = $null
$aidolPythonArgs = @()
if (Test-Path .venv/Scripts/python.exe) {
    $aidolPython = (Resolve-Path .venv/Scripts/python.exe).Path
} elseif (Test-Path .venv) {
    throw 'Existing .venv is incompatible or incomplete. Move it aside and retry; do not copy virtual environments between operating systems.'
} else {
    foreach ($aidolCandidate in @('py', 'python', 'python3')) {
        if (!(Get-Command $aidolCandidate -ErrorAction SilentlyContinue)) { continue }
        $aidolCandidateArgs = if ($aidolCandidate -eq 'py') { @('-3') } else { @() }
        & $aidolCandidate @aidolCandidateArgs -c 'import sys; sys.exit(sys.version_info < (3, 11))'
        if ($LASTEXITCODE -eq 0) {
            $aidolPython = $aidolCandidate
            $aidolPythonArgs = $aidolCandidateArgs
            break
        }
    }
}
if (!$aidolPython) { throw 'Install Python 3.11+ and add it to PATH, then retry.' }
& $aidolPython @aidolPythonArgs -c 'import sys; print(sys.version); sys.exit(sys.version_info < (3, 11))'
if ($LASTEXITCODE -ne 0) { throw 'Python 3.11+ is required.' }
if ($Check) {
    Write-Host 'Aidol startup check passed. No files changed; no dependencies installed.'
    exit 0
}
if (!(Test-Path .venv/Scripts/python.exe)) {
    & $aidolPython @aidolPythonArgs -m venv .venv
    if ($LASTEXITCODE -ne 0) { throw 'Could not create .venv. Check your Python installation.' }
}
if (!(Test-Path .env)) { Copy-Item .env.example .env }
$requirements = if ($Dev) { 'requirements-dev.txt' } else { 'requirements.txt' }
& ./.venv/Scripts/python.exe -m pip install -r $requirements
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed. Check network/proxy and Python compatibility.' }
if ($InstallOnly) {
    Write-Host 'Aidol dependencies installed. Your existing .env was preserved.'
    exit 0
}
Write-Host 'Starting Aidol. Open the local URL printed below. Press Ctrl+C to stop.'
& ./.venv/Scripts/python.exe run.py
exit $LASTEXITCODE
