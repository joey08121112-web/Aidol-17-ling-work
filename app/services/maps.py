"""AMap adapter: WGS-84 device locations in, GCJ-02 POIs and plans out.

Transit responses are route plans and scheduled service information, never live
vehicle telemetry. Missing configuration/results are explicit and are not mocked.
"""
import math
from datetime import datetime, timezone

import httpx


class MapService:
    def __init__(self, config):
        self.config = config

    async def route(self, location, settings):
        if not self.config.amap_key:
            return {"available": False, "reason": "尚未配置高德 Key；不生成虚构的实时 ETA"}
        if settings.destination_lon is None or settings.destination_lat is None:
            return {"available": False, "reason": "请先在设置中填写目的地 GCJ-02 经纬度"}
        transport = getattr(settings, "transport", "driving")
        if transport not in {"driving", "walking", "transit"}:
            raise ValueError("出行方式必须为 driving、walking 或 transit")
        async with httpx.AsyncClient(timeout=10, proxy=self.config.proxy) as client:
            # navigator.geolocation gives WGS-84; AMap route APIs require GCJ-02.
            origin = await self._convert(client, location)
            params = {"origin": origin,
                      "destination": f"{settings.destination_lon},{settings.destination_lat}"}
            if transport == "transit":
                city = await self._origin_city(client, origin)
                if not city:
                    return {"available": False, "reason": "无法确定出发城市，请检查定位后重试"}
                destination_city = getattr(settings, "transit_city", "上海") or "上海"
                params.update({"city": city, "cityd": destination_city, "extensions": "all", "strategy": "0"})
                route = await self._get(client, "direction/transit/integrated", params)
            else:
                if transport == "driving":
                    params.update({"extensions": "base", "strategy": "0"})
                route = await self._get(client, "direction/" + transport, params)
        paths = route.get("route", {}).get("transits" if transport == "transit" else "paths", [])
        if not paths:
            return {"available": False, "reason": "高德未返回可用路线"}
        selected = paths[0]
        duration, distance = self._number(selected.get("duration")), self._number(selected.get("distance"))
        if duration is None or distance is None:
            return {"available": False, "reason": "高德路线缺少有效的时间或距离"}
        notes = {"driving": "驾车路线估算；不代表实时公交或地铁车辆位置",
                 "walking": "步行路线估算；实际时间受路况和步速影响",
                 "transit": "公共交通线路规划与计划时刻；不代表车辆实时位置或实际到站时间"}
        result = {"available": True, "source": "amap_" + transport, "transport": transport,
                  "eta_seconds": duration, "distance_meters": distance,
                  "fetched_at": datetime.now(timezone.utc).isoformat(),
                  "destination": settings.destination_name, "note": notes[transport]}
        if transport == "transit":
            result.update({"scheduled": True, "steps": self._transit_steps(selected),
                           "walking_distance_meters": self._number(selected.get("walking_distance")),
                           "cost": self._text(selected.get("cost"))})
        else:
            result["steps"] = [{"instruction": self._text(step.get("instruction")),
                                 "distance_meters": self._number(step.get("distance")),
                                 "duration_seconds": self._number(step.get("duration"))}
                                for step in selected.get("steps", [])[:40] if isinstance(step, dict)]
        return result

    async def search(self, query, city=""):
        """Search a destination; returned coordinates can be saved without conversion."""
        if not query or not query.strip() or len(query) > 100:
            raise ValueError("地点关键词必须为 1 至 100 个字符")
        if not self.config.amap_key:
            return {"available": False, "pois": [], "reason": "尚未配置高德 Key"}
        params = {"keywords": query.strip(), "offset": "10", "page": "1", "extensions": "base"}
        if city:
            params["city"] = city
        async with httpx.AsyncClient(timeout=10, proxy=self.config.proxy) as client:
            body = await self._get(client, "place/text", params)
        return self._poi_result(body, "amap_poi")

    async def nearby(self, location, keyword="美食"):
        """Find real POIs within 3 km of a browser/device WGS-84 location."""
        if len(keyword) > 100:
            raise ValueError("周边搜索关键词不得超过 100 个字符")
        if not self.config.amap_key:
            return {"available": False, "pois": [], "reason": "尚未配置高德 Key"}
        async with httpx.AsyncClient(timeout=10, proxy=self.config.proxy) as client:
            origin = await self._convert(client, location)
            body = await self._get(client, "place/around", {"location": origin,
                "keywords": keyword.strip() or "美食", "radius": "3000", "sortrule": "distance",
                "offset": "10", "page": "1", "extensions": "base"})
        result = self._poi_result(body, "amap_nearby")
        result["radius_meters"] = 3000
        return result

    async def weather(self, city):
        """Read current reported weather for a six-digit AMap city/district adcode."""
        city = str(city).strip()
        if len(city) != 6 or not city.isascii() or not city.isdigit():
            raise ValueError("天气查询需要 6 位城市 adcode，可从地点搜索结果获得")
        if not self.config.amap_key:
            return {"available": False, "reason": "尚未配置高德 Key"}
        async with httpx.AsyncClient(timeout=10, proxy=self.config.proxy) as client:
            body = await self._get(client, "weather/weatherInfo", {"city": city, "extensions": "base"})
        lives = body.get("lives", [])
        if not lives or not isinstance(lives[0], dict):
            return {"available": False, "reason": "高德未返回该城市的天气"}
        current = lives[0]
        return {"available": True, "source": "amap_weather", "city": self._text(current.get("city")),
                "adcode": self._text(current.get("adcode")) or city,
                "weather": self._text(current.get("weather")), "temperature": self._text(current.get("temperature")),
                "humidity": self._text(current.get("humidity")), "wind_direction": self._text(current.get("winddirection")),
                "wind_power": self._text(current.get("windpower")), "report_time": self._text(current.get("reporttime")),
                "note": "高德最近发布的实况天气；温度单位摄氏度，湿度单位百分比"}

    async def weather_at(self, longitude, latitude):
        """Resolve a selected GCJ-02 destination when POI search omits adcode."""
        if not self.config.amap_key:
            return {"available": False, "reason": "尚未配置高德 Key"}
        async with httpx.AsyncClient(timeout=10, proxy=self.config.proxy) as client:
            body = await self._get(client, "geocode/regeo", {"location": f"{longitude},{latitude}", "extensions": "base"})
        code = self._text(body.get("regeocode", {}).get("addressComponent", {}).get("adcode"))
        if len(code) != 6 or not code.isascii() or not code.isdigit():
            return {"available": False, "reason": "暂时无法识别目的地天气区域，请更换目的地重试"}
        return await self.weather(code)

    async def _convert(self, client, location):
        converted = await self._get(client, "assistant/coordinate/convert", {
            "locations": f"{location.longitude},{location.latitude}", "coordsys": "gps"})
        origin = converted.get("locations")
        if not isinstance(origin, str) or not self._coordinates(origin):
            raise ValueError("高德未返回有效的 GCJ-02 坐标")
        return origin

    async def _origin_city(self, client, origin):
        body = await self._get(client, "geocode/regeo", {"location": origin, "extensions": "base"})
        address = body.get("regeocode", {}).get("addressComponent", {})
        # Direct-administered municipalities can have city=[]; citycode works for
        # those cases and also avoids accidentally using a district as the city.
        return self._text(address.get("citycode")) or self._text(address.get("city")) or self._text(address.get("province"))

    @classmethod
    def _poi_result(cls, body, source):
        pois = []
        for item in body.get("pois", []):
            if not isinstance(item, dict):
                continue
            coordinates = cls._coordinates(item.get("location"))
            if coordinates is None or not cls._text(item.get("name")):
                continue
            poi = {"id": cls._text(item.get("id")), "name": cls._text(item.get("name")),
                   "address": cls._text(item.get("address")), "longitude": coordinates[0],
                   "latitude": coordinates[1], "city": cls._text(item.get("cityname")) or cls._text(item.get("pname")),
                   "adcode": cls._text(item.get("adcode"))}
            distance = cls._number(item.get("distance"))
            if distance is not None:
                poi["distance_meters"] = distance
            pois.append(poi)
        return {"available": True, "source": source, "pois": pois[:10],
                "coordinate_system": "GCJ-02", "reason": "" if pois else "未找到匹配地点，可更换关键词或城市"}

    @staticmethod
    def _text(value):
        return str(value).strip() if isinstance(value, (str, int, float)) else ""

    @staticmethod
    def _number(value):
        try:
            number = float(value)
            return int(number) if math.isfinite(number) and number >= 0 else None
        except (TypeError, ValueError, OverflowError):
            return None

    @staticmethod
    def _coordinates(value):
        try:
            longitude, latitude = map(float, value.split(","))
            if math.isfinite(longitude) and math.isfinite(latitude) and -180 <= longitude <= 180 and -90 <= latitude <= 90:
                return longitude, latitude
        except (AttributeError, TypeError, ValueError):
            pass
        return None

    @classmethod
    def _transit_steps(cls, path):
        steps = []
        for segment in path.get("segments", [])[:20]:
            if not isinstance(segment, dict):
                continue
            walking = segment.get("walking") or {}
            if not isinstance(walking, dict):
                walking = {}
            walk_distance = cls._number(walking.get("distance"))
            if walk_distance:
                steps.append({"type": "walking", "instruction": f"步行约 {walk_distance} 米",
                              "distance_meters": walk_distance, "duration_seconds": cls._number(walking.get("duration"))})
            bus = segment.get("bus") or {}
            lines = bus.get("buslines", []) if isinstance(bus, dict) else []
            if lines:
                # Buslines in one segment are alternatives, not sequential legs.
                line = lines[0]
                start = cls._text(line.get("departure_stop", {}).get("name"))
                end = cls._text(line.get("arrival_stop", {}).get("name"))
                name = cls._text(line.get("name"))
                steps.append({"type": "transit", "instruction": f"{start}乘坐{name}，在{end}下车",
                              "line": name, "departure_stop": start, "arrival_stop": end,
                              "stop_count": cls._number(line.get("via_num")),
                              "duration_seconds": cls._number(line.get("duration")),
                              "first_departure": cls._text(line.get("start_time")),
                              "last_departure": cls._text(line.get("end_time"))})
            railway = segment.get("railway", {})
            if railway and isinstance(railway, dict):
                name = cls._text(railway.get("name")) or cls._text(railway.get("trip"))
                steps.append({"type": "railway", "instruction": f"乘坐列车 {name}", "line": name})
        return steps

    async def _get(self, client, path, params):
        try:
            result = await client.get("https://restapi.amap.com/v3/" + path,
                                      params={"key": self.config.amap_key, **params})
            result.raise_for_status()
            body = result.json()
        except (httpx.HTTPError, ValueError):
            # HTTPStatusError includes a URL with key=...; never expose it upstream.
            raise ValueError("高德服务暂不可用，请稍后重试") from None
        if not isinstance(body, dict):
            raise ValueError("高德返回了无效数据")
        if body.get("status") != "1":
            raise ValueError("高德请求失败，请检查 Key 权限、配额和参数")
        return body
