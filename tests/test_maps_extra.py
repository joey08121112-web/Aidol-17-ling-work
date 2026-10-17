"""Offline AMap feature contracts, including city and coordinate boundaries."""
from types import SimpleNamespace

import httpx
import pytest

from app.domain import LocationInput
from app.services.maps import MapService


@pytest.fixture
def map_mock(config, monkeypatch):
    config.amap_key = "test-map-key"
    responses, requests = [], []
    def handle(request):
        requests.append(request)
        response = responses.pop(0)
        if isinstance(response, httpx.Response):
            return response
        return httpx.Response(200, json={"status": "1", **response})
    real_client = httpx.AsyncClient
    monkeypatch.setattr("app.services.maps.httpx.AsyncClient", lambda **kwargs: real_client(transport=httpx.MockTransport(handle)))
    return MapService(config), responses, requests


def destination(transport):
    return SimpleNamespace(destination_lon=121.6, destination_lat=31.3, destination_name="公司",
                           transport=transport, transit_city="上海")


@pytest.mark.asyncio
async def test_search_returns_gcj02_destinations_and_skips_invalid_locations(map_mock):
    service, responses, requests = map_mock
    responses.append({"pois": [{"id": "B1", "name": "张江园区", "address": "科苑路", "location": "121.60,31.20",
        "cityname": "上海市", "adcode": "310115"}, {"name": "坏坐标", "location": "nan,31"},
        {"name": "缺坐标", "location": []}]})
    result = await service.search("张江", "上海")
    assert result["available"] and result["coordinate_system"] == "GCJ-02"
    assert result["pois"] == [{"id": "B1", "name": "张江园区", "address": "科苑路", "longitude": 121.6,
                               "latitude": 31.2, "city": "上海市", "adcode": "310115"}]
    assert requests[0].url.path == "/v3/place/text"
    assert requests[0].url.params["city"] == "上海"


@pytest.mark.asyncio
async def test_nearby_converts_device_location_before_search(map_mock):
    service, responses, requests = map_mock
    responses.extend([{"locations": "121.505,31.202"}, {"pois": [{"name": "咖啡馆", "location": "121.506,31.202",
        "address": [], "cityname": [], "pname": "上海市", "distance": "80", "adcode": "310101"}]}])
    result = await service.nearby(LocationInput(longitude=121.5, latitude=31.2), "咖啡")
    assert result["source"] == "amap_nearby" and result["radius_meters"] == 3000
    assert result["pois"][0]["distance_meters"] == 80
    assert result["pois"][0]["address"] == "" and result["pois"][0]["city"] == "上海市"
    assert requests[1].url.params["location"] == "121.505,31.202"
    assert requests[1].url.params["keywords"] == "咖啡"


@pytest.mark.asyncio
async def test_weather_requires_adcode_and_preserves_report_time(map_mock):
    service, responses, requests = map_mock
    with pytest.raises(ValueError, match="adcode"):
        await service.weather("上海")
    assert not requests
    responses.append({"lives": [{"city": "浦东新区", "adcode": "310115", "weather": "晴", "temperature": "22",
        "humidity": "58", "winddirection": "东", "windpower": "≤3", "reporttime": "2026-10-17 10:00:00"}]})
    result = await service.weather("310115")
    assert result["weather"] == "晴" and result["temperature"] == "22"
    assert result["report_time"] == "2026-10-17 10:00:00"
    assert requests[0].url.params["city"] == "310115"


@pytest.mark.asyncio
async def test_walking_route_keeps_two_request_contract(map_mock):
    service, responses, requests = map_mock
    responses.extend([{"locations": "121.505,31.202"}, {"route": {"paths": [{"duration": "600", "distance": "800",
        "steps": [{"instruction": "向北步行", "distance": "800", "duration": "600"}]}]}}])
    result = await service.route(LocationInput(longitude=121.5, latitude=31.2), destination("walking"))
    assert len(requests) == 2 and requests[1].url.path == "/v3/direction/walking"
    assert result["eta_seconds"] == 600 and result["source"] == "amap_walking"
    assert result["steps"][0]["instruction"] == "向北步行"


@pytest.mark.asyncio
async def test_transit_resolves_origin_city_and_reports_only_schedule(map_mock):
    service, responses, requests = map_mock
    responses.extend([{"locations": "121.505,31.202"},
        {"regeocode": {"addressComponent": {"city": [], "citycode": "021", "province": "上海市"}}},
        {"route": {"transits": [{"duration": "1800", "distance": "12000", "walking_distance": "300", "cost": "4",
            "segments": [{"walking": {"distance": "300", "duration": "240"}, "bus": {"buslines": [
                {"name": "地铁2号线", "departure_stop": {"name": "世纪大道"}, "arrival_stop": {"name": "张江高科"},
                 "via_num": "6", "duration": "1560", "start_time": "0530", "end_time": "2300"},
                {"name": "替代线路，不应该成为下一段"}]}}, {"walking": [], "bus": [], "railway": []}]}]}}])
    result = await service.route(LocationInput(longitude=121.5, latitude=31.2), destination("transit"))
    assert requests[1].url.path == "/v3/geocode/regeo"
    assert requests[2].url.path == "/v3/direction/transit/integrated"
    assert requests[2].url.params["city"] == "021" and requests[2].url.params["cityd"] == "上海"
    assert result["scheduled"] and result["source"] == "amap_transit"
    assert "不代表车辆实时" in result["note"]
    assert len(result["steps"]) == 2
    assert result["steps"][1]["first_departure"] == "0530"


@pytest.mark.asyncio
async def test_no_transit_origin_city_does_not_guess(map_mock):
    service, responses, requests = map_mock
    responses.extend([{"locations": "121.505,31.202"}, {"regeocode": {"addressComponent": {}}}])
    result = await service.route(LocationInput(longitude=121.5, latitude=31.2), destination("transit"))
    assert not result["available"] and len(requests) == 2


@pytest.mark.asyncio
async def test_missing_key_disables_extra_features(config):
    service = MapService(config)
    results = [await service.search("公司"), await service.nearby(LocationInput(longitude=121.5, latitude=31.2)),
               await service.weather("310115")]
    assert all(not result["available"] for result in results)


@pytest.mark.asyncio
async def test_empty_poi_and_weather_are_explicit(map_mock):
    service, responses, _ = map_mock
    responses.extend([{"pois": []}, {"lives": []}])
    pois = await service.search("不存在")
    assert pois["available"] and pois["pois"] == [] and pois["reason"]
    assert not (await service.weather("310115"))["available"]


@pytest.mark.asyncio
async def test_http_error_never_exposes_key_url(map_mock):
    service, responses, _ = map_mock
    responses.append(httpx.Response(403, text="forbidden"))
    with pytest.raises(ValueError) as error:
        await service.search("公司")
    assert "test-map-key" not in str(error.value)
    assert "https://" not in str(error.value)


@pytest.mark.asyncio
async def test_route_missing_duration_has_no_fabricated_eta(map_mock):
    service, responses, _ = map_mock
    responses.extend([{"locations": "121.505,31.202"}, {"route": {"paths": [{"duration": [], "distance": "200"}]}}])
    result = await service.route(LocationInput(longitude=121.5, latitude=31.2), destination("walking"))
    assert not result["available"] and "eta_seconds" not in result


@pytest.mark.asyncio
async def test_bicycling_uses_v4_errcode_and_data(map_mock):
    service, responses, requests = map_mock
    responses.extend([{"locations": "121.505,31.202"}, httpx.Response(200, json={"errcode": 0,
        "data": {"paths": [{"duration": "300", "distance": "1000", "steps": [{"instruction": "向北骑行"}]}]}})])
    result = await service.route(LocationInput(longitude=121.5, latitude=31.2), destination("bicycling"))
    assert result["available"] and result["eta_seconds"] == 300
    assert requests[1].url.path == "/v4/direction/bicycling"


@pytest.mark.asyncio
async def test_weather_forecasts_plural_and_dates(map_mock):
    service, responses, requests = map_mock
    responses.append({"forecasts": [{"city": "北京市", "reporttime": "2026-10-17 11:00:00", "casts": [
        {"date": "2026-10-18", "dayweather": "晴", "nightweather": "多云", "daytemp": "20", "nighttemp": "10"}]}]})
    result = await service.weather("110000", True)
    assert result["available"] and result["forecast"][0]["date"] == "2026-10-18"
    assert requests[0].url.params["extensions"] == "all"


@pytest.mark.asyncio
async def test_metro_search_uses_single_type_and_configured_radius(map_mock):
    service, responses, requests = map_mock
    responses.extend([{"locations": "121.505,31.202"}, {"pois": []}])
    await service.nearby(LocationInput(longitude=121.5, latitude=31.2), radius=2000, metro_only=True)
    assert requests[1].url.params["types"] == "150500"
    assert requests[1].url.params["radius"] == "2000" and requests[1].url.params["keywords"] == ""


@pytest.mark.asyncio
async def test_geocode_and_reverse_coordinates(map_mock):
    service, responses, requests = map_mock
    responses.extend([{"geocodes": [{"formatted_address": "上海市", "city": [], "province": "上海市",
        "adcode": "310000", "location": "121.5,31.2"}]}, {"locations": "121.505,31.202"},
        {"regeocode": {"formatted_address": "上海市人民广场", "addressComponent": {"city": [], "province": "上海市", "adcode": "310101"}}}])
    geocoded = await service.geocode("上海市")
    assert geocoded["places"][0]["city"] == "上海市" and geocoded["coordinate_system"] == "GCJ-02"
    result = await service.reverse(LocationInput(longitude=121.5, latitude=31.2))
    assert result["available"] and result["adcode"] == "310101"
    assert requests[-1].url.params["location"] == "121.505,31.202"
