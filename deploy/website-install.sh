#!/usr/bin/env bash
# Publish only the public website. Usage: bash website-install.sh /path/to/upload
# The upload directory must contain website/ and deploy/website-nginx.conf.
set -Eeuo pipefail
umask 022

[[ ${EUID} -eq 0 ]] || { echo "Run as root." >&2; exit 1; }
[[ $# -eq 1 ]] || { echo "Usage: $0 /path/to/upload" >&2; exit 1; }
upload=$(realpath -- "$1")
source_dir="$upload/website"
config_source="$upload/deploy/website-nginx.conf"
base=/opt/aidol/website
config=/etc/nginx/sites-available/aidol-website
enabled=/etc/nginx/sites-enabled/aidol-website
marker='# Aidol public product website. Managed by deploy/website-install.sh.'

for command in nginx curl sha256sum install find cp readlink; do
    command -v "$command" >/dev/null || { echo "Missing command: $command" >&2; exit 1; }
done
test -f "$source_dir/index.html"
test -f "$config_source"
grep -Fxq "$marker" "$config_source"
test -r /opt/aidol/downloads/aidol-android.apk
test -r /opt/aidol/downloads/aidol-android.apk.sha256
(cd /opt/aidol/downloads && sha256sum --check aidol-android.apk.sha256)

# Accept an explicit, public static directory, with no hidden files or symlinks.
if find "$source_dir" -mindepth 1 \( -name '.*' -o -type l \) -print -quit | grep -q .; then
    echo "The public website directory must not contain hidden files or symlinks." >&2
    exit 1
fi
if [[ -e "$config" ]] && ! grep -Fxq "$marker" "$config"; then
    echo "Refusing to replace an unmanaged aidol-website configuration." >&2
    exit 1
fi
if [[ -e "$enabled" || -L "$enabled" ]]; then
    [[ -L "$enabled" && $(readlink -f "$enabled") == "$config" ]] || {
        echo "Refusing to replace an unrelated enabled site." >&2; exit 1;
    }
fi

install -d -m 755 "$base/releases" "$base/config-backups"
release=$(mktemp -d "$base/releases/$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")
chmod 755 "$release"
cp -a "$source_dir/." "$release/"
find "$release" -type d -exec chmod 755 {} +
find "$release" -type f -exec chmod 644 {} +
chown -R root:root "$release"

previous=$(readlink -f "$base/current" 2>/dev/null || true)
had_config=0
had_enabled=0
[[ -e "$config" ]] && had_config=1
[[ -L "$enabled" ]] && had_enabled=1
backup="$base/config-backups/$(basename "$release").conf"
[[ $had_config -eq 0 ]] || cp -a "$config" "$backup"

rollback() {
    echo "Website validation failed; restoring the previous website configuration." >&2
    if [[ -n "$previous" && -d "$previous" ]]; then
        ln -sfn "$previous" "$base/current.next"
        mv -Tf "$base/current.next" "$base/current"
    else
        rm -f -- "$base/current"
    fi
    if [[ $had_config -eq 1 ]]; then cp -a "$backup" "$config"; else rm -f -- "$config"; fi
    [[ $had_enabled -eq 1 ]] || rm -f -- "$enabled"
    nginx -t && systemctl reload nginx || true
}
trap rollback ERR

install -m 644 "$config_source" "$config"
ln -sfn "$config" "$enabled"
ln -sfn "$release" "$base/current.next"
mv -Tf "$base/current.next" "$base/current"
nginx -t
systemctl reload nginx
curl --fail --silent --show-error http://127.0.0.1:6655/ -o /dev/null
curl --fail --silent --show-error --head http://127.0.0.1:6655/downloads/aidol-android.apk
trap - ERR

if [[ -n "$previous" && -d "$previous" ]]; then
    ln -sfn "$previous" "$base/previous"
fi
if command -v ufw >/dev/null && ufw status | grep -q '^Status: active'; then
    ufw allow 6655/tcp comment 'Aidol product website'
fi

echo "Website release: $release"
echo 'Public URL: http://123.207.219.174:6655/'
echo 'Verify external access separately; a cloud security group may also filter port 6655.'
