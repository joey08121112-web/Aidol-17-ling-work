#!/usr/bin/env bash
# Reuse the existing CA so already installed APKs continue to trust the server.
set -euo pipefail
umask 077
tls=/opt/aidol/shared/tls
test "$(id -u)" = 0
test -f "$tls/ca.key"
openssl req -new -key "$tls/server.key" -out "$tls/server.csr" -subj '/CN=123.207.219.174/O=Aidol'
openssl x509 -req -in "$tls/server.csr" -CA "$tls/ca.pem" -CAkey "$tls/ca.key" \
    -CAcreateserial -out "$tls/server.next.pem" -days 365 -sha256 -extfile "$tls/server.ext"
openssl verify -CAfile "$tls/ca.pem" -verify_ip 123.207.219.174 "$tls/server.next.pem"
cp -p "$tls/server.pem" "$tls/server.previous.pem"
mv "$tls/server.next.pem" "$tls/server.pem"
nginx -t
systemctl reload nginx
openssl x509 -in "$tls/server.pem" -noout -dates
