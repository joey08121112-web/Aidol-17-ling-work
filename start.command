#!/bin/sh
# macOS Finder entry point. Keep all setup logic in scripts/start.sh.
set -eu
aidol_root=$(CDPATH= cd "$(dirname "$0")" && pwd)
exec sh "$aidol_root/start.sh" "$@"
