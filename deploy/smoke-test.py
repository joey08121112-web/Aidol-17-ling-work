"""Public hosted API acceptance; creates/deletes two synthetic users, no AI calls."""
import argparse
import http.client
import json
from pathlib import Path
import secrets
import ssl
import socket
import urllib.error
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--url", default="https://123.207.219.174:7776")
parser.add_argument("--ca", type=Path, required=True)
parser.add_argument("--connect-ip", help="Connect to this IP but still verify the URL's TLS hostname (for server loopback).")
args = parser.parse_args()
context = ssl.create_default_context(cafile=str(args.ca))


class ResolvedHTTPSConnection(http.client.HTTPSConnection):
    def connect(self):
        if args.connect_ip:
            self._create_connection = lambda address, *positional, **named: socket.create_connection((args.connect_ip, address[1]), *positional, **named)
        super().connect()


class ResolvedHTTPSHandler(urllib.request.HTTPSHandler):
    def https_open(self, request):
        return self.do_open(ResolvedHTTPSConnection, request, context=context)


opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), ResolvedHTTPSHandler())
passed = []
users = []


def call(method, path, expected=200, token=None, body=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(args.url.rstrip("/") + path,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers=headers, method=method)
    try:
        response = opener.open(request, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status = response.status
        payload = response.read()
    assert status == expected, f"{method} {path}: expected {expected}, received {status}"
    return json.loads(payload) if payload else {}


try:
    assert call("GET", "/healthz")["ok"] is True
    passed.append("HTTPS certificate and health")
    call("GET", "/v1/state", 401)
    call("GET", "/api/state", 404)
    passed.append("unauthenticated and legacy API protection")
    for i in range(2):
        user = {"username": "smoke_" + secrets.token_hex(5), "password": secrets.token_urlsafe(20)}
        result = call("POST", "/auth/register", 201, body=user)
        user.update(token=result["token"], recovery_code=result["recovery_code"])
        users.append(user)
    first, second = users
    call("POST", "/auth/register", 409, body={"username": first["username"].upper(), "password": first["password"]})
    call("POST", "/auth/login", 401, body={"username": first["username"], "password": "wrong-test-password"})
    passed.append("unique case-insensitive account IDs and password rejection")
    settings = call("GET", "/v1/state", token=first["token"])["settings"]
    settings.update(name="Synthetic acceptance user", preferences="smoke-test-private-value", auto_mode=False, mode="life")
    call("PUT", "/v1/settings", token=first["token"], body=settings)
    assert call("GET", "/v1/state", token=first["token"])["settings"]["preferences"] == settings["preferences"]
    assert call("GET", "/v1/state", token=second["token"])["settings"]["preferences"] != settings["preferences"]
    passed.append("per-account settings persistence and isolation")
    old_token = first["token"]
    call("POST", "/auth/logout", token=old_token)
    call("GET", "/auth/me", 401, token=old_token)
    first["token"] = call("POST", "/auth/login", body={k: first[k] for k in ("username", "password")})["token"]
    passed.append("logout revokes session and login restores account")
    old_token, new_password = first["token"], secrets.token_urlsafe(20)
    changed = call("PUT", "/auth/password", token=old_token, body={"old_password": first["password"], "new_password": new_password})
    first.update(token=changed["token"], password=new_password)
    call("GET", "/auth/me", 401, token=old_token)
    old_token, new_password = first["token"], secrets.token_urlsafe(20)
    recovered = call("POST", "/auth/recover", body={"username": first["username"], "password": new_password, "recovery_code": first["recovery_code"]})
    first.update(token=recovered["token"], password=new_password)
    call("GET", "/auth/me", 401, token=old_token)
    passed.append("password change and recovery revoke prior sessions")
    call("GET", "/v1/export", token=first["token"])
    call("DELETE", "/v1/data", token=first["token"], body={"password": first["password"]})
    passed.append("private data export and clear")
finally:
    cleanup_errors = []
    for user in users:
        try:
            call("DELETE", "/auth/account", token=user["token"], body={"password": user["password"]})
            call("GET", "/auth/me", 401, token=user["token"])
        except Exception as error:
            cleanup_errors.append(type(error).__name__)
    if cleanup_errors:
        raise RuntimeError("Synthetic user cleanup requires attention: " + ", ".join(cleanup_errors))

passed.append("account deletion and session revocation; synthetic users removed")
for item in passed:
    print("PASS " + item)
print(f"{len(passed)} checks passed; no AI provider endpoints called.")
