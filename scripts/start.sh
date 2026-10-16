#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
aidol_dev=0
aidol_check=0
aidol_install_only=0
for aidol_arg in "$@"; do
    case "$aidol_arg" in
        --dev) aidol_dev=1 ;;
        --check) aidol_check=1 ;;
        --install-only) aidol_install_only=1 ;;
        *) printf 'Unknown option: %s\nUse --dev, --check or --install-only.\n' "$aidol_arg" >&2; exit 2 ;;
    esac
done
if [ -x .venv/bin/python ]; then
    aidol_python=.venv/bin/python
elif [ -e .venv ]; then
    printf '%s\n' 'Existing .venv is incompatible or incomplete. Move it aside and retry; never copy .venv between operating systems.' >&2
    exit 1
else
    aidol_python=''
    for aidol_candidate in python3 python; do
        if command -v "$aidol_candidate" >/dev/null 2>&1 && "$aidol_candidate" -c 'import sys; sys.exit(sys.version_info < (3, 11))' 2>/dev/null; then
            aidol_python=$aidol_candidate
            break
        fi
    done
    if [ -z "$aidol_python" ]; then
        printf '%s\n' 'Install Python 3.11+ first (python.org on macOS, your package manager on Linux).' >&2
        exit 1
    fi
fi
"$aidol_python" -c 'import sys; sys.exit("Python 3.11+ is required.") if sys.version_info < (3, 11) else print("Python", sys.version.split()[0])'
if [ "$aidol_check" -eq 1 ]; then
    printf '%s\n' 'Aidol startup check passed. No files changed; no dependencies installed.'
    exit 0
fi
if [ ! -x .venv/bin/python ]; then
    "$aidol_python" -m venv .venv || {
        printf '%s\n' 'Cannot create .venv. On Debian/Ubuntu, install the python3-venv package for your Python version.' >&2
        exit 1
    }
fi
[ -f .env ] || cp .env.example .env
aidol_requirements=requirements.txt
[ "$aidol_dev" -eq 0 ] || aidol_requirements=requirements-dev.txt
.venv/bin/python -m pip install -r "$aidol_requirements"
if [ "$aidol_install_only" -eq 1 ]; then
    printf '%s\n' 'Aidol dependencies installed. Your existing .env was preserved.'
    exit 0
fi
printf '%s\n' 'Starting Aidol. Open the local URL printed below. Press Ctrl+C to stop.'
exec .venv/bin/python run.py
