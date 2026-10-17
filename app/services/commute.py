"""Commute direction between the saved home and workplace.

Saved places are AMap GCJ-02 coordinates while device fixes are WGS-84. In
mainland China the two differ by a few hundred metres, which the nearby radius
absorbs, so no provider conversion is needed just to decide a direction.
"""
from datetime import datetime
import math
from zoneinfo import ZoneInfo

NEARBY_METERS = 1000
NAMES = {"home": "家", "work": "公司"}
# A change to any of these invalidates the current route and arrival-notice throttle.
ROUTE_FIELDS = ("destination_lon", "destination_lat", "destination_name", "transport", "transit_city",
                "commute_target", "home_lon", "home_lat", "home_name", "home_city",
                "work_lon", "work_lat", "work_name", "work_city")


def distance_meters(lon1, lat1, lon2, lat2):
    lon1, lat1, lon2, lat2 = map(math.radians, (lon1, lat1, lon2, lat2))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


def saved_places(settings):
    places = {}
    for key in ("home", "work"):
        lon, lat = getattr(settings, key + "_lon"), getattr(settings, key + "_lat")
        if lon is not None and lat is not None:
            places[key] = {"target": key, "name": getattr(settings, key + "_name") or NAMES[key],
                           "longitude": lon, "latitude": lat,
                           "city": getattr(settings, key + "_city") or settings.transit_city}
    return places


def commute_destination(settings, position=None, now=None):
    """Resolve where the user is heading; position is a WGS-84 (lon, lat) pair or None.

    Returns a destination dict with ``target`` in home/work/custom, or one with
    ``target`` None and a user-facing ``reason`` when there is nowhere to route.
    """
    places = saved_places(settings)
    custom = None
    if settings.destination_lon is not None and settings.destination_lat is not None:
        custom = {"target": "custom", "name": settings.destination_name, "longitude": settings.destination_lon,
                  "latitude": settings.destination_lat, "city": settings.transit_city}
    choice = settings.commute_target
    if choice in NAMES:
        return places.get(choice) or {"target": None, "reason": f"请先设置{NAMES[choice]}的位置"}
    if choice == "custom":
        return custom or {"target": None, "reason": "请先选择目的地"}
    if not places:
        return custom or {"target": None, "reason": "请先设置家和公司的位置"}
    near = {key for key, place in places.items() if position is not None and
            distance_meters(position[0], position[1], place["longitude"], place["latitude"]) <= NEARBY_METERS}
    if len(places) == 1:
        (key, place), = places.items()
        if key in near:
            return {"target": None, "reason": f"已在{place['name']}附近；设置另一个通勤地点后可自动规划"}
        return {**place, "basis": "only_saved_place"}
    if near == {"home"}:
        return {**places["work"], "basis": "near_home"}
    if near == {"work"}:
        return {**places["home"], "basis": "near_work"}
    hour = (now or datetime.now(ZoneInfo(settings.timezone))).hour
    key = "work" if hour < (settings.work_start + settings.work_end) / 2 else "home"
    return {**places[key], "basis": "time_of_day"}


def with_destination(settings, destination):
    """A settings copy routed to the resolved destination; persisted settings are unchanged."""
    routed = settings.model_copy(deep=True)
    routed.destination_name = destination["name"]
    routed.destination_lon, routed.destination_lat = destination["longitude"], destination["latitude"]
    routed.transit_city = destination["city"]
    return routed
