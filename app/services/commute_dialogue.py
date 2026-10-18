"""Required travel slots in the existing account orchestrator; no implicit departure/mode."""
import json
import re
import time
from uuid import uuid4
from .commute_journeys import PlacePoint
from .conversation import ConversationService

MODES = {'公共交通': 'transit', '坐地铁': 'transit', '地铁': 'transit', '公交': 'transit',
         '坐公交': 'transit', '开车': 'driving', '驾车': 'driving', '打车': 'driving',
         '步行': 'walking', '走路': 'walking', '骑行': 'bicycling', '骑车': 'bicycling', '自行车': 'bicycling'}
GPS = {'当前位置', '我这里', '这里', '现在的位置', '我的位置'}
LABELS = {'origin': '起点（具体地址，或说当前位置）', 'destination': '目的地（请带上城市）',
          'transport': '出行方式（驾车、公共交通、步行或骑行）'}


def clean_place(value):
    value = re.sub(r'^(?:我想|我要|想要|准备|我|想|要|是|在|从|到|去)+', '', value.strip())
    value = re.split(r'坐地铁|坐公交|公共交通|开车|驾车|打车|步行|走路|骑行|骑车|自行车|，|。|,|；|;', value)[0]
    return re.sub(r'(?:出发|出发地|玩一下|去玩|玩|游玩|那里|吧|呢|啊)$', '', value).strip(' ：:。 ，,')[:100]


def rule_slots(text):
    result = {}
    modes = {v for k, v in MODES.items() if k in text}
    if len(modes) == 1:
        result['transport'] = modes.pop()
    for key, pattern in [('origin', r'(?:起点|出发地)(?:是|在|为|：|:)?\s*([^，。,；;]+)'),
                         ('destination', r'(?:目的地|终点)(?:是|在|为|：|:)?\s*([^，。,；;]+)')]:
        found = re.search(pattern, text)
        if found:
            result[key] = clean_place(found[1])
    pair = re.search(r'从(.+?)(?:出发)?(?:到|去)(.+)', text)
    if pair:
        result.setdefault('origin', clean_place(pair[1]))
        result.setdefault('destination', clean_place(pair[2]))
    else:
        found = re.search(r'(?:我想去|我要去|想去|准备去|去)([^，。,；;]+)', text)
        if found:
            result.setdefault('destination', clean_place(found[1]))
        found = re.search(r'(?:我在|从)(.+?)(?:出发|，|。|,|$)', text)
        if found:
            result.setdefault('origin', clean_place(found[1]))
    if '回家' in text and not re.search(r'怎么|多久|多远|路线', text):
        result.setdefault('destination', '家')
    return {k: v for k, v in result.items() if v and v not in {'哪', '哪里', '哪儿', '什么地方'}}


class CommuteDialogue:
    def __init__(self, config, store, maps, charge=lambda: True):
        self.config, self.store, self.maps, self.charge = config, store, maps, charge

    def save(self, draft):
        self.store.save_commute_dialogue({**draft, 'updated_at': time.time()} if draft else {})

    async def handle(self, text):
        draft = self.store.commute_dialogue()
        if time.time() - draft.get('updated_at', 0) > 1800:
            draft = {}
        if draft and re.search(r'取消(?:通勤|出行|导航)?|不去了|算了', text):
            self.save({})
            return self.answer('已取消这次出行信息收集。')
        if draft and re.search(r'不去|不是|不要|不从|不坐|不开|不骑|不走', text):
            self.save({})
            return self.answer('请重新确认起点、目的地和出行方式，我会以这次确认的信息为准。')
        slots = rule_slots(text)
        if re.search(r'不去|不从|不是', text):
            slots = {}

        if not draft and not (slots or re.search(r'想去|要去|去哪|出行|导航|出发|通勤|出去玩', text)):
            return None
        active = self.store.commute_journey()
        if active.get('active'):
            return self.answer('当前通勤正在进行。要换目的地，请先在通勤页结束本次行程。')
        # Optional model extracts verbatim place spans only. It cannot supply coordinates or default slots.
        if self.config.text_key and self.config.text_model:
            raw = await ConversationService(self.config).reply(text,
                '只输出JSON，字段origin、destination、transport。提取本句明确说出的出发地、目的地和出行方式。'
                '地点必须逐字复制用户原文的连续片段；未提及填null，禁止推断当前位置。transport只能为'
                'driving/walking/transit/bicycling或null。否定、假设、建议不算确定信息。不要回答问题。')
            try:
                parsed = json.loads((raw or '').strip().removeprefix('```json').removesuffix('```'))
                for key in ['origin', 'destination']:
                    value = parsed.get(key)
                    explicit_role = bool(re.search(r'起点|出发地|从|我在', text)) if key == 'origin' else bool(re.search(r'目的地|终点|去|到', text))
                    if key not in slots and (explicit_role or draft.get('waiting') == key) and isinstance(value, str) and 1 <= len(value) <= 100 and value in text:
                        slots[key] = value
            except (ValueError, TypeError, AttributeError):
                pass
        waiting = draft.get('waiting')
        candidates = draft.get('candidates') or []
        if candidates and waiting in ('origin', 'destination'):
            choice = re.fullmatch(r'(?:第)?([1-5一二三四五])(?:个|项)?[。！! ]*', text.strip())
            if choice:
                n = '一二三四五'.find(choice[1]) + 1 if choice[1] in '一二三四五' else int(choice[1])
                if n <= len(candidates):
                    draft[waiting + '_point'] = candidates[n - 1]
                    draft.pop('candidates', None)
            elif not slots and text.strip() not in {'好的', '嗯', '好', '不知道'}:
                slots[waiting] = text.strip()[:100]
        elif waiting in ('origin', 'destination') and not slots and not re.search(r'不知道|随便|好的|天气|几点|怎么|取消|不去', text):
            slots[waiting] = clean_place(text)
        # Explicit ambiguity is a missing slot, never a guessed default.
        if re.search(r'还是|或者|不坐|不开|不想|不要|能不能|怎么样|推荐', text):
            slots.pop('transport', None)
        for key, value in slots.items():
            if draft.get(key) != value:
                draft.pop(key + '_point', None)
                draft.pop('candidates', None)
            draft[key] = value
        missing = [k for k in LABELS if not draft.get(k)]
        if missing:
            draft['waiting'] = missing[0]
            self.save(draft)
            return self.answer('还需要' + '、'.join(LABELS[k] for k in missing) + '。')
        for key in ['origin', 'destination']:
            if key == 'origin' and draft[key] in GPS:
                continue
            if draft.get(key + '_point'):
                continue
            points = self.saved(draft[key])
            if not points:
                try:
                    result = await self.maps.search(draft[key]) if self.charge() else {'available': False}
                    points = result.get('pois', []) if result.get('available') else []
                except Exception:
                    points = []
            points = [p for p in (self.point(p) for p in points[:5]) if p]
            if len(points) == 1:
                draft[key + '_point'] = points[0]
            else:
                draft['waiting'], draft['candidates'] = key, points
                self.save(draft)
                prompt = '请确认' + ('起点' if key == 'origin' else '目的地') + '：'
                if points:
                    prompt += '；'.join(f'{i + 1}，{p["name"]}，{p["city"]}，{p["address"][:60]}' for i, p in enumerate(points)) + '。请说序号或更完整的地址。'
                else:
                    prompt += '暂时未能定位“' + draft[key] + '”，请补充城市和详细地点，或选择已保存的常用地址。'
                return self.answer(prompt)
        gps = draft['origin'] in GPS
        plan = {'journey_id': str(uuid4()), 'origin_mode': 'gps' if gps else 'fixed',
                'origin': None if gps else draft['origin_point'], 'destination': draft['destination_point'],
                'transport': draft['transport']}
        self.save({})
        return {**self.answer('出行信息已齐，正在准备通勤和路线播报。'),
                'interaction': 'commute_ready', 'commute_plan': plan, 'speak': False}

    @staticmethod
    def answer(text):
        return {'response': text, 'interaction': 'commute_collect', 'extraction': 'rules',
                'memories': [], 'explicit_reply': True}

    def saved(self, name):
        tag = {'家': 'home', '公司': 'work', '学校': 'school'}.get(name)
        return [p for p in self.store.saved_addresses() if p.get('name') == name or tag and p.get('tag') == tag]

    @staticmethod
    def point(value):
        try:
            return PlacePoint.model_validate(value).model_dump()
        except (ValueError, TypeError):
            return None
