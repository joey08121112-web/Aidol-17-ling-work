# 体验修复计划（2026-10）

分支：`feature/ux-fixes-and-ai-prompt`。每个任务单独提交，一个任务验证通过再开下一个，节省额度。

## 分工原则

| 模型 | 负责 | 原因 |
|---|---|---|
| **Claude** | 后端 Python（`app/`）、系统提示词、API 契约与文档、供应商能力调研、审查 ChatGPT 的改动 | 跨模块推理、契约设计、提示词措辞 |
| **ChatGPT（Codex）** | Android UI（`android-app/.../MainActivity.java` 等），严格按本文的验收标准实现 | 范围明确的单文件 UI 改动 |

规则：

1. **先契约后界面**：需要后端字段的任务，Claude 先合入后端和 `docs/api.md`，ChatGPT 再改 Android。
2. **同一时间只允许一个模型改 `MainActivity.java`**（2000 行单文件，冲突代价高）。
3. 每个任务完成后跑对应校验：后端 `.venv/bin/python -m pytest tests -q`；Android `./gradlew testDebugUnitTest lintDebug assembleDebug`。
4. 真实模型 / 供应商调用会消耗额度，只在任务写明"实测"时做一次，并把结果记到 `docs/acceptance.md`。

## 审查发现的问题清单

### A. AI 把查询推给用户（最高优先级）

| # | 位置 | 问题 |
|---|---|---|
| A1 | `app/services/agent.py:263-264` | 系统提示词写着"可用 web_search 查**公开购票渠道**"和"实际下单需用户在官方渠道完成"。模型据此回答"请您到官方渠道查询"——这就是查机票时被推回给用户的根源。**已修复** |
| A2 | `app/services/assistant_tools.py:56` | `web_search` 工具描述强调"官方购票渠道""不支持下单"，没要求模型从结果里提炼具体信息。**已修复** |
| A3 | `app/services/assistant_tools.py:99` | 定位失效时工具返回"需在通勤页重新定位"，AI 会让用户自己去通勤页点按钮。应由 App 在通话开始时自动上报位置（见 C6） |
| A4 | `app/services/agent.py:165` | 无模型时的通勤兜底回复"实时路线与到站时间需查询确认"，也是推给用户 |
| A5 | `app/services/agent.py:191-200` | `recommend()` 固定话术"尚未查询附近营业商家"——有定位时应直接调 `nearby_places` |
| A6 | 能力缺口 | 机票、酒店目前只能用博查网页搜索，拿到的是网页摘要，价格和余票不实时。要准确报价得接专门的航班 / 酒店 API（需选供应商、申请 Key） |

### B. 对话记录

| # | 位置 | 问题 |
|---|---|---|
| B1 | `MainActivity.java:1881` | 时间格式 `MM-dd HH:mm`，没有年份和秒。要求 `yyyy-MM-dd HH:mm:ss` |
| B2 | `MainActivity.java:386-389` | 首页 `liveWords` 最多显示 4 行，只显示当前这一句；说过的话要等整轮结束刷新后才进"最近对话"。要求通话过程中逐句追加、自动滚动 |
| B3 | `MainActivity.java:749-752` | 会议原文只显示"片段 N"，没有时间。数据里已有 `created_at`，只是没显示 |
| B4 | `app/services/work_meetings.py:585`、`MainActivity.java:749` | 不区分说话人，多人对话全部混在一起（见 D） |

### C. 通勤页（导航栏第 4 个）

| # | 位置 | 问题 |
|---|---|---|
| C1 | `app/domain.py:24-28` | 只有一个"目的地"，没有"家"和"公司"。通勤本来就是家↔公司往返，应保存两个地点，根据当前位置自动判断方向（在家附近→去公司，在公司附近→回家） |
| C2 | `MainActivity.java:899` | 目的地只能手动输入。要求输入框右侧加📍定位按钮，点一下自动定位并填入当前位置（调用已有的 `/v1/maps/reverse`） |
| C3 | `MainActivity.java:898` | "所在城市"需要手填，默认"上海"。有定位时应自动填入 |
| C4 | `MainActivity.java:911` | "仅在点击时获取位置"——不是实时定位。通勤进行中应持续定位（前台每 15–30 秒一次），到达或离开页面时停止；后端已有 30 秒路线节流（`agent.py:223`） |
| C5 | `MainActivity.java:917, 924` | "搜索周边""附近地铁站"会提示"请先点击定位并更新路线"。应自动先定位再搜索 |
| C6 | `MainActivity.java:1033` | 点"定位"会强制 `mode=commute`、`auto_mode=false`，悄悄关掉了自动模式切换 |
| C7 | `MainActivity.java:928-949` | "目的地天气预报"和"查看目的地天气"两个按钮重复；后者依赖本机缓存的 adcode，换手机后会失败 |
| C8 | `app/domain.py:31` vs `MainActivity.java:951` | 联系人默认值后端是"老板"、界面是"同事"，前后不一致 |

### D. 多人说话人区分

现在的流式识别（火山 / Qwen）只返回文字，没有说话人标签。实现路线：

1. **调研（Claude，需实测 1 次）**：确认现有供应商哪个接口支持说话人分离。候选是火山"录音文件识别"的说话人信息参数、阿里 Paraformer 录音文件转写的 `diarization_enabled`。**两者都要先用合成双人音频跑一次 probe 确认，不要凭文档直接实现。** 流式接口大概率不支持，所以方案是：实时显示时先标"说话中"，每段录音结束后用文件接口重新识别，回填"说话人1 / 说话人2 / …"。
2. **后端（Claude）**：`transcripts` 加 `speaker_label` 列（按会议编号，第一个出现的人是说话人1，有几个人就有几个编号）；会议纪要提示词带上标签；更新 `docs/api.md`。
3. **Android（ChatGPT）**：原文和首页气泡显示"说话人N · 时间"。
4. 说明：这是区分**不同的声音**，不是识别**是谁**（不做声纹注册）。界面文案不能写成"已识别身份"。

## 执行顺序

| 顺序 | 任务 | 模型 | 依赖 | 状态 |
|---|---|---|---|---|
| 1 | A1+A2 系统提示词与工具描述 | Claude | — | ✅ 已完成，后端测试 464 通过 |
| 2 | A1 实测：真实问一次"帮我查明天上海到北京的机票" | Claude | 1 | 待做（消耗额度） |
| 3 | B1+B3 时间戳到秒、会议片段显示时间 | ChatGPT | — | 待做 |
| 4 | B2 首页实时逐句显示 | ChatGPT | 3 | 待做 |
| 5 | C1 后端：家 / 公司两个地点 + 自动判断方向 + 迁移旧目的地 | Claude | — | 待做 |
| 6 | C1–C3、C5–C8 通勤页 UI 重做 | ChatGPT | 5 | 待做 |
| 7 | C4+A3 通勤中持续定位；通话开始自动上报位置 | ChatGPT（Claude 审查隐私和耗电） | 6 | 待做 |
| 8 | A4+A5 其余兜底话术 | Claude | 1 | 待做 |
| 9 | D1 说话人分离调研 + probe | Claude | — | 待做（消耗额度） |
| 10 | D2+D3 说话人标签落库与显示 | Claude → ChatGPT | 9 | 待做 |
| 11 | A6 航班 / 酒店专用 API 选型 | Claude 调研，需你决定供应商 | — | 待决定 |

## 交给 ChatGPT 的提示词模板

> 仓库 Aidol，分支 `feature/ux-fixes-and-ai-prompt`。先读 `AGENTS.md` 和 `docs/ux-fix-plan-2026-10.md`。
> 只做任务 #N：〈粘贴该行问题和验收标准〉。只改 `android-app/`，不改 `app/` 后端。
> 保持 `MainActivity.java` 现有的代码风格（程序化构建 View、单行紧凑写法）。
> 完成后在 `android-app/` 运行 `./gradlew testDebugUnitTest lintDebug assembleDebug`，贴出结果，提交一个 commit，信息写清任务编号。

ChatGPT 每完成一个任务，交回 Claude 用 `/code-review` 审查后再开下一个。
