"""Explicit, evidence-backed memory commands; ambient speech is never a command."""
import re
import time

from .memory import is_non_assertion
from .recall import RecallService


_REMEMBER = re.compile(r"^(?:请)?(?:帮我)?(?:记住|记一下|记下来|帮我记住)[，,:：\s]*(.+)", re.S)
_FORGET = re.compile(r"^(?:请)?(?:帮我)?(?:忘记|忘掉|删除)(?:刚才|刚刚)?(?:那条|这条|这一条|那一条|它|这个|那条记忆|这条记忆|刚才那条)[。！!\s]*$")
_CORRECT = re.compile(r"^(?:请)?(?:帮我)?(?:后来|刚才那条|这条|它)?(?:更正为|改成|改为|改到)[，,:：\s]*(.+?)[。！!\s]*$", re.S)
_DATE = re.compile(r"(?:下周|这周|周|星期)[一二三四五六日天](?:上午|下午|晚上|中午|早上)?(?:\d{1,2}点(?:半|\d{1,2}分)?)?|(?:明天|后天|今天)(?:上午|下午|晚上|中午|早上)?(?:\d{1,2}点(?:半|\d{1,2}分)?)?")
_FOLLOWUP = re.compile(r"不是这次|上个月那次|那次呢|那家呢|那件事|再早一点|更早|听原话|原话呢")


class MemoryInteraction:
    def __init__(self, config, store):
        self.store = store
        self.recall = RecallService(config)
        self.reset()

    def reset(self):
        self.target_ids = []
        self.last_question = ""
        self.context_at = 0.0

    def _set_targets(self, ids):
        self.target_ids = list(dict.fromkeys(ids))
        self.context_at = time.monotonic()

    def _result(self, response, operation, **extra):
        return {"response": response, "interaction": "memory_" + operation,
                "memories": [], "extraction": "rules", **extra}

    @staticmethod
    def intent(text):
        if _REMEMBER.match(text):
            return "remember"
        if _FORGET.match(text) or re.match(r"^(?:请)?(?:忘记|忘掉|删除)", text):
            return "forget"
        if _CORRECT.match(text):
            return "correct"
        # "告诉我今天的天气/现在几点" is a public lookup, not a request to
        # recall private history merely because it contains 我/今天. Explicit
        # historical references still take priority over place/weather words.
        if (re.search(r"天气|气温|下雨|预报|附近|周边|导航|路线|路况|公交|地铁|骑行|新闻|联网|网上|网页|几点|星期几|几号|日期|现在.*时间", text)
                and not re.search(r"上次|之前|刚才|记忆|记得|记录|答应|承诺|待办|会议|约定|出门前|过敏|那次|那家|更早|曾经|原话|喜欢|偏好|说过|去过|买过|住过", text)):
            return None
        if (_FOLLOWUP.search(text)
                or (is_non_assertion(text) and re.search(r"我|上次|之前|刚才|记忆|记得|记录|答应|承诺|待办|会议|决定|出门前|今晚|今天|过敏", text))
                or re.search(r"查(?:一下|找)?(?:记忆|记录)|找回|回忆|还有.*(?:事|做)|(?:未完成|待办).*(?:事项|任务)|(?:记得|记着).*(?:吗|么)", text)):
            return "recall"
        return None

    async def handle(self, text, speaker, source, mode, transcript_id=None):
        text = text.strip()
        intent = self.intent(text)
        if not intent:
            return None
        if time.monotonic() - self.context_at > 600:
            self.reset()
        if intent == "remember":
            self.reset()
            content = _REMEMBER.match(text).group(1).strip()
            if is_non_assertion(content, speaker):
                return self._result("这句话含有问题或不确定说法，我没有把它保存为事实。请明确要记住的内容。", intent)
            kind = ("preference" if re.search("喜欢|不吃|过敏|偏好", content) else
                    "task" if re.search("答应|约定|买|提交|负责|截止|完成|周[一二三四五六日]|明天|后天", content) else "fact")
            origin = ("transcript:" + transcript_id) if transcript_id else "voice_explicit"
            row = self.store.add("memories", kind=kind, content=f"{speaker}：{content}", source=origin)
            self._set_targets([row["id"]])
            self.last_question = content
            message = "已保存这条记忆：" + content
            if kind == "task" or "提醒" in content:
                message += "。这里只保存记忆，尚未设置定时或位置提醒。"
            return self._result(message, intent, memories=[row])
        if intent in {"correct", "forget"}:
            if intent == "forget" and not _FORGET.fullmatch(text):
                self.reset()
                return self._result("请先问回要删除的那条记忆，确认唯一记录后说“忘记刚才那条”。", intent)
            if len(self.target_ids) != 1:
                return self._result("还不能确定你指哪条记忆。请先用具体内容问回一条记录，再更正或忘记。", intent)
            row = self.store.get_memory(self.target_ids[0])
            if not row:
                self.reset()
                return self._result("那条记忆已经不存在，没有再次修改。", intent)
            if intent == "forget":
                deleted = self.store.forget_memory(row["id"])
                self.reset()
                if not deleted:
                    return self._result("那条记忆已经不存在，没有再次删除。", intent)
                return self._result("已忘记这条记忆，并清理关联原文与派生记录；后续检索不再使用。其他独立记录仍保留。", intent,
                                    deletion=deleted)
            replacement = _CORRECT.match(text).group(1).strip()
            replacement = re.sub(r"了$", "", replacement).strip()
            if is_non_assertion(replacement):
                return self._result("这次更正含有问题或不确定说法，原记忆未修改。", intent)
            # A short date correction only replaces an unambiguous existing date.
            # Never keep the obsolete date as if it were still the current promise.
            if _DATE.fullmatch(replacement):
                dates = list(_DATE.finditer(row["content"]))
                if len(dates) != 1:
                    return self._result("原记录的日期不够明确，请说“更正为”加上完整的新约定。原记忆未修改。", intent)
                content = _DATE.sub(lambda _: replacement, row["content"], count=1)
            elif text.startswith("更正为") or len(replacement) >= 6:
                content = f"{speaker}：{replacement}"
            else:
                return self._result("请说“更正为”加上完整内容，避免改错事项。原记忆未修改。", intent)
            updated = self.store.correct_memory(row["id"], content)
            if not updated:
                self.reset()
                return self._result("那条记忆已经不存在，本次没有修改。", intent)
            self._set_targets([updated["id"]])
            self.last_question = content
            return self._result("已更正这条记忆：" + content, intent, memories=[updated])
        question = text[:500]
        if _FOLLOWUP.search(text) and self.last_question:
            question = (self.last_question[:300] + "；" + text)[:500]
        memories, transcripts = self.store.recall_records(question)
        result = await self.recall.answer(question, memories, transcripts)
        citations = result["citations"]
        memory_ids = [citation["id"] for citation in citations if citation["kind"] == "memory"
                      and not citation["id"].startswith("task:")]
        self._set_targets(memory_ids if len(memory_ids) == len(citations) else [])
        self.last_question = question
        if result["insufficient"]:
            response = result["answer"]
        else:
            response = "记录里是：" + citations[0]["text"][:160]
            if len(citations) > 1:
                response += "。另有相关记录，详细依据已留在手机上。"
            if result["extraction"] == "rules_fallback":
                response += "。AI整理暂不可用，以上是检索到的原文。"
        return self._result(response, intent, citations=citations, recall=result,
                            extraction=result["extraction"], insufficient=result["insufficient"])
