#!/usr/bin/env sh
# macOS/Linux: all options pass directly to the standard-library USB helper.
set -eu
aidol_usb_root=$(CDPATH= cd "$(dirname "$0")" && pwd)
cd "$aidol_usb_root"
if [ -x "$aidol_usb_root/.venv/bin/python" ]; then
    aidol_usb_python="$aidol_usb_root/.venv/bin/python"
elif command -v python3 >/dev/null 2>&1; then
    aidol_usb_python=python3
else
    printf '%s\n' 'Install Python 3.11+ or run start-android-backend.sh first.' >&2
    exit 1
fi
exec "$aidol_usb_python" -m scripts.android_usb "$@"
