"""Read-only tools for the existing account orchestrator, inspired by demo ReAct.

No shell, filesystem, arbitrary URL fetch, messaging, or second agent. Location
comes only from a recent user-authorized device fix; model coordinates are rejected.
"""
from datetime import datetime, timezone
from typing import Literal
from zoneinfo import ZoneInfo

from pydantic import BaseModel, ConfigDict, Field

from ..domain import LocationInput
from ..providers.bocha import BochaSearch


class Arguments(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Search(Arguments):
    query: str = Field(min_length=1, max_length=500)
    freshness: str = Field(default="noLimit", max_length=24)


class Places(Arguments):
    query: str = Field(min_length=1, max_length=100)
    city: str = Field(default="", max_length=80)


class Nearby(Arguments):
    keyword: str = Field(default="美食", max_length=80)
    radius: int = Field(default=3000, ge=100, le=50000)
    metro_only: bool = False


class Weather(Arguments):
    city: str = Field(default="", max_length=80)
    forecast: bool = False


class Route(Arguments):
    destination_id: str = Field(default="", max_length=100)
    transport: Literal["driving", "walking", "transit", "bicycling"] = "transit"


TOOLS = {
    "web_search": (Search, "搜索公开互联网最新资料。只提交用户此次查询所需关键词，禁止附带历史记忆或账号资料；返回网页来源，不能据此保证余票或营业状态。"),
    "search_places": (Places, "高德地点搜索，返回真实目的地 ID 和 GCJ-02 坐标；同名地点需要用户明确城市或选择。"),
    "geocode": (Places, "将明确的地址和城市解析成坐标及行政区代码。"),
    "nearby_places": (Nearby, "用用户最近主动提供的位置查周边；metro_only=true 查附近地铁站。"),
    "current_location": (Arguments, "用用户最近主动提供的位置查地址；无位置时提示点击定位，不能推测。"),
    "weather": (Weather, "查实况或天气预报；city 可为明确城市名/6位行政区代码，空值表示当前位置。"),
    "plan_route": (Route, "查询路线，不修改设置。destination_id 只能来自本轮 search_places/nearby_places，空值使用用户已保存目的地；路线为估算。"),
    "current_time": (Arguments, "查询用户设置时区的真实当前日期、星期、时间。"),
}


class AssistantTools:
    def __init__(self, agent):
        self.agent = agent
        self.search = BochaSearch(agent.config)
        self.receipts = []
        self.places = {}

    def schemas(self):
        return [{"type": "function", "function": {"name": name, "description": description,
            "parameters": model.model_json_schema()}} for name, (model, description) in TOOLS.items()]

    def location(self):
        value = self.agent.location
        if value:
            age = (datetime.now(timezone.utc) - datetime.fromisoformat(value["observed_at"])).total_seconds()
            if 0 <= age <= 300 and value.get("accuracy", 100000) <= 200 and value.get("source") == "gps":
                return LocationInput(**value)
        raise ValueError("请先在通勤页面点击定位；位置需在五分钟内且精度优于 200 米")

    async def call(self, name, arguments):
        try:
            if name not in TOOLS:
                raise ValueError("不支持此操作")
            args = TOOLS[name][0].model_validate(arguments)
            charge = getattr(self.agent, 'tool_charge', None)
            if name != 'current_time' and charge is not None and not charge():
                result = {"available": False, "reason": "今日查询额度已用完，请明天再试"}
                self.receipts.append({"tool": name, "result": result})
                return result
            result = await self._execute(name, args)
        except ValueError:
            # Never echo validation input or provider response into model context.
            result = {"available": False, "reason": "工具参数无效、服务不可用或缺少定位；请检查配置，位置问题请在通勤页重新定位"}
        self.receipts.append({"tool": name if name in TOOLS else "unsupported", "result": result})
        return result

    async def _execute(self, name, args):
        maps = self.agent.maps
        if name == "web_search":
            return await self.search.search(args.query, args.freshness)
        if name == "current_time":
            now = datetime.now(ZoneInfo(self.agent.store.settings().timezone))
            return {"available": True, "source": "server_clock", "datetime": now.isoformat(),
                "timezone": self.agent.store.settings().timezone, "weekday": now.isoweekday()}
        if name == "geocode":
            return await maps.geocode(args.query, args.city)
        if name in {"search_places", "nearby_places"}:
            result = (await maps.search(args.query, args.city) if name == "search_places" else
                      await maps.nearby(self.location(), args.keyword, args.radius, args.metro_only))
            self.places.update({p["id"]: p for p in result.get("pois", []) if p.get("id")})
            return result
        if name == "current_location":
            return await maps.reverse(self.location())
        if name == "weather":
            if args.city:
                code = args.city
                if not (code.isascii() and code.isdigit() and len(code) == 6):
                    places = (await maps.geocode(args.city)).get("places", [])
                    if len(places) != 1:
                        return {"available": False, "reason": "城市不明确，请提供城市/区县名称"}
                    code = places[0]["adcode"]
            else:
                code = (await maps.reverse(self.location())).get("adcode", "")
            return await maps.weather(code, args.forecast)
        if name == "plan_route":
            settings = self.agent.store.settings().model_copy(deep=True)
            if args.destination_id:
                poi = self.places.get(args.destination_id)
                if not poi:
                    return {"available": False, "reason": "请先搜索并明确目的地，不接受模型编造的地点 ID"}
                settings.destination_lon, settings.destination_lat = poi["longitude"], poi["latitude"]
                settings.destination_name, settings.transit_city = poi["name"], poi["city"] or settings.transit_city
            settings.transport = args.transport
            return await maps.route(self.location(), settings)
