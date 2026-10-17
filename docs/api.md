# Android 多账号 API 契约

## 服务入口

APK 内置基址 `https://123.207.219.174:7776`，由 `create_hosted_app` 提供。本地开发使用同一工厂与接口，基址 `http://127.0.0.1:8108`，手机通过 USB reverse 访问；账号与数据独立于远端。所有 JSON 为 UTF-8；供应商凭证不下发。除健康检查、注册、登录、恢复密码外，均需要 `Authorization: Bearer <登录返回的 token>`。响应设置 `Cache-Control: no-store`、`X-Content-Type-Options: nosniff` 和 `X-Frame-Options: DENY`。公开服务不提供 `/docs`、`/openapi.json`、旧 `/api/*`、Web 页面、SSE 或旧 `/ws/audio`。新增鉴权音频 WebSocket 见下方实时语音协议。

### 账号与会话

账号 `username` 为 3–24 位字母、数字或下划线，按 ASCII 小写归一后全站唯一，例如 `Alice_01` 与 `alice_01` 是同一个账号。密码 8–128 位，不要求邮箱、手机号或验证码。服务器保存随机盐 scrypt 派生值，不保存明文密码；Bearer 与恢复码仅保存摘要。

| 方法/路径 | 请求 | 成功响应 |
| --- | --- | --- |
| `GET /healthz` | 无 | `ok/version/ready`；ready 表示配置齐备，不代替实际供应商权限探测 |
| `POST /auth/register` | `{username,password}` | 201，`token/expires_in/user/recovery_code`；重复账号 409 |
| `POST /auth/login` | `{username,password}` | `token/expires_in/user`；账号不存在和密码错误均为同一 401 提示 |
| `POST /auth/recover` | `{username,password,recovery_code}`；password 为新密码 | 新会话与新恢复码；旧恢复码、旧会话失效 |
| `GET /auth/me` | Bearer | `user` 对象本身：`id/username/created_at` |
| `POST /auth/logout` | Bearer | `{ok:true}`；撤销本次 token，其他设备不受影响 |
| `PUT /auth/password` | `{old_password,new_password}` + Bearer | 新会话；所有旧设备会话失效；原恢复码仍有效 |
| `DELETE /auth/account` | `{password}` + Bearer | `{ok:true}`；删除身份、会话及用户业务库；已删除账号名可重新注册，得到不同 UUID |

登录会话 30 天，`expires_in=2592000`，每个账号最多五个会话，新增会话淘汰最早的旧会话。注册和恢复密码响应中的恢复码需要用户自行保存；没有邮箱/短信兜底，服务端不提供查看恢复码接口。账号删除只影响在线库；运维历史备份的保留/删除按 [部署文档](android-deployment.md) 管理。

### 业务索引

下表全部鉴权，并只读写当前账号的 UUID 库。其他账号的对象 ID 不构成授权，访问返回 404。除表中专门声明，成功响应为 200。

| 方法/路径 | 请求与响应 |
| --- | --- |
| `GET /v1/state` | 返回 `user/settings/capabilities/agent/transcripts/memories/tasks/drafts/meeting_report`；刷新时重新判断当前模式 |
| `PUT /v1/settings` | 完整 `Settings` JSON；返回保存后的设置。固定 `provider=cascade`、`life_poll_enabled=false`；目的地/交通参数变化清除旧路线与提醒节流 |
| `POST /v1/chat` | `{text,request_id,interaction_source?}`；text 1–6000 有效字符；返回 `text/mode/memories/response/extraction/interaction/speak/notice`，记忆操作另含 `citations/recall/deletion` 等回执 |
| `POST /v1/voice?request_id=...&interaction_source=direct_question` | `Content-Type: audio/pcm`，16 kHz 单声道小端 PCM16 原始字节；不带 WAV 头；返回与 chat 相同结构，未识别时返回空 text、解释 response、`speak=false` |
| `GET /v1/turns/{request_id}` | 同账号只读请求状态，不占业务锁、不计模型额度；`request_id/status/stage/retryable/result`；不存在 404 |
| `POST /v1/speech` | `{text}`，1–1500 字符；返回 24 kHz 单声道 PCM16 的 `audio/wav` |
| `POST /v1/memories` | `{content,kind}`，content 1–6000 有效字符，kind 为 `preference/task/fact/trip`；返回保存记录 |
| `PUT /v1/memories/{id}` | `{content,kind,pinned}`；更正/收藏，`{ok:true}` |
| `DELETE /v1/memories/{id}` | `{ok:true}` |
| `POST /v1/memories/from-transcript/{id}` | 将本人转写完整原句保存为事实笔记；重复调用按来源返回同一笔记 |
| `GET /v1/memories/{id}/source` | 返回明确关联的本人原始转写；手动笔记等无来源则 404 |
| `POST /v1/recall` | `{question}`，1–500 有效字符；返回 `answer/citations/extraction/insufficient`；全历史索引检索，行动项带真实完成状态参加检索 |
| `POST /v1/meetings` | `{hours}`，1–168，默认 24；筛选本人时段内的环境工作转写，排除助手和私人提问；有界生成后返回 `id/created_at/summary/decisions/tasks/source_ids/extraction/sources/coverage/hours/includes_demo`；同快照复用报告，无记录 422 |
| `PATCH /v1/tasks/{id}` | `{status:"open"或"done"}`；`{ok:true}`；可修改的任务 ID 从 state.tasks 取得 |
| `GET /v1/maps/search?q=...&city=...` | q 1–100 字符，city 最多 80；高德地点搜索结果，沿用下方地图结构 |
| `POST /v1/location` | `{longitude,latitude,accuracy?,speed?,source?}`，WGS-84 定位；返回 agent 快照，可能更新模式、路线与提醒草稿 |
| `POST /v1/maps/nearby` | 定位字段加 `{keyword}`，默认“美食”；高德 3 km 周边结果 |
| `GET /v1/maps/weather?city=310000` | city 可选，为六位 adcode；省略/留空则用本人保存的目的地坐标反查区域后查询天气，无目的地 422；返回高德实况天气结构 |
| `POST /v1/drafts/{id}/{action}` | action 仅 `copied/dismissed`；返回 `{ok:true,status,sent:false}`；不支持真实发送 |
| `GET /v1/export` | v3 JSON：`version/user/settings/memories/transcripts/reports/tasks/drafts` |
| `DELETE /v1/data` | `{password}`；清空业务数据和对话幂等结果，保留账号与设置；返回 `{ok:true}` |

`PUT /v1/settings` 是完整替换：先取 state.settings 再合并修改。字段取值范围沿用下方“设置”，Android 强制值优先；新账号默认手动生活模式，偏好为空。关闭 `retain_transcripts` 阻止新增转写历史，但不清除旧数据，也不阻止抽取记忆或幂等结果保存；完整清除使用 `/v1/data`。

服务端 `life_poll_enabled=false` 禁止服务器触发后台采集，不妨碍 Android 在当前前台页面、用户本次主动开启后按 `life_poll_seconds`（60–3600，默认 300）及 `listen_seconds`（5–60，默认 30）调度有界录音。连续模式也是一轮识别/回复/播放完成后开启下一轮；各轮单独使用 HTTP 或新的有界 WebSocket 上传，不是持续全双工。停止、页面失去前台或错误会停止自动接续。用户另外开启本次锁屏耳机会话后，可在宿主仍存活期间继续手动短语音；它不授权后台连续或定时开麦。

`interaction_source` 仅允许 `direct_question`（用户定向提问/命令）与 `ambient_capture`（环境记录），省略时兼容旧版为后者。新版 Android 的单轮触控、手动文字使用前者，连续/定时采集使用后者。工作模式环境记录保持安静，不执行原文中的删除等命令；显式提问允许简短答复。三种模式共用同一个账号编排器及记忆上下文。

定向记忆操作支持“记住，周四交方案”→问回→“后来改成周五了”→“忘记刚才那条”。`interaction` 为 `memory_remember/recall/correct/forget`。更正、遗忘仅接受十分钟内唯一命中的记忆，歧义、已失效指代或不存在目标返回明确解释，不猜测删除对象；普通新话题会清理旧指代。日期局部替换要求原文只有一个明确日期，否则要求完整更正。纯转写或多条任务/记忆引用不能直接口头更改，当前任务状态仍通过 `/v1/tasks` 操作。保存约定不代表设置提醒或生成可调度任务。

索引在当前账号 SQLite 内用 FTS5 存储中文双字词与英文词，首次启动回填全历史，写入/更正/删除由同事务触发器同步。结构条件先于候选限额：上个月按账号时区的**记录时间**筛选，未完成按任务真实状态筛选；不是事件发生日期解析。每类最多取 100 条候选，再选择最多 8 条证据、回答最多 4 条引用。关键词索引覆盖旧记录，仍不等于任意同义表达均能命中。问题、假设和助手输出不作为事实证据；模型只选择可核验连续原文，失败时明确降级为原文摘录。

更正保留原始来源供 `/source` 核对，但原文不再作为当前召回或普通对话依据。口头遗忘删除选定记忆、同来源派生记忆、关联原始转写、引用它的纪要和任务，并返回 `deletion` 计数；其他独立记录、运维备份不在此次范围。旧 `DELETE /v1/memories/{id}` 保持仅删记忆语义，但屏蔽关联旧原文的召回。上述修改均失效已完成请求正文缓存、清理助手历史，保留请求身份以防旧请求重放恢复内容；旧结果重试返回 `invalidated=true`。单纯关闭转写不是临时会话。

纪要 `coverage` 披露可用/纳入/遗漏片段、截短来源与记录首尾时间；`continuous_capture_verified=false` 明示这些信息不能证明没有采集缺段。模型仍最多使用 80 条、18000 字材料。新增片段后重生成纪要时，仅内容、负责人、期限相同且来源重叠的任务复用旧 ID/完成状态；语义改写仍需后续任务关联设计。

state 最多返回 100 条转写（按时间正序）、300 条记忆、300 条行动项、30 条草稿及最新纪要；记忆按收藏优先及时间倒序。导出各集合最多 10000 条，暂不提供分页。纪要中的 tasks 是生成时的草稿快照，当前完成状态以 state.tasks 或 export.tasks 为准。`include_demo` 为兼容模型字段，不会为托管账号注入模拟内容。

### 幂等、限流和失败语义

M1 Android 复用 HTTP 短语音，无新增实时接口。含异步停止尾帧最高60秒/1920000字节；停止确认/队列排空前不得提交，超限不截断伪装成功。客户端取消尽力中止网络，不能撤销服务端已保存内容；结果未知或409先看历史，不能自动换ID重放。

诊断：chat/voice沿用 `request_id`；speech可带 `X-Aidol-Request-ID: <同轮ID>`，格式 `[A-Za-z0-9-]{16,64}`，缺失或无效时生成诊断ID，不记录无效原文。此头仅关联日志，不提供speech幂等、鉴权或额度豁免。`uvicorn.error.aidol.mobile`记录cache/asr/business/turn/tts阶段、状态与耗时，不记录正文、音频、凭证或账户信息。客户端debug并列显示本地session ID与request ID。

`request_id` 必须为 16–64 位字母、数字或连字符，建议使用 UUID；范围是当前账号。聊天/语音已完成请求使用相同 ID、相同文本或 PCM 及 `interaction_source` 指纹重试时，返回原结果，不重复写入、模型调用或额度消耗（记忆变更后旧正文撤回，见上）。不同内容/来源返回 409。状态为 `processing/completed/failed`，阶段为 `asr/business/completed`；ASR失败或进程中断在ASR阶段，可使用同 ID、同内容重试并重新计费。进入 business 后失败可能已有副作用，`retryable=false`，重放返回409，先核对历史；不能自动换ID重做。状态查询在正在处理时仍可读取，活动账号锁期间返回 `retryable=false`。幂等不等于供应商事务，进程异常中断不能保证上游没有消耗费用。

| 限制 | 默认值与口径 |
| --- | --- |
| 开放注册 | `AIDOL_REGISTRATION_OPEN=true`；关闭后现有账号仍可登录 |
| 注册容量 | `AIDOL_MAX_USERS=500`；达到容量后注册 403 |
| 鉴权尝试 | 每来源 IP 40 次/900 秒、每归一账号 15 次/900 秒；注册、登录、恢复、改密、清空与注销共用该计数，超限 429；不信任客户端自行写的 X-Forwarded-For |
| 业务额度 | `AIDOL_DAILY_REQUESTS=300`；每账号从窗口首次操作起计 24 小时，到期重置，不是北京时间午夜；不是逐请求滑动时间序列，也不是资金预算 |
| 计数操作 | 新 chat/voice、每次 speech、recall、meetings、地图 search/nearby/weather、location 各计一次；聊天后合成语音通常两次，录音后合成语音也通常两次。已完成 chat/voice 重试不计；其他操作可能即使上游失败或会议命中缓存仍计数 |
| 同账号并发 | 同时一项业务操作；第二项 409。其他账号可独立操作，全局最多八项活动业务操作，满时 503 |
| 请求体 | 文字/普通请求最多 65536 字节；voice 最多 1920000 字节（60 秒）。按实际接收长度检查，不能通过省略 Content-Length 绕过 |
| 音频长度 | voice 至少 3200 字节且字节数为偶数；单次最多 60 秒；ASR 处理有 65 秒上限 |

常见状态：400 不支持的草稿动作；401 会话/密码无效；403 注册关闭或名额满；404 当前账号内不存在；409 操作冲突、请求 ID 冲突或凭据已变更；413 超大请求；415 音频类型错误；422 输入无效或无会议内容；429 请求过于频繁/额度用尽；502 识别/合成/地图服务失败；503 配置不全或服务繁忙。正文为 `{detail:可展示的提示}`，不回显供应商密钥或内部错误体。

工作模式环境记录 `speak=false`；`direct_question` 允许 `speak=true`，此值由客户端遵守。文字模型不可用时允许规则降级，`interaction=rules` 和 `notice` 解释实际来源。附近 POI 不证明商家正在营业，公交计划不等于实时车辆位置，草稿复制不等于发送。原文出现“下一站”不自动生成到站草稿；ETA 引用还要求近期路线和足够精确的定位。

## 设置

`PUT` 是完整替换，省略字段会使用默认值；修改部分字段时先 GET state，再合并完整 `settings` 后提交。

```json
{
  "name": "小聆",
  "auto_mode": false,
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
  "preferences": "",
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
| `provider` | 固定为 `cascade`；独立 ASR → 文本 → TTS，不能由客户端切换原生实时供应商 |
| 工作时段 | 开始 `0..23`，结束 `1..24` 且晚于开始；当前不支持跨午夜班次 |
| `timezone` | 有效 IANA 时区 |
| 轮询间隔 / 监听时长 | `60..3600` 秒 / `5..60` 秒 |
| `vad_threshold` | `0.005..0.2`，客户端 RMS 触发阈值 |
| 目的地 | 经度 `-180..180`，纬度 `-90..90`，必须同时填写或同时为空，使用 GCJ-02 |
| `transport` / `transit_city` | `driving/walking/transit/bicycling`；公交规划目的城市，默认上海 |
| 提醒分钟 | `1..60` |
| `retain_transcripts` | 仅控制原始转写保存，不关闭记忆提取 |

## 地点与天气响应

- `GET /v1/maps/search` 返回 `available/source/pois/coordinate_system/reason`；每个 POI 包含名称、地址、经纬度、城市和 adcode。结果坐标为 GCJ-02，可直接保存为目的地。
- `POST /v1/location` 和 `/v1/maps/nearby` 接收 WGS-84 坐标；附近搜索先转换坐标，查询 3 km 内最多 10 个 POI。`keyword` 默认为“美食”，最多 80 字。
- `GET /v1/maps/weather` 接收六位 adcode，省略时从当前账号目的地反查；返回天气、温度、湿度、风向、风力、发布时间和来源，属于实况。未配置目的地时不能省略 city。

缺少地图 Key 返回 `available=false`，上游失败返回 502。`transport=driving/walking/transit` 选择路线方式，`transit_city` 是公交规划目的城市。公交结果标记 `scheduled=true`，只有计划线路/时刻；路线细节在 `steps`，部分字段可能为空。

## 记忆与来源语义

`POST /v1/memories/from-transcript/{id}` 不需要正文，读取本人已保存的完整原句，保存 `kind=fact`、`source=transcript:{id}`，默认不收藏。重复调用复用同来源笔记，保留用户后续更正和收藏。`PUT /v1/memories/{id}` 提交完整可编辑字段 `content/kind/pinned`，不修改原始转写。自动提取方式与精确转写引用是不同来源，不能凭相近文字猜测原话。

会议从最近最多 1000 条转写中选择时段内最多 80 条工作发言；模型输入另有限额，不能承诺覆盖时段全部记录。同一输入快照复用报告，不同快照可能形成相似任务；报告是生成时的快照，后续编辑记忆不会自动重算旧报告。普通 `kind=task` 记忆是线索，完成状态只来自行动项表。

问记忆检索当前账号的有界记忆、转写和行动项，返回引用与原文摘录。引用 ID 和摘录子串需通过校验，资料不足时 `insufficient=true`；不得补造事实或将记录内容当成系统指令。

`GET /v1/export` 返回 v3 快照，当前没有导入接口。`DELETE /v1/data` 要求当前密码，删除业务数据及幂等结果、保留账号和设置；账号注销使用 `DELETE /auth/account`。删除在线数据不自动删除运维历史备份，备份规则见部署文档。

## 当前范围

应用只提供本文件列出的账号与业务 REST API。旧单用户浏览器接口和供应商 Demo 已移除。M2 连续语音需要另行定义账号鉴权、停止、并发及持久化语义；产品规划中的候选地址不属于已实现接口。


## 2026-10-17 实时传输与只读工具增量

`GET /v1/state` 的 capabilities 新增 `voice_stream`、`speech_stream`、`web_search`，表示配置可用，不代替实际供应商权限检测。Android 按能力使用新接口，旧接口保留。

### 有界语音上传

`WS /v1/voice/stream?request_id=<16–64位字母数字连字符>&interaction_source=direct_question|ambient_capture`，Bearer 仅放 HTTP 握手头；禁止把 token 放 URL。鉴权失败关闭码 4401，其他受控错误接受连接后返回 `{type:"error",status,detail}` 并关闭。

服务端返回 `{type:"ready",sample_rate:16000}` 后，客户端发送 16kHz 单声道 little-endian PCM16 二进制帧，每帧 2–32000 字节且为偶数，总量最多 1920000 字节。设备确认停止排空后发送唯一文本帧 `{"type":"end"}`，总音频至少 3200 字节；不会根据识别中间结果执行业务。中间结果为 `{type:"transcript.partial",text}`，最终为 `{type:"result",result:<与/v1/voice相同结构>}`。输入等待最多 75 秒，包含上游识别的总期限 90 秒；队列最多 32 帧，拥塞超过 3 秒即失败。业务阶段重新检查登录状态。

流式 request_id 为账号内单次使用，包括失败的流式 ID。重复连接返回 409，不重放、不二次扣识别额度。用 `/v1/turns/{id}` 恢复状态；`streaming` 表示尚未进入业务，`business` 表示可能已产生记录。`retryable=false`，重新发起需新 ID；先核对是否进入业务或已有完成结果。断连发生在 end 前不执行业务；end 后若结果丢失需查询状态，不能假设取消已撤销保存。原始录音不写库。

### 流式播放

`POST /v1/speech/stream` 请求仍为 `{text}`（1–1500 字），需要登录、配额并占账号业务锁。响应 `application/x-aidol-pcm`，头 `X-Aidol-Audio-Rate: 24000`。每帧是四字节无符号大端长度，再接 24kHz 单声道 little-endian PCM16；长度为偶数且不超过 24000，总音频最多 6MiB。四字节零长度帧表示正常完成；缺少终止帧是中断，不能报告完整播放。首帧之前的供应商错误为 502，之后的错误终止流且不发成功帧。Android 使用原有焦点/真实耳机路由核验，只在核验成功后输出语音。

### 新工具与地图字段

- `GET /v1/search?q=...&freshness=noLimit`：q 1–500 字，freshness 为 noLimit/oneDay/oneWeek/oneMonth/oneYear 或有效 ISO 日期、`开始日期..结束日期`；返回 `available/source/query/results/reason`。每条结果含 title/url/summary/site/published；供应商错误不回显凭证或响应正文。
- `GET /v1/maps/geocode?address=...&city=...`：地址 1–100 字，城市最多 80；返回 GCJ-02 places（address/longitude/latitude/adcode/city）。
- `POST /v1/maps/reverse`：WGS-84 LocationInput，先转换再逆解析，返回 address/city/district/adcode。
- `/v1/maps/nearby` 新增 radius（100–50000，默认3000）、metro_only（默认false）；地铁站类型固定150500。
- `/v1/maps/weather` 新增 forecast=true；返回 forecast 数组（date/week/dayweather/nightweather/daytemp/nighttemp/daywind/daypower）和 report_time/city/note。
- settings.transport 支持 bicycling，调用高德v4并核对errcode；其他路线沿用v3。公共交通 steps 新增 stations 列表，只来自选中路线，不合并备选线路；均为规划而非车辆实时位置。

主动对话在同一 Agent 内执行最多三轮、六次工具调用；只读工具包括公开搜索、地点/地址/周边、天气、路线与时钟，整段工具对话流程限45秒。每个非时钟工具调用额外占一次账号业务额度，现有一轮对话和TTS额度保持原口径。无配置/失败/额度不足均返回明确不可用结果。路线只能使用本轮地点搜索的 ID 或本人已保存目的地，不接受模型提供的任意坐标；定位须来自用户最近五分钟的主动GPS更新且精度≤200米。

返回的 `tool_results:[{tool,result}]` 与对话结果一起进入账号幂等回执，用于手机展示查询来源；外部结果不当作命令，不作为用户私人事实写入记忆。公开查询中的“我/今天”不再单独触发私人记忆召回，明确历史/约定问题仍优先使用记忆。环境录音不启用工具。未接入任意MCP、本机文件操作、网页抓取、真实消息发送或自动导航。
