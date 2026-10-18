"""Explicit journey contracts and route refresh, separate from the voice session lock."""
import asyncio
import time
from datetime import datetime, timezone
from typing import Literal
from pydantic import BaseModel, Field, field_validator, model_validator
from ..domain import LocationInput


class PlacePoint(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    address: str = Field(min_length=1, max_length=300)
    city: str = Field(min_length=1, max_length=80)
    longitude: float = Field(ge=-180, le=180, allow_inf_nan=False)
    latitude: float = Field(ge=-90, le=90, allow_inf_nan=False)
    adcode: str = Field(default='', pattern=r'^([0-9]{6})?$')

    @field_validator('name', 'address', 'city')
    @classmethod
    def not_blank(cls, value):
        if not value.strip():
            raise ValueError('地址和名称不能为空')
        return value.strip()


class SavedAddressInput(PlacePoint):
    tag: Literal['home', 'work', 'school', 'custom']


class CommuteStart(BaseModel):
    journey_id: str = Field(pattern=r'^[a-zA-Z0-9-]{16,64}$')
    origin_mode: Literal['gps', 'fixed']
    origin: PlacePoint | None = None
    destination: PlacePoint
    transport: Literal['driving', 'walking', 'transit', 'bicycling'] = 'driving'

    @model_validator(mode='after')
    def origin_matches_mode(self):
        if (self.origin is not None) != (self.origin_mode == 'fixed'):
            raise ValueError('请为自定义起点选择已定位地址')
        return self


class CommuteFix(LocationInput):
    source: Literal['gps'] = 'gps'
    accuracy: float = Field(ge=0, le=200, allow_inf_nan=False)
    observed_at: datetime

    @field_validator('observed_at')
    @classmethod
    def fresh(cls, value):
        if value.tzinfo is None or not -10 <= (datetime.now(timezone.utc) - value).total_seconds() <= 120:
            raise ValueError('路线需要两分钟内的手机定位')
        return value.astimezone(timezone.utc)


class CommuteRefresh(BaseModel):
    request_id: str = Field(pattern=r'^[a-zA-Z0-9-]{16,64}$')
    location: CommuteFix | None = None


async def refresh_journey(store, maps, journey_id, value, charge):
    fix = value.location.model_dump(mode='json') if value.location else None
    journey, claimed = store.claim_commute_refresh(journey_id, value.request_id, fix, time.time())
    if not claimed:
        return journey
    try:
        if not charge():
            route = {'available': False, 'reason': '今日路线查询额度已用完'}
        else:
            settings = store.settings().model_copy(deep=True)
            destination = journey['destination']
            settings.destination_name = destination['name']
            settings.destination_lon, settings.destination_lat = destination['longitude'], destination['latitude']
            settings.transit_city, settings.transport = destination['city'], journey['transport']
            point = journey['origin'] if journey['origin_mode'] == 'fixed' else fix
            origin = LocationInput(longitude=point['longitude'], latitude=point['latitude'], accuracy=point.get('accuracy', 0))
            if journey['origin_mode'] == 'fixed':
                route = await asyncio.wait_for(maps.route(origin, settings, origin_gcj=True, origin_city=point['city'], prefer_shorter=True), 35)
            else:
                route = await asyncio.wait_for(maps.route(origin, settings, prefer_shorter=True), 35)
    except asyncio.CancelledError:
        store.finish_commute_refresh(journey_id, value.request_id, {'available': False, 'reason': '路线更新已中断'})
        raise
    except Exception:
        route = {'available': False, 'reason': '路线暂不可用，请检查网络后重试'}
    return store.finish_commute_refresh(journey_id, value.request_id, route)


async def journey_briefing(store, maps, journey_id):
    """One factual speech text; reuse this journey's route and fetch destination weather."""
    from datetime import timedelta
    from zoneinfo import ZoneInfo
    journey = store.commute_journey()
    if journey.get('id') != journey_id or not journey.get('active'):
        raise LookupError('通勤已结束或不存在')
    destination = journey['destination']
    try:
        weather = await asyncio.wait_for(maps.weather(destination['adcode']) if destination.get('adcode')
            else maps.weather_at(destination['longitude'], destination['latitude']), 12)
    except Exception:
        weather = {'available': False}
    route = journey.get('route') or {}
    text = '通勤已开启，目的地是' + destination['name'] + '。'
    if route.get('available') and not journey.get('error') and time.time() - journey.get('last_attempt', 0) < 240:
        seconds = max(0, route.get('eta_seconds', 0))
        arrival = datetime.now(ZoneInfo(store.settings().timezone)) + timedelta(seconds=seconds)
        text += f'路线估算约{max(1, round(seconds / 60))}分钟，预计{arrival:%H点%M分}抵达，全程约{route.get("distance_meters", 0) / 1000:.1f}公里。'
        lines = [s.get('line', '') for s in route.get('steps', []) if s.get('type') == 'transit']
        if lines:
            text += '乘坐' + '，换乘'.join(lines[:4]) + '。公共交通时间为计划估算，上车后请点我已上车，校准站点提醒。'
        if journey['origin_mode'] == 'fixed':
            text += '以上从所选起点计算，实际抵达时间会随出发时间变化。'
    else:
        text += '暂时没有取得最新路线和到达时间，后台会继续重试。'
    if weather.get('available'):
        text += '目的地最近天气为' + weather.get('weather', '') + '，气温' + str(weather.get('temperature', '')) + '摄氏度。'
    else:
        text += '目的地天气暂时未取得。'
    text += '即将打开高德地图。'
    # A concurrent stop must not resurrect a speech/navigation action.
    latest = store.commute_journey()
    if latest.get('id') != journey_id or not latest.get('active'):
        raise LookupError('通勤已结束或不存在')
    return {'journey_id': journey_id, 'text': text, 'weather': weather, 'route': route}
