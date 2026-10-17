#!/usr/bin/env bash
# Run as root on Ubuntu, from an uploaded release directory. Never print .env.
set -euo pipefail
umask 027
base=/opt/aidol
source_dir=$(cd "${1:-.}" && pwd)
test "$(id -u)" = 0 || { echo 'Run as root.' >&2; exit 1; }
test -f "$source_dir/app/main.py"
test -f "$source_dir/requirements.txt"
test -f "$base/shared/.env" || { echo 'Upload /opt/aidol/shared/.env first (mode 600).' >&2; exit 1; }
test -f "$base/shared/tls/server.pem" || { echo 'Run deploy/prepare-server.sh first.' >&2; exit 1; }
command -v nginx >/dev/null
if ! python3 -m venv --help >/dev/null 2>&1 || ! dpkg-query -W -f='${Status}' python3-venv 2>/dev/null | grep -q 'install ok installed'; then
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3-venv
fi
id aidol >/dev/null 2>&1 || useradd --system --home-dir "$base" --shell /usr/sbin/nologin aidol
install -d -m 755 "$base" "$base/releases" "$base/downloads"
install -d -o root -g aidol -m 750 "$base/shared"
install -d -o aidol -g aidol -m 700 "$base/shared/data"
chown root:aidol "$base/shared/.env"
chmod 640 "$base/shared/.env"
release="$base/releases/$(date -u +%Y%m%dT%H%M%SZ)-$$"
install -d -m 755 "$release"
cp -a "$source_dir/app" "$source_dir/requirements.txt" "$release/"
find "$release" -type d -name __pycache__ -prune -exec rm -rf -- {} +
python3 -m venv "$release/.venv"
"$release/.venv/bin/python" -m pip install --disable-pip-version-check -q -r "$release/requirements.txt"
"$release/.venv/bin/python" -m compileall -q "$release/app"
chmod -R go+rX "$release"
chown -R root:root "$release"

# Validate a temporary API instance without calling any external provider.
smoke_data=$(mktemp -d "$base/shared/data/install-check.XXXXXX")
chown aidol:aidol "$smoke_data"
cleanup() { test -z "${smoke_pid:-}" || kill "$smoke_pid" 2>/dev/null || true; rm -rf -- "$smoke_data"; }
trap cleanup EXIT
cd "$release"
runuser -u aidol -- env AIDOL_DATA_DIR="$smoke_data/hosted" \
    "$release/.venv/bin/python" -m uvicorn app.main:create_hosted_app --factory --host 127.0.0.1 --port 18108 --no-access-log >"$smoke_data/check.log" 2>&1 &
smoke_pid=$!
ready=false
for attempt in $(seq 1 30); do
    if curl --fail --silent http://127.0.0.1:18108/healthz >/dev/null; then ready=true; break; fi
    sleep 1
done
if [ "$ready" != true ]; then echo 'Release health check failed.' >&2; cat "$smoke_data/check.log" >&2; exit 1; fi
kill "$smoke_pid" 2>/dev/null || true
wait "$smoke_pid" 2>/dev/null || true
smoke_pid=

# SQLite backup API captures WAL consistently; keep backups outside releases.
if [ -d "$base/shared/data/hosted" ]; then
    backup_dir="$base/shared/backups/$(basename "$release")"
    install -d -m 700 "$backup_dir"
    python3 "$source_dir/deploy/backup.py" "$base/shared/data" "$backup_dir"
fi
previous=$(readlink -f "$base/current" 2>/dev/null || true)
had_nginx=false
if [ -f /etc/nginx/sites-available/aidol ]; then
    had_nginx=true
    cp -a /etc/nginx/sites-available/aidol "$base/shared/nginx-aidol.previous"
fi
install -m 644 "$source_dir/deploy/nginx-aidol.conf" /etc/nginx/sites-available/aidol
ln -sfn /etc/nginx/sites-available/aidol /etc/nginx/sites-enabled/aidol
if ! nginx -t; then
    if [ "$had_nginx" = true ]; then
        cp -a "$base/shared/nginx-aidol.previous" /etc/nginx/sites-available/aidol
    else
        rm -f /etc/nginx/sites-enabled/aidol /etc/nginx/sites-available/aidol
    fi
    echo 'Invalid nginx configuration; restored previous configuration.' >&2
    exit 1
fi
test -z "$previous" || ln -sfn "$previous" "$base/previous"
ln -sfn "$release" "$base/current.next"
mv -Tf "$base/current.next" "$base/current"
install -m 644 "$source_dir/deploy/aidol.service" /etc/systemd/system/aidol.service
systemctl daemon-reload
systemctl enable aidol >/dev/null
systemctl restart aidol
ready=false
for attempt in $(seq 1 30); do
    if curl --fail --silent http://127.0.0.1:8108/healthz >/dev/null; then ready=true; break; fi
    sleep 1
done
if [ "$ready" != true ]; then
    echo 'Service health check failed; restoring previous release if present.' >&2
    if [ -n "$previous" ]; then ln -sfn "$previous" "$base/current"; systemctl restart aidol; fi
    exit 1
fi
systemctl reload nginx
ready=false
for attempt in $(seq 1 15); do
    if curl --fail --silent --max-time 5 --cacert "$base/shared/tls/ca.pem" --resolve 123.207.219.174:7776:127.0.0.1 https://123.207.219.174:7776/healthz; then ready=true; break; fi
    sleep 1
done
if [ "$ready" != true ]; then echo 'nginx TLS health check failed; inspect nginx logs.' >&2; exit 1; fi
echo
echo "Installed $release"
