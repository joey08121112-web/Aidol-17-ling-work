#!/usr/bin/env bash
# One-time infrastructure preparation. Run as root. No application secrets here.
set -euo pipefail
umask 077
test "$(id -u)" = 0 || { echo 'Run as root.' >&2; exit 1; }
base=/opt/aidol
tls="$base/shared/tls"
id aidol >/dev/null 2>&1 || useradd --system --home-dir "$base" --shell /usr/sbin/nologin aidol
install -d -m 755 "$base" "$base/releases" "$base/downloads"
install -d -o root -g aidol -m 750 "$base/shared"
install -d -m 700 "$tls" "$base/shared/backups"
install -d -o aidol -g aidol -m 700 "$base/shared/data"
if ! test -f "$tls/ca.pem"; then
    openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 3650 \
        -keyout "$tls/ca.key" -out "$tls/ca.pem" -subj '/CN=Aidol Internal Test CA/O=Aidol' \
        -addext 'basicConstraints=critical,CA:TRUE,pathlen:0' \
        -addext 'keyUsage=critical,keyCertSign,cRLSign' >/dev/null 2>&1
fi
if ! test -f "$tls/server.pem"; then
    openssl req -new -newkey rsa:2048 -nodes -keyout "$tls/server.key" \
        -out "$tls/server.csr" -subj '/CN=123.207.219.174/O=Aidol' >/dev/null 2>&1
    cat > "$tls/server.ext" <<'EOF'
subjectAltName=IP:123.207.219.174
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
EOF
    openssl x509 -req -in "$tls/server.csr" -CA "$tls/ca.pem" -CAkey "$tls/ca.key" \
        -CAcreateserial -out "$tls/server.pem" -days 365 -sha256 -extfile "$tls/server.ext" >/dev/null 2>&1
fi
chmod 600 "$tls"/*.key
openssl verify -CAfile "$tls/ca.pem" -verify_ip 123.207.219.174 "$tls/server.pem"
echo 'Infrastructure ready; public CA for the APK: /opt/aidol/shared/tls/ca.pem'
