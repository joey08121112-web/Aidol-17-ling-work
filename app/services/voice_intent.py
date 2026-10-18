"""Deterministic controls for an opted-in continuous call, never quoted speech."""
import re

from ..domain import Mode


_MODES = {"工作": Mode.work, "会议": Mode.work, "通勤": Mode.commute,
          "生活": Mode.life, "日常": Mode.life, "常态对话": Mode.life, "对话": Mode.life}
_SWITCH = re.compile(r"(?:请)?(?:帮我)?(?:切换到|切换至|切到|进入|开启|打开|切回|回到)(工作|会议|通勤|生活|日常|常态对话|对话)模式(?:吧)?[。！!，,\s]*")

# Public lookups skip the model loop only for exact high-frequency phrasings;
# any historical or memory-command reference keeps the full path even with
# these words.
_HISTORY = re.compile(r"上次|之前|刚才|记忆|记得|记录|记住|记下来|提醒|答应|承诺|待办|会议|约定|那次|那家|那条|更早|曾经|原话|说过|去过|买过|住过")
_WEATHER = re.compile(r"^(?:请)?(?:帮我)?(?:查一下|查询|查查|看一下|看看|告诉我)?"
                      r"(?P<place>[\u4e00-\u9fa5]{0,8}?)(?P<day>今天|明天|后天|现在)?(?:的)?"
                      r"(?:天气预报|天气|气温|温度)(?:怎么样|如何|怎样|呢|了|吗)?[。！!，,\s]*$")
_TIME = re.compile(r"^(?:请)?(?:帮我)?(?:告诉我|说下|问一下)?(?:现在|当前|今天)?(?:是)?"
                   r"(?:几点|几点钟|什么时间|几月几号|星期几|周几|几号|什么日期)(?:了|呀|啊)?[。！!，,\s]*$")
_ROUTE = re.compile(r"^(?:请)?(?:帮我)?(?:查一下|查询|看看)?(?:现在)?"
                    r"(?P<how>怎么|如何)?(?P<by>走路|步行|骑车|骑行|开车|驾车|坐地铁|乘地铁|坐公交|乘公交)?"
                    r"(?P<place>回家|回家里|去公司|去上班|到公司)(?:的)?"
                    r"(?:路线|怎么走|如何走)?(?:呀|啊|呢)?(?:大概|大约)?(?:要|得)?(?:多久|多长时间|多少时间)?[。！!，,\s]*$")
_TRANSPORT = {"走路": "walking", "步行": "walking", "骑车": "bicycling", "骑行": "bicycling",
              "开车": "driving", "驾车": "driving", "坐地铁": "transit", "乘地铁": "transit",
              "坐公交": "transit", "乘公交": "transit"}
_HERE = {"", "这边", "这儿", "咱们", "咱们这边", "那里", "当地"}


def mode_command(text):
    match = _SWITCH.fullmatch(text.strip())
    return _MODES[match.group(1)] if match else None


def shortcut_command(text):
    """Return a direct-lookup plan for exact weather/time/route phrasings, else None."""
    stripped = text.strip()
    if _HISTORY.search(stripped):
        return None
    weather = _WEATHER.fullmatch(stripped)
    if weather:
        place = re.sub(r"今天|明天|后天|现在", "", weather.group("place"))
        return {"kind": "weather", "city": "" if place in _HERE else place,
                "day": weather.group("day") or "",
                "forecast": weather.group("day") in {"明天", "后天"}}
    if _TIME.fullmatch(stripped):
        return {"kind": "time"}
    route = _ROUTE.fullmatch(stripped)
    if route and (route.group("how") or route.group("by")
                  or re.search(r"路线|怎么走|如何走|多久|多长时间|多少时间", stripped)):
        return {"kind": "route", "target": "home" if route.group("place").startswith("回家") else "work",
                "transport": _TRANSPORT.get(route.group("by"))}
    return None
