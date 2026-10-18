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
