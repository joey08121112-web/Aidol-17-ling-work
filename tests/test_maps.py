import httpx
import pytest

from app.domain import LocationInput, Settings
from app.services.maps import MapService


@pytest.mark.asyncio
async def test_unconfigured_maps_do_not_fabricate_eta(config):
    result = await MapService(config).route(LocationInput(longitude=121.5, latitude=31.2), Settings())
    assert result["available"] is False
    assert "eta_seconds" not in result


@pytest.mark.asyncio
async def test_missing_destination_prevents_remote_request(config):
    config.amap_key = "offline-map-key"
    result = await MapService(config).route(LocationInput(longitude=121.5, latitude=31.2), Settings())
    assert result["available"] is False


@pytest.mark.asyncio
async def test_route_converts_gps_then_routes_to_gcj02_destination(config, monkeypatch):
    config.amap_key = "offline-map-key"
    requests = []

    def handler(request):
        requests.append(request)
        assert request.url.params["key"] == "offline-map-key"
        if "coordinate" in request.url.path:
            assert request.url.params["coordsys"] == "gps"
            return httpx.Response(200, json={"status": "1", "locations": "121.505,31.202"})
        assert request.url.params["origin"] == "121.505,31.202"
        assert request.url.params["destination"] == "121.6,31.3"
        return httpx.Response(200, json={"status": "1", "route": {"paths": [{"duration": "240", "distance": "2000"}]}})

    real_client = httpx.AsyncClient
    monkeypatch.setattr("app.services.maps.httpx.AsyncClient", lambda **kwargs: real_client(transport=httpx.MockTransport(handler)))
    result = await MapService(config).route(LocationInput(longitude=121.5, latitude=31.2),
        Settings(destination_lon=121.6, destination_lat=31.3))
    assert len(requests) == 2
    assert result["available"] and result["eta_seconds"] == 240 and result["distance_meters"] == 2000
    assert result["source"] == "amap_driving"


@pytest.mark.asyncio
async def test_vendor_error_does_not_expose_key(config):
    config.amap_key = "offline-map-key"
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(
        200, json={"status": "0", "info": "INVALID_USER_KEY"}))) as client:
        with pytest.raises(ValueError) as failure:
            await MapService(config)._get(client, "direction/driving", {})
    assert config.amap_key not in str(failure.value)
