"""Deterministic controls for an opted-in continuous call, never quoted speech."""
import re

from ..domain import Mode


_MODES = {"工作": Mode.work, "会议": Mode.work, "通勤": Mode.commute,
          "生活": Mode.life, "日常": Mode.life, "常态对话": Mode.life, "对话": Mode.life}
_SWITCH = re.compile(r"(?:请)?(?:帮我)?(?:切换到|切换至|切到|进入|开启|打开|切回|回到)(工作|会议|通勤|生活|日常|常态对话|对话)模式(?:吧)?[。！!，,\s]*")


def mode_command(text):
    match = _SWITCH.fullmatch(text.strip())
    return _MODES[match.group(1)] if match else None
