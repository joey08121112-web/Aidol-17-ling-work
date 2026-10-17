#!/usr/bin/env sh
# Linux and macOS terminal entry point; safe from any working directory.
set -eu
aidol_root=$(CDPATH= cd "$(dirname "$0")" && pwd)
exec sh "$aidol_root/start-android-backend.sh" "$@"
