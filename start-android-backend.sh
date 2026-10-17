#!/usr/bin/env sh
# macOS/Linux local backend for the same Android APK/API.
set -eu
aidol_root=$(CDPATH= cd "$(dirname "$0")" && pwd)
cd "$aidol_root"
aidol_dev=''
aidol_check=''
aidol_install_only=0
aidol_reload=''
while [ "$#" -gt 0 ]; do
    case "$1" in
        --dev) aidol_dev=--dev ;;
        --check) aidol_check=--check ;;
        --install-only) aidol_install_only=1 ;;
        --reload) aidol_reload=--reload ;;
        *) printf 'Unknown option: %s\nUse --dev, --check, --install-only or --reload.\n' "$1" >&2; exit 2 ;;
    esac
    shift
done
# These optional arguments are fixed flags, never user-controlled shell text.
sh "$aidol_root/scripts/start.sh" ${aidol_dev:+"$aidol_dev"} ${aidol_check:+"$aidol_check"} --install-only
if [ -n "$aidol_check" ]; then
    printf 'Local Android API / USB server: http://127.0.0.1:8108 (USB requires adb reverse tcp:8108 tcp:8108)\nIsolated local accounts and data: %s/data/android-local\n' "$aidol_root"
    exit 0
fi
[ "$aidol_install_only" -eq 0 ] || exit 0
exec "$aidol_root/.venv/bin/python" -m scripts.android_backend ${aidol_reload:+"$aidol_reload"}
