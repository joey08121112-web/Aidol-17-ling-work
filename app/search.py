"""Local lexical index terms. No private corpus is sent to an indexing vendor."""
import re


def search_terms(text):
    terms = set(re.findall(r"[a-z0-9]+", text.lower()))
    for run in re.findall(r"[\u4e00-\u9fff]+", text):
        terms.update(run[i:i + 2] for i in range(len(run) - 1))
        if len(run) == 1:
            terms.add(run)
    return terms


def index_text(text):
    return " ".join(sorted(search_terms(text or "")))


def query_terms(question):
    cleaned = re.sub(r"请问|告诉我|帮我|查询|搜索|记得|什么|哪些|怎么|多少|有没有|是不是|之前|上次|最近|关于|一下|我的|我们", " ", question)
    terms = search_terms(cleaned)
    for pattern, synonyms in (
        (r"答应|承诺|待办|任务|事项|未完成|没完成|负责|截止|要做", "答应 承诺 负责 提交 截止 需要 任务 完成 待办"),
        (r"饮食|口味|偏好|爱吃|喜欢吃|忌口|过敏|吃什么", "喜欢 不吃 过敏 偏好 清淡 口味 忌口 素食 辣"),
        (r"会议|开会|怎么定|决定|决策|讨论", "会议 开会 决定 确定 定为 讨论 结论 方案"),
        (r"行程|出差|航班|旅行|去哪|出发", "出发 前往 航班 地铁 出差 行程 旅行"),
    ):
        if re.search(pattern, question):
            terms.update(synonyms.split())
    return terms


def is_non_assertion(text, speaker="用户"):
    """Conservative gate shared by extraction and recall of raw transcripts."""
    return (speaker.lower() in {"assistant", "system", "助手", "小聆"}
            or bool(re.search(r"[？?]|[吗么呢][。！!\s]*$|是不是|是否|有没有|为什么|怎么|(?<!没)什么|哪[个家天次里些]|何时|几[点号天]|多少|\b(?:what|when|where|why|how|is|are|do|does|did|can|could|would)\b", text, re.I))
            or bool(re.search(r"(?:如果|假如|假设|也许|可能|不确定|好像|听说|据说|开玩笑|比如|例如)", text)))
