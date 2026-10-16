# API 与事件契约

服务默认 `http://localhost:8000`。交互文档 `/docs`，机器可读契约 `/openapi.json`。HTTP 使用 UTF-8 JSON。供应商凭证不出现在这些接口中。

默认每位队友调用自己电脑上的服务，不存在共同云端数据库；Git 协作只同步代码。下面地址均指当前电脑的 localhost。

## 鉴权与约束

配置 `APP_TOKEN` 后，除 `/api/health` 外的 `/api/*` 需要 `Authorization: Bearer <APP_TOKEN>`。WebSocket 在首条 JSON 中提供 token。浏览器写请求拒绝与服务地址不一致的 Origin；WebSocket 校验 Origin 主机。当前共享口令用于单用户 Demo，不是多租户账户系统。

常见状态：`401` 访问口令错误，`403` 跨站请求，`404` 记录不存在，`409` 录音未结束不能清空，`413` 请求过大，`415` 音频类型不支持，`422` 参数或音频校验失败，`502` 上游失败，`503` 独立语音服务未配置。普通请求体检查为 64 KiB，录音识别单独限制为 2 MiB。

## REST 索引

| 方法 | 路径 | 返回 / 作用 |
| --- | --- | --- |
| GET | `/api/health` | `ok/version/auth_required` |
| GET | `/api/state` | Agent、设置、服务配置状态、记忆、转写、事件、草稿、最新 `meeting_report` 与 `action_tasks` |
| PUT | `/api/settings` | 保存完整设置模型，触发模式决策 |
| POST | `/api/text` | 文本进入模式判断、转写与记忆流水线 |
| POST | `/api/location` | 保存当前定位、刷新模式、按节流规则查询路线 |
| GET | `/api/maps/search?q=...&city=...` | 高德目的地 POI 搜索 |
| POST | `/api/maps/nearby` | 当前位置 3 km 内周边 POI |
| GET | `/api/maps/weather?city=310101` | 按六位城市/区县 adcode 查询最近实况天气 |
| POST | `/api/speech/synthesize` | 文字合成，返回 `audio/wav` |
| POST | `/api/speech/transcribe` | WAV / 原始 PCM 识别并进入业务处理 |
| GET | `/api/memories?q=...` | 内容模糊查询，最多 300 条 |
| POST | `/api/memories` | 手动添加记忆 |
| PUT | `/api/memories/{id}` | 更正 `content/kind/pinned` |
| POST | `/api/memories/from-transcript/{id}` | 将指定转写完整原句保存为事实笔记，按来源幂等 |
| GET | `/api/memories/{id}/source` | 返回该笔记明确关联的原始转写 |
| DELETE | `/api/memories/{id}` | 删除指定记忆 |
| POST | `/api/meetings/summarize` | 按时段整理工作转写并保存纪要与行动项 |
| PATCH | `/api/tasks/{id}` | 设置行动项 `status=open/done` |
| POST | `/api/recall` | 本地证据检索与摘录回答 |
| POST | `/api/drafts/{id}/copied` | 标记已复制，`sent=false` |
| POST | `/api/drafts/{id}/dismissed` | 忽略草稿，`sent=false` |
| GET | `/api/export` | v2 JSON：设置、记忆、转写、草稿、`meeting_reports/action_tasks` |
| DELETE | `/api/data` | 清空数据但保留设置；须先停止录音 |
| GET | `/api/events` | SSE 实时通知 |

`/api/state` 是有限快照：通常最多 100 条记忆/转写、40 条事件、30 条草稿、300 条行动项，以及最新一份纪要。记忆优先重要收藏，再按创建时间倒序。导出各类数据最多 10000 条，当前不提供游标分页；不要把快照长度当作数据库总量。纪要返回的 `tasks` 是生成快照；可修改完成状态的任务 ID 在 `action_tasks` 中。

### 设置

`PUT` 是完整替换，省略字段会使用默认值；修改部分字段时先 GET state，再合并完整 `settings` 后提交。

```json
{
  "name": "小聆",
  "auto_mode": true,
  "mode": "life",
  "provider": "cascade",
  "work_start": 9,
  "work_end": 18,
  "timezone": "Asia/Shanghai",
  "life_poll_enabled": false,
  "life_poll_seconds": 300,
  "listen_seconds": 30,
  "vad_enabled": true,
  "vad_threshold": 0.025,
  "preferences": "清淡饮食，喜欢安静的咖啡馆。",
  "destination_name": "公司",
  "transport": "driving",
  "transit_city": "上海",
  "destination_lon": null,
  "destination_lat": null,
  "contact": "老板",
  "arrival_notice_minutes": 5,
  "retain_transcripts": true
}
```

| 字段 | 范围 / 语义 |
| --- | --- |
| `mode` | `work/commute/life`；`auto_mode=false` 时作为手动模式 |
| `provider` | `auto/doubao/qwen/cascade/demo`；新数据库默认 `cascade`，已有设置保留；下次语音连接生效；cascade 为独立 ASR→文本→TTS |
| 工作时段 | 开始 `0..23`，结束 `1..24` 且晚于开始；当前不支持跨午夜班次 |
| `timezone` | 有效 IANA 时区 |
| 轮询间隔 / 监听时长 | `60..3600` 秒 / `5..60` 秒 |
| `vad_threshold` | `0.005..0.2`，前端 RMS 触发阈值 |
| 目的地 | 经度 `-180..180`，纬度 `-90..90`，必须同时填写或同时为空，使用 GCJ-02 |
| `transport` / `transit_city` | `driving/walking/transit`；公交规划目的城市，默认上海 |
| 提醒分钟 | `1..60` |
| `retain_transcripts` | 仅控制原始转写保存，不关闭记忆提取 |

### 文本输入

```http
POST /api/text
Content-Type: application/json
```

```json
{"text":"开始会议，我负责周五前完成前端页面。","speaker":"林同学","source":"text","event_id":"team-demo-001"}
```

`text` 长度 `1..6000`，`speaker` 最多 80 字，`source` 为 `text/asr/demo`。`event_id` 可选，最长 120 字，提供后可避免同一事件重复处理。重复返回 `duplicate=true`；正常返回：

```json
{"mode":"work","memories":[],"response":"已记录0条有效信息，保持安静，不打断会议。","extraction":"rules"}
```

`memories` 的具体数量由内容与模型决定；`extraction` 为 `llm/rules/rules_fallback`。`interaction` 为 `text_llm/rules`，非工作模式的普通文本可使用独立文本模型回答，模型失败则退回模板。该 HTTP 接口返回文本，不自动生成或播放 TTS。

### 定位

```json
{"longitude":121.4737,"latitude":31.2304,"accuracy":20,"speed":6,"source":"gps"}
```

经纬度使用浏览器 WGS-84；`accuracy` 单位米，`speed` 单位米/秒，可为 `null`，`source` 为 `gps/demo`。目的地设置则使用 GCJ-02，不要混用。响应是 Agent 快照，路线不可用时 `route.available=false` 并附原因，不生成虚构 ETA。

### 记忆

```json
{"kind":"preference","content":"我不吃香菜。"}
```

`kind` 为 `preference/task/fact/trip`，内容 `1..6000` 字。返回记录含 `id/kind/content/source/created_at/pinned/updated_at`；SQLite 返回的 `pinned` 为 0/1，新建默认 0，`updated_at` 初始为空。手工添加 `source=user`；机器提取来源为 `llm/rules/rules_fallback`；模拟演示提取加 `demo_` 前缀。`task` 类型仅表示待办线索，不具有完成状态。

更正或收藏使用 `PUT /api/memories/{id}`，提交完整可编辑字段：

```json
{"kind":"fact","content":"公司入口在东侧。","pinned":true}
```

`pinned` 接收布尔值，缺省为 `false`，因此只修改内容时也应带上已有收藏值。返回 `{"ok":true}`；原始 `created_at/source` 保留，`updated_at` 更新。不存在返回 404；空白内容不应提交。

`POST /api/memories/from-transcript/{id}` 无需正文，将指定已保存转写的完整原句存为 `kind=fact`，新笔记默认不收藏。来源明确写成 `transcript:{id}`；模拟转写为 `demo_transcript:{id}`。重复点击复用同一来源笔记，不创建副本，也不重置已有收藏或内容更正。`GET /api/memories/{id}/source` 查询该关联原话；记录无明确关联或原话不存在时返回 404。普通模型提取来源不是精确原话引用，不能凭文本相似度伪造关联。

### 会议纪要与行动项

```http
POST /api/meetings/summarize
Content-Type: application/json
```

```json
{"hours":24,"include_demo":false}
```

`hours` 为 `1..168` 的整数，默认 24；界面提供最近 1 小时、24 小时、7 天。默认排除 `source=demo`。接口先从最近最多 1000 条转写中选择时段内 `mode=work` 的最近最多 80 条；整理服务另限制单条最多 2000 字、总计最多 18000 字。因此这是一段时间的**工作记录汇总**，不代表自动分出某一场会议或完整处理所有历史。

返回持久化报告，包含 `id/created_at/summary/decisions/tasks/source_ids/sources/extraction/hours/includes_demo/cached`。`decisions` 每项为 `text/source_ids`；`tasks` 每项为 `content/owner/due/source_ids`。负责人和截止时间必须有原文依据，未知为空；相对日期保持原文，不换算日历日期。`sources` 是所用转写来源，供人工核对；有效引用不代表结论语义一定正确。

`extraction` 为 `llm/rules/rules_fallback`。无文本模型或输出不合法时使用保守规则整理。相同输入快照命中已有报告，`cached=true`，不重复创建行动项；不同快照可创建新报告与任务，当前不承诺跨报告语义去重。没有可用工作转写返回 422；生成期间数据被清空返回 409，不将旧结果重新写回。

行动项及其可更新 ID 由 `/api/state.action_tasks` 返回。`PATCH /api/tasks/{id}` 请求 `{"status":"done"}` 或 `{"status":"open"}`，返回 `{"ok":true,"status":"done"}` 等；不存在返回 404。状态只记录用户操作，不代表系统代办、催办或确认外部工作完成。

### 问记忆

`POST /api/recall` 请求：

```json
{"question":"我有什么饮食偏好？","include_demo":false}
```

`question` 为 `1..500` 字的有效文本。默认排除演示记忆、演示转写及包含演示的报告所产生的任务。接口读取有上限的本地集合（最多 500 条记忆、500 条转写、300 条行动项）；检索服务还限制候选数。结果不是全库穷尽检索，也不访问互联网补充知识。

返回 `answer/citations/extraction/insufficient`；引用项包含 `id/kind/text/created_at`，`kind` 为 `memory/transcript`。行动项会转换为包含负责人、截止、当前完成状态的证据记录，其引用 ID 带 `task:` 前缀；这类证据是待办表内容，不是录音原话。

模型只筛选所给资料的连续原文摘录，服务验证引用和子串，最多展示四条；无模型或模型失败退回本地摘录。无依据时 `insufficient=true`。关于「未完成」的提问只依据 `action_tasks` 中明确标记的状态，不能从普通 `task` 记忆推断完成情况。清空期间回答返回 409。界面在资料改变后要求重新提问，避免旧证据继续作为当前结论。

### 导出与清空

`GET /api/export` 的 `version=2`；除原有字段外包含 `meeting_reports` 与 `action_tasks`，记忆包含收藏和修改时间。导出的是本地快照；尚无一键导入 API。

`DELETE /api/data` 同时清空转写、记忆、纪要、行动项、草稿、事件和去重记录，保留设置；进行中的旧纪要/问答不能在清空后重新出现。仍须先停止录音。

草稿仅允许 `pending → copied` 或 `pending → dismissed`，重复处理会返回未找到/已处理；复制并不等于已经发送。

## 独立语音 API

独立 ASR/TTS 与豆包实时双工、Qwen 会话互不替代鉴权。它们可作为文件识别和文字朗读能力单独联调。

`POST /api/speech/synthesize`：JSON `{"text":"你好，我是小聆。"}`，文本长度 `1..1500`，返回单声道 24 kHz PCM16 WAV。失败时返回 JSON 错误，客户端必须先检查响应状态与 Content-Type，再交给播放器。此接口不自动写记忆。

`POST /api/speech/transcribe`：请求体是音频文件本身，不是 multipart，也不是 JSON Base64。支持 16 kHz、单声道、16 bit PCM WAV，或 Content-Type 为 `audio/pcm` 的无头 little-endian PCM16。最大 2 MiB，不支持浏览器默认 WebM/Opus 直接上传。后端按 `SPEECH_ASR_MODE=stream/flash` 选择识别方式，默认 ASR 2.0 stream，然后进入 `Agent.ingest()`；返回 `text` 及模式、记忆、文本回复、提取来源。无有效语音时返回 `text=""`。此 API 不会自动把返回回复合成为语音；即使后端用流式 ASR，该 HTTP 接口仍接收完整录音并返回最终 JSON。

```sh
curl -X POST http://localhost:8000/api/speech/transcribe \
  -H 'Content-Type: audio/wav' --data-binary @sample-16k-mono.wav
curl -X POST http://localhost:8000/api/speech/synthesize \
  -H 'Content-Type: application/json' \
  -d '{"text":"你好，我是小聆。"}' --output greeting.wav
```

如配置 APP_TOKEN，补充鉴权头。这些调用可能产生供应商费用。

## 地点与天气 API

- 搜索：`GET /api/maps/search?q=咖啡&city=上海`。`q` 为 `1..100` 字，`city` 可选。响应 `available/source/pois/coordinate_system/reason`；每个 POI 包含名称、地址、经纬度、城市、adcode，坐标为 GCJ-02，可直接保存为目的地。
- 周边：`POST /api/maps/nearby`，JSON 与定位请求相同，另加 `keyword`（默认「美食」，最多 80 字）。输入位置仍是 WGS-84，服务先转换后在 3 km 内查询；最多返回 10 个 POI。POI 存在不等于商家当前营业。
- 天气：`GET /api/maps/weather?city=310101`。必须使用六位 adcode，可从 POI 搜索结果取得。返回天气、温度、湿度、风向、风力、发布时间和来源，不是对未来天气的预测。

缺少 Key 返回 `available=false`，上游失败返回 502。保存设置 `transport=driving/walking/transit` 选择路线方式，`transit_city` 是公交规划目的城市。公共交通结果标记 `scheduled=true`，仅计划线路/时刻，不是实时车辆遥测；路线细节在 `steps`，部分字段可能为空。

## SSE

请求 `GET /api/events`，接收 `text/event-stream`。由于需要鉴权头，前端采用 `fetch()` 读取流而非原生 EventSource。

```text
data: {"id":"...","type":"mode.changed","payload":{"from":"life","mode":"work","reason":"对话出现明确会议场景"},"created_at":"2026-10-17T00:00:00+00:00"}

```

每 15 秒可能收到注释心跳 `: heartbeat`。当前不支持 `Last-Event-ID` 历史重放，断线后重新 GET state。

| 事件 | 主要 payload |
| --- | --- |
| `mode.changed` | `from/mode/reason` |
| `settings.updated` | `mode/auto_mode` |
| `transcript.final` | `speaker/text/mode/source`，持久化时含 ID/时间 |
| `memory.created` | 新记忆记录 |
| `memory.updated` | 已更正或收藏的记忆 `id`；重新取状态获取完整内容 |
| `meeting.created` | 新纪要 `id` 与行动项数量 `tasks`；缓存命中不重复创建 |
| `task.updated` | 行动项 `id/status` |
| `memory.deleted` | `id` |
| `agent.processed` | `mode/extraction/memory_count/latency_ms/source` |
| `location.updated` | `location/route` |
| `notification.proposed` | `message/draft/origin`；`origin=location` 区分定位触发与转写触发，供界面选择是否朗读 |
| `life.listen_requested` | `seconds`；前端仍需本次授权与页面可见性检查 |
| `hardware.notice` | ANC 待 SDK 提示 |
| `audio.connected` | `provider/session` |
| `audio.disconnected` | `session` |
| `data.cleared` | 空对象 |

事件总线不是可靠消息队列；慢订阅者可能丢最旧事件，业务持久状态以数据库快照为准。

## 音频 WebSocket

地址 `ws://localhost:8000/ws/audio`；HTTPS 部署使用 `wss://`。首条消息须在 10 秒内发送：

```json
{"type":"start","token":"","duration":3600}
```

`duration` 限制为 `5..3600` 秒。供应商由已保存设置选择；连接成功后：

```json
{"type":"ready","provider":"qwen","input_rate":16000,"output_rate":24000}
```

收到 ready 后才上传二进制 PCM：**16 kHz、单声道、有符号 little-endian PCM16，无 WAV 头**。每帧长度必须为偶数且不超过 32000 字节。下行二进制音频：**24 kHz、单声道、PCM16 little-endian**。

`provider=cascade` 复用同一地址和音频格式。默认 `SPEECH_ASR_MODE=stream` 时 ready 包含 `provider="cascade"`、`asr_mode="stream"`、`tts` 标记；服务器持续向 ASR 2.0 发送每帧 PCM（含静音），不由本地 VAD 切段，返回部分 `transcript.delta` 和去重的最终 `transcript`。记忆处理与 TTS 独立于持续识别；工作模式仅记录，TTS 未配置返回文字。

可选 `SPEECH_ASR_MODE=flash` 才是服务端 VAD 切句后 HTTP 识别，通常没有逐字部分结果。两者均为独立 ASR→文本→TTS 组合，不是原生实时模型；`auto` 不自动选择 cascade，须显式保存。通道切换或修改 ASR 环境变量后重启后端并开始新会话。

原生实时通道可选文字控制消息（cascade 当前不处理 `text` 注入，文字请用 REST `/api/text`）：

```json
{"type":"text","text":"你好，请说一句简短问候。"}
```

```json
{"type":"stop"}
```

正常停止时，浏览器立即释放麦克风并停止播放，再发送 `stop`，最多保留连接 25 秒等待后端最终识别和记忆保存；快速重新开始会等待旧连接收尾。自定义客户端也应发送 stop 后等待服务器关闭，不要立即切断 WebSocket。关闭浏览器、断网或非正常断连不承诺保存最后一句。

`text` 是向实时模型注入文字，不等于 REST `/api/text` 的完整业务入库；若供应商没有返回输入转写事件，不应假定这条文字自动成为记忆。

| 下行 JSON 类型 | 字段与作用 |
| --- | --- |
| `ready` | `provider/input_rate/output_rate` |
| `provider.failed` | 本次建连失败，可继续尝试备用 |
| `error` | `message`，显示原因并准备结束/重连 |
| `transcript.delta` | 部分文本 `text` |
| `transcript` | 最终 `text/event_id`，后台同时排队处理 |
| `assistant.delta` | 助手文本片段 |
| `interrupt` | 用户发言活动，停止本地排队播放 |
| `response.done` | 当前输出完成 |
| `metric` | `name=session_to_first_audio_ms`，`value` 为毫秒 |

关闭码 `1008` 表示策略拒绝，例如口令、Origin 或会话限制。一次仅支持一个语音会话；不要共享同一后端供多个独立用户同时录音。

## 供应商协议参考

- 豆包 JSON 双工参考：`web_duplex_demo/web_duplex_demo/server.py` 与同目录 `README.md`。
- Qwen 比赛 MaaS 参考：`qwen_omni_demo/server.py` 与 `qwen_omni_demo/README.md`。
- 主应用归一化实现：`app/providers/realtime.py`。
- 连续 ASR 2.0：[火山官方文档](https://docs.volcengine.com/docs/DoubaoVoice/LargemodelstreamingautomaticspeechrecognitionAPI?lang=zh)，实现 `app/providers/volc_speech.py` 与 `app/services/streaming_cascade.py`。

示例代码用于协议参考，不是权威的产品权限说明。更换端点、模型和账号时重新验证握手事件、音频格式及输入转写；不要默认套用其他版本二进制语音协议或 DashScope 配置。
