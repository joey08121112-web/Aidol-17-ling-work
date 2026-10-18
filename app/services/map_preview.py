"""Read-only commute previews and bounded GCJ-02 geometry for AMap's static map API."""
from typing import Annotated, Literal
from pydantic import BaseModel, Field, model_validator
from ..domain import LocationInput, Settings
from .commute_journeys import PlacePoint, CommuteFix

Longitude = Annotated[float, Field(ge=-180, le=180, allow_inf_nan=False)]
Latitude = Annotated[float, Field(ge=-90, le=90, allow_inf_nan=False)]
Coordinate = tuple[Longitude, Latitude]
Line = Annotated[list[Coordinate], Field(min_length=2, max_length=120)]


class MapPoint(BaseModel):
    longitude: Longitude
    latitude: Latitude


class MapSnapshot(BaseModel):
    origin: MapPoint | None = None
    destination: MapPoint | None = None
    paths: list[Line] = Field(default_factory=list, max_length=4)

    @model_validator(mode='after')
    def needs_point(self):
        if self.origin is None and self.destination is None:
            raise ValueError('请先选择起点或目的地')
        return self


class CommutePreview(BaseModel):
    origin_mode: Literal['gps', 'fixed'] = 'gps'
    origin: PlacePoint | None = None
    location: CommuteFix | None = None
    destination: PlacePoint | None = None
    transport: Literal['driving', 'walking', 'transit', 'bicycling'] = 'driving'

    @model_validator(mode='after')
    def correct_coordinate_source(self):
        if self.origin_mode == 'gps' and self.origin is not None or self.origin_mode == 'fixed' and self.location is not None:
            raise ValueError('起点坐标来源不匹配')
        return self


async def preview(maps, value):
    origin = value.origin.model_dump() if value.origin_mode == 'fixed' and value.origin else None
    if value.origin_mode == 'gps' and value.location:
        # Device coordinates are WGS-84; saved places and all map overlays are GCJ-02.
        origin = await maps.map_point(value.location)
    destination = value.destination.model_dump() if value.destination else None
    route = {'available': False, 'reason': '请选择起点和目的地'}
    if origin and destination:
        settings = Settings(destination_name=destination['name'], destination_lon=destination['longitude'],
                            destination_lat=destination['latitude'], transit_city=destination['city'], transport=value.transport)
        location = LocationInput(longitude=origin['longitude'], latitude=origin['latitude'])
        try:
            route = await maps.route(location, settings, origin_gcj=True, origin_city=origin.get('city', ''), prefer_shorter=True)
        except (ValueError, TimeoutError):
            route = {'available': False, 'reason': '路线暂不可用，请稍后刷新'}
    return {'origin': origin, 'destination': destination, 'route': route, 'coordinate_system': 'GCJ-02'}


def simplify(points, limit=120):
    """Douglas-Peucker with a bounded result; keep bends and both endpoints rather than truncating."""
    if len(points) <= limit:
        return points
    tolerance = 0.00001
    while True:
        keep = {0, len(points) - 1}
        pending = [(0, len(points) - 1)]
        while pending:
            start, end = pending.pop()
            ax, ay = points[start]; bx, by = points[end]
            dx, dy = bx - ax, by - ay; length = dx * dx + dy * dy
            farthest, at = 0, start
            for i in range(start + 1, end):
                px, py = points[i]
                t = max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / length)) if length else 0
                distance = (px - ax - t * dx) ** 2 + (py - ay - t * dy) ** 2
                if distance > farthest: farthest, at = distance, i
            if farthest > tolerance * tolerance:
                keep.add(at); pending.extend(((start, at), (at, end)))
        if len(keep) <= limit:
            return [points[i] for i in sorted(keep)]
        tolerance *= 2


def route_geometry(path, transport, coordinates):
    pieces = []
    if transport == 'transit':
        for segment in (path.get('segments') or [])[:80]:
            if not isinstance(segment, dict): continue
            walking = segment.get('walking') or {}
            if isinstance(walking, dict): pieces.extend(s.get('polyline') for s in (walking.get('steps') or []) if isinstance(s, dict))
            bus = segment.get('bus') or {}
            lines = bus.get('buslines') or [] if isinstance(bus, dict) else []
            if lines and isinstance(lines[0], dict): pieces.append(lines[0].get('polyline'))
            # Never invent a straight railway/unknown leg from two station coordinates.
            if segment.get('railway'): pieces.append(None)
    else:
        pieces = [s.get('polyline') for s in (path.get('steps') or [])[:1000] if isinstance(s, dict)]
    groups = []; current = []; partial = False
    remaining = 30000
    for raw in pieces:
        if remaining <= 0:
            partial = True; break
        parts = raw.split(';', remaining) if isinstance(raw, str) else []
        if len(parts) > remaining: partial = True
        parsed = [coordinates(p) for p in parts[:remaining]]
        remaining -= len(parsed)
        if any(p is None for p in parsed): partial = True
        points = [list(p) for p in parsed if p is not None]
        if not points:
            if current: groups.append(current); current = []
            partial = True; continue
        if current and current[-1] != points[0]:
            # Disjoint provider geometry stays disjoint, including transit interchange gaps.
            groups.append(current); current = []
        for point in points:
            if not current or current[-1] != point: current.append(point)
    if current: groups.append(current)
    groups = [g for g in groups if len(g) > 1]
    if len(groups) > 4:
        # Static maps allow four paths. Show endpoints and the longest supplied portions explicitly.
        groups = sorted(enumerate(groups), key=lambda g: len(g[1]), reverse=True)[:4]
        groups = [g for _, g in sorted(groups)]; partial = True
    return [simplify(g) for g in groups], partial


def snapshot_params(value):
    markers = []
    for name, point, color in [('起', value.origin, '0x24786d'), ('终', value.destination, '0x2468db')]:
        if point: markers.append(f'mid,{color},{name}:{point.longitude:.6f},{point.latitude:.6f}')
    params = {'size': '760*420', 'scale': '1', 'markers': '|'.join(markers)}
    if value.paths:
        params['paths'] = '|'.join(
            '5,0x2468db,0.9,,:' + ';'.join(f'{lon:.6f},{lat:.6f}' for lon, lat in line) for line in value.paths)
    # Omitting center/zoom lets AMap fit every supplied point and path in the same viewport.
    return params
