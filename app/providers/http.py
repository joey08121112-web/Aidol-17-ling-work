"""Shared httpx clients scoped to a configuration object, not the process.

Every service built from the same config (app ``Config``, ``VolcSpeechConfig``
or ``QwenWorkAsrConfig``) draws its clients from one pool, so repeated calls
reuse keep-alive connections instead of paying TCP+TLS per request. A pool is
created per config instance, keeping separate app instances and offline tests
isolated from each other.

Clients are constructed lazily through ``httpx.AsyncClient(...)`` with keyword
arguments only, because offline tests substitute that constructor with a mock
transport. Call sites pass ``timeout`` per request: one vendor client serves
several deadlines (conversation/memory 15s, meeting minutes 120s, ...).
``aclose()`` releases every client; the app shutdown wiring lives with the
owner of app/main.py, which this provider layer must not import.
"""
import httpx


class HttpClientPool:
    """One shared AsyncClient per (vendor, proxy, SSL context) combination."""

    def __init__(self):
        self._clients: dict[tuple, httpx.AsyncClient] = {}

    def client(self, vendor, *, timeout, proxy=None, verify=True):
        # USE_CLIENT_DEFAULT belongs to per-request options, not AsyncClient's
        # TLS constructor. Passing it through stores it as an SSLContext and
        # crashes when HTTPS (including CONNECT proxies) configures ALPN.
        if verify is httpx.USE_CLIENT_DEFAULT:
            verify = True
        key = (vendor, proxy, verify)
        client = self._clients.get(key)
        if client is None:
            client = httpx.AsyncClient(timeout=timeout, proxy=proxy, verify=verify)
            self._clients[key] = client
        return client

    async def aclose(self):
        clients = list(self._clients.values())
        self._clients.clear()
        for client in clients:
            await client.aclose()
