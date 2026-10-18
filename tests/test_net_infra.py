"""W3 network infrastructure guards: shared clients, adcode cache, duplex turns.

All HTTP is offline: the httpx.AsyncClient constructor is substituted with a
mock transport, and construction counts prove the second call reuses the pool.
"""
import asyncio
import base64
import json

import httpx
import pytest

from app.config import Config
from app.domain import LocationInput
from app.providers.http import HttpClientPool
from app.providers.qwen_realtime import QwenDuplexSession, QwenRealtimeConfig
from app.services.assistant_tools import AssistantTools
from app.services.conversation import ConversationService
from app.services.maps import MapService
from app.services.recall import RecallService


def counting_client(monkeypatch, handler):
    """Replace the httpx.AsyncClient constructor; return the creation log."""
    real = httpx.AsyncClient
    created = []

    def factory(**kwargs):
        created.append(kwargs)
        return real(transport=httpx.MockTransport(handler))

    monkeypatch.setattr(httpx, "AsyncClient", factory)
    return created


async def test_pool_reuses_per_vendor_and_rebuilds_after_close():
    pool = HttpClientPool()
    amap = pool.client("amap", timeout=10)
    assert pool.client("amap", timeout=10) is amap
    assert pool.client("text", timeout=15) is not amap
    assert pool.client("amap", timeout=10, proxy="http://127.0.0.1:9") is not amap
    await pool.aclose()
    assert amap.is_closed
    assert pool.client("amap", timeout=10) is not amap


def test_pools_are_per_config_not_process_global():
    first, second = Config(), Config()
    assert first.http_pool is not second.http_pool
    assert first.http_pool.client("text", timeout=15) is not second.http_pool.client("text", timeout=15)


async def test_second_map_call_creates_no_new_client(config, monkeypatch):
    config.amap_key = "offline-map-key"
    hits = []

    def handler(request):
        hits.append(request.url.path)
        return httpx.Response(200, json={"status": "1", "pois": []})

    created = counting_client(monkeypatch, handler)
    first = await MapService(config).search("公园")
    second = await MapService(config).search("饭馆")
    assert first["available"] and second["available"]
    assert len(created) == 1 and hits.count("/v3/place/text") == 2


async def test_text_vendor_is_shared_across_services(config, monkeypatch):
    config.text_key, config.text_model = "offline-key", "test-model"
    hits = []

    def handler(request):
        hits.append(request.url.path)
        return httpx.Response(200, json={"choices": [{"message": {"content": "好的"}}]})

    created = counting_client(monkeypatch, handler)
    assert await ConversationService(config).reply("你好", "介绍") == "好的"
    answer = await RecallService(config).answer("我的饮食偏好是什么", [
        {"id": "m1", "content": "我喜欢清淡", "kind": "preference", "created_at": ""}], [])
    assert answer["insufficient"] is False
    assert len(created) == 1 and len(hits) == 2


async def test_vendors_keep_separate_clients(config, monkeypatch):
    config.amap_key, config.bocha_key = "offline-map-key", "offline-bocha-key"
    requests = []

    def handler(request):
        requests.append(request.url.host)
        if request.url.host == "restapi.amap.com":
            return httpx.Response(200, json={"status": "1", "pois": []})
        return httpx.Response(200, json={"code": 200, "data": {"webPages": {"value": [
            {"name": "公开来源", "url": "https://example.com", "summary": "x"}]}}})

    from app.providers.bocha import BochaSearch

    created = counting_client(monkeypatch, handler)
    assert (await MapService(config).search("公园"))["available"]
    assert (await BochaSearch(config).search("公开新闻"))["available"]
    assert len(created) == 2 and set(requests) == {"restapi.amap.com", "api.bochaai.com"}


def amap_weather_handler(paths):
    def handler(request):
        paths.append(request.url.path)
        if request.url.path.endswith("/geo"):
            return httpx.Response(200, json={"status": "1", "geocodes": [
                {"formatted_address": "北京市", "location": "116.40,39.90", "adcode": "110000", "city": "北京市"}]})
        if request.url.path.endswith("/regeo"):
            return httpx.Response(200, json={"status": "1", "regeocode": {"formatted_address": "某路口",
                "addressComponent": {"adcode": "310115", "city": "上海市", "citycode": "021"}}})
        if request.url.path.endswith("/convert"):
            return httpx.Response(200, json={"status": "1", "locations": "121.51,31.21"})
        if request.url.params.get("extensions") == "all":
            return httpx.Response(200, json={"status": "1", "forecasts": [
                {"city": "北京市", "adcode": "110000", "reporttime": "2026-10-18 10:00:00", "casts": [
                    {"date": "2026-10-18", "week": "6", "dayweather": "晴", "nightweather": "多云",
                     "daytemp": "20", "nighttemp": "10", "daywind": "东", "daypower": "2"}]}]})
        return httpx.Response(200, json={"status": "1", "lives": [
            {"city": "北京市", "adcode": "110000", "weather": "晴", "temperature": "20", "humidity": "40",
             "winddirection": "东", "windpower": "2", "reporttime": "2026-10-18 10:00:00"}]})
    return handler


async def test_weather_city_adcode_is_cached_per_account(config, monkeypatch):
    config.amap_key = "offline-map-key"
    paths = []
    counting_client(monkeypatch, amap_weather_handler(paths))
    service = MapService(config)
    assert (await service.weather_for("北京市"))["available"]
    assert paths.count("/v3/geocode/geo") == 1 and paths.count("/v3/weather/weatherInfo") == 1
    paths.clear()
    assert (await service.weather_for("北京市", forecast=True))["available"]
    assert paths == ["/v3/weather/weatherInfo"]


async def test_weather_position_rounds_and_caches(config, monkeypatch):
    config.amap_key = "offline-map-key"
    paths = []
    counting_client(monkeypatch, amap_weather_handler(paths))
    service = MapService(config)
    near = lambda lon, lat: LocationInput(longitude=lon, latitude=lat)
    assert (await service.weather_for("", near(121.503, 31.202)))["available"]
    assert paths == ["/v3/assistant/coordinate/convert", "/v3/geocode/regeo", "/v3/weather/weatherInfo"]
    paths.clear()
    assert (await service.weather_for("", near(121.504, 31.201)))["available"]
    assert paths == ["/v3/weather/weatherInfo"]


async def test_weather_adcode_cache_spans_tool_turns(agent, monkeypatch):
    agent.config.amap_key = "offline-map-key"
    paths = []
    counting_client(monkeypatch, amap_weather_handler(paths))
    first = await AssistantTools(agent).call("weather", {"city": "北京市"})
    second = await AssistantTools(agent).call("weather", {"city": "北京市"})
    assert first["available"] and second["available"]
    assert paths.count("/v3/geocode/geo") == 1 and paths.count("/v3/weather/weatherInfo") == 2


class ScriptedWire:
    """Feeds scripted JSON events to the reader, then blocks forever."""

    def __init__(self, events):
        self.events = list(events)
        self.sent = []

    async def send(self, raw):
        self.sent.append(json.loads(raw))

    async def recv(self):
        if not self.events:
            await asyncio.Future()
        return json.dumps(self.events.pop(0))


async def queued(session, count, timeout=1.0):
    deadline = asyncio.get_running_loop().time() + timeout
    while session.responses.qsize() < count:
        assert asyncio.get_running_loop().time() < deadline, "reader did not queue events in time"
        await asyncio.sleep(0.001)


def audio_event(response_id, payload):
    return {"type": "response.audio.delta", "response_id": response_id,
            "delta": base64.b64encode(payload).decode("ascii")}


async def test_duplex_receive_drops_late_events_from_previous_turn():
    stale_pcm, fresh_pcm = b"\x01\x00" * 10, b"\x02\x00" * 10
    session = QwenDuplexSession(ScriptedWire([
        {"type": "response.created", "response": {"id": "resp-old"}},
        audio_event("resp-old", stale_pcm),
        {"type": "response.done", "response": {"id": "resp-old", "status": "completed"}},
        {"type": "response.created", "response": {"id": "resp-new"}},
        audio_event("resp-new", fresh_pcm),
    ]), QwenRealtimeConfig(api_key="offline"))
    session.reader = asyncio.create_task(session._read())
    try:
        await queued(session, 2)
        assert session.response_id == "resp-new"
        first = await session.receive(timeout=1)
        second = await session.receive(timeout=1)
        assert [first["type"], second["type"]] == ["response.created", "response.audio.delta"]
        assert base64.b64decode(second["delta"]) == fresh_pcm
        assert all("_aidol_response_id" not in event and "_aidol_buffer_size" not in event
                   for event in (first, second))
        assert session.responses.qsize() == 0 and session.response_buffered == 0
    finally:
        await session.close_reader()


async def test_duplex_keeps_events_when_endpoint_omits_response_ids():
    session = QwenDuplexSession(ScriptedWire([
        {"type": "response.created", "response": {}},
        {"type": "response.audio.delta", "delta": base64.b64encode(b"\x03\x00").decode("ascii")},
    ]), QwenRealtimeConfig(api_key="offline"))
    session.reader = asyncio.create_task(session._read())
    try:
        await queued(session, 2)
        first, second = await session.receive(timeout=1), await session.receive(timeout=1)
        assert [first["type"], second["type"]] == ["response.created", "response.audio.delta"]
    finally:
        await session.close_reader()


async def test_duplex_id_less_late_events_bound_to_cancelled_turn_are_skipped():
    session = QwenDuplexSession(ScriptedWire([
        {"type": "response.audio.delta", "delta": base64.b64encode(b"\x04\x00").decode("ascii")},
        {"type": "response.created", "response": {"id": "resp-next"}},
    ]), QwenRealtimeConfig(api_key="offline"))
    session.response_id = "resp-old"
    session.cancelled_ids = {"resp-old"}
    session.reader = asyncio.create_task(session._read())
    try:
        await queued(session, 1)
        event = await session.receive(timeout=1)
        assert event["type"] == "response.created"
        assert session.responses.qsize() == 0
    finally:
        await session.close_reader()
