"""Exercise real HTTPX transports against local TLS and CONNECT endpoints.

No vendor requests or credentials are used. The short-lived certificate is made
only for this test's loopback server, and HTTPX still verifies it normally.
"""
import asyncio
from contextlib import asynccontextmanager
import shutil
import ssl
import subprocess

import httpx
import pytest

from app.providers.http import HttpClientPool


@pytest.fixture
def local_tls(tmp_path, monkeypatch):
    for name in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
                 "http_proxy", "https_proxy", "all_proxy", "no_proxy",
                 "SSL_CERT_FILE", "SSL_CERT_DIR"):
        monkeypatch.delenv(name, raising=False)
    openssl = shutil.which("openssl")
    if openssl is None:
        pytest.skip("OpenSSL is required to generate the local TLS test certificate")
    config = tmp_path / "openssl.cnf"
    config.write_text("""[req]
prompt = no
distinguished_name = dn
x509_extensions = extensions
[dn]
CN = localhost
[extensions]
subjectAltName = DNS:localhost,IP:127.0.0.1
basicConstraints = critical,CA:TRUE
keyUsage = critical,digitalSignature,keyEncipherment,keyCertSign
extendedKeyUsage = serverAuth
""")
    cert, key = tmp_path / "server.pem", tmp_path / "server.key"
    subprocess.run([openssl, "req", "-new", "-x509", "-newkey", "rsa:2048",
                    "-sha256", "-nodes", "-days", "1", "-config", str(config),
                    "-out", str(cert), "-keyout", str(key)],
                   check=True, capture_output=True, timeout=15)
    server = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    server.load_cert_chain(cert, key)
    return cert, server


@asynccontextmanager
async def local_endpoint(server_context, proxy_scheme=None):
    requests, failures, tasks = [], [], set()

    async def handle(reader, writer):
        try:
            if proxy_scheme:
                connect = await reader.readuntil(b"\r\n\r\n")
                requests.append(connect.split(b"\r\n", 1)[0])
                writer.write(b"HTTP/1.1 200 Connection established\r\n\r\n")
                await writer.drain()
                await writer.start_tls(server_context, ssl_handshake_timeout=2)
            request = await reader.readuntil(b"\r\n\r\n")
            requests.append(request.split(b"\r\n", 1)[0])
            body = b'{"ok":true}'
            writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                         b"Content-Length: 11\r\nConnection: close\r\n\r\n" + body)
            await writer.drain()
        except Exception as error:
            failures.append(type(error).__name__)
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except (OSError, ssl.SSLError):
                pass

    def connected(reader, writer):
        task = asyncio.create_task(handle(reader, writer))
        tasks.add(task)
        task.add_done_callback(tasks.discard)

    tls = server_context if proxy_scheme != "http" else None
    server = await asyncio.start_server(connected, "127.0.0.1", 0, ssl=tls)
    port = server.sockets[0].getsockname()[1]
    try:
        yield port, requests, failures
    finally:
        server.close()
        await server.wait_closed()
        if tasks:
            await asyncio.wait_for(asyncio.gather(*tasks), timeout=3)


@pytest.mark.parametrize("proxy_scheme", [None, "http", "https"])
async def test_default_pool_verifies_real_tls_direct_and_through_proxy(local_tls, monkeypatch, proxy_scheme):
    cert, server_context = local_tls
    # Use the same public CA override supported by HTTPX's default verify=True.
    monkeypatch.setenv("SSL_CERT_FILE", str(cert))
    pool = HttpClientPool()
    try:
        async with local_endpoint(server_context, proxy_scheme) as (port, requests, failures):
            proxy = f"{proxy_scheme}://127.0.0.1:{port}" if proxy_scheme else None
            url = "https://localhost/healthz" if proxy_scheme else f"https://localhost:{port}/healthz"
            client = pool.client("test-vendor", timeout=2, proxy=proxy)
            response = await client.get(url, timeout=2)
            assert response.json() == {"ok": True}
            assert requests == ([b"CONNECT localhost:443 HTTP/1.1"] if proxy_scheme else []) + [
                b"GET /healthz HTTP/1.1"]
            assert failures == []
            assert pool.client("test-vendor", timeout=9, proxy=proxy) is client
    finally:
        await pool.aclose()


async def test_default_pool_rejects_an_untrusted_tls_certificate(local_tls):
    _, server_context = local_tls
    pool = HttpClientPool()
    try:
        async with local_endpoint(server_context) as (port, _, _):
            client = pool.client("test-vendor", timeout=2)
            with pytest.raises(httpx.ConnectError, match="CERTIFICATE_VERIFY_FAILED"):
                await client.get(f"https://localhost:{port}/healthz", timeout=2)
    finally:
        await pool.aclose()


async def test_pool_preserves_explicit_ssl_context_and_separates_it_from_default(local_tls):
    cert, _ = local_tls
    context = ssl.create_default_context(cafile=str(cert))
    pool = HttpClientPool()
    try:
        custom = pool.client("speech", timeout=2, verify=context)
        assert custom._transport._pool._ssl_context is context
        assert pool.client("speech", timeout=3, verify=context) is custom
        default = pool.client("speech", timeout=2)
        assert default is not custom
        assert isinstance(default._transport._pool._ssl_context, ssl.SSLContext)
        assert default._transport._pool._ssl_context.verify_mode == ssl.CERT_REQUIRED
        assert default._transport._pool._ssl_context.check_hostname
    finally:
        await pool.aclose()


async def test_pool_normalizes_legacy_default_sentinel_to_verified_client(local_tls):
    pool = HttpClientPool()
    try:
        default = pool.client("text", timeout=2)
        assert pool.client("text", timeout=2, verify=True) is default
        assert pool.client("text", timeout=2, verify=httpx.USE_CLIENT_DEFAULT) is default
        assert isinstance(default._transport._pool._ssl_context, ssl.SSLContext)
    finally:
        await pool.aclose()
