# Android 多账号 API 契约

## 独立工作会议（2026-10-17）

以下均需账号鉴权，数据只进入该账号 UUID SQLite。`capabilities.work_meetings=true` 表示支持此协议，不代表 ASR／文字模型在线健康。

| 请求 | 契约 |
| --- | --- |
| `POST /v1/work-meetings` | `{"meeting_id":"客户端生成16–64位唯一编号"}`；工作模式下幂等建场，已结束编号不能复用。成功后才开启采音。 |
| `POST /v1/voice?request_id=…&meeting_id=…&segment_index=0&offset_ms=0` | PCM16/16kHz；`meeting_id` 与从0递增的 `segment_index` 必须同时提供，来源为 `ambient_capture`。`recognize=0` 表示该段文字已由实时字幕保存：只存录音，不做 ASR、不计额度。默认 `recognize=1` 时做整段识别，并删除起点落在该段内的实时字幕句子再写入识别结果（实时字幕中断时的兜底）。`offset_ms` 为该段在本场录音时间轴上的起点（只计已录 PCM，32 字节/毫秒，不含暂停）；旧客户端省略时按 `segment_index×20000`。只能与会议参数一起使用。每段独立 request_id；原文与场次关联在一个事务内提交。静音段保留无原文回执，不假装记录了文字。同一编号重试不重复 ASR 或原文，不同请求占用同一片段编号返回409。 |
| `POST /v1/work-meetings/{id}/finish` | `{"segment_count":N,"complete":true}`；客户端排空后告知本场总片段数。核对0到N−1回执，完整且有文字才为 `saved`；无讲话为 `empty`，缺段／非正常结束为 `partial`。返回原文和后台整理状态；结束请求幂等，不再接受追加。异常中断可用 `complete:false` 收尾已保存部分。 |
| `GET /v1/work-meetings` | `{"meetings":[…]}`；最近100场，含开始／结束时间、原文段数、标题、摘要和处理状态。只读 SQLite 快照不等待 ASR 账号锁。 |
| `GET /v1/work-meetings/{id}` | 本场完整有序 `sources`（含 `offset_ms/duration_ms`）、`utterances`、`events`、`audio` 和 `report`；原文不按最近100条聊天裁剪。未知／其他账号编号返回404。列表另含 `duration_ms`。 |
| `WS /v1/work-meetings/{id}/live?offset_ms=N` | 实时字幕（仅请求头 Bearer 鉴权）。会议须为录音中且工作模式，否则以 4409 关闭；每条连接计一次额度，不占账号锁。客户端发送已录 PCM 二进制帧（16 kHz 单声道 PCM16，单帧 ≤64000 字节；暂停期间不发），控制消息 `{"type":"pause"}` / `{"type":"resume","offset_ms":N}` / `{"type":"end"}`。服务端发送 `ready`、`partial {text,start_ms}`（仅显示、不保存）、`final {sentences:[{id,transcript_id,start_ms,end_ms,text,speaker}]}`（已保存到本场）、`paused`、`done`（收到 end 后保存完最后几句）、`error`（上游失败，随后关闭）。时间轴为 `offset_ms` 起累计的已录 PCM；暂停或单次上游满10分钟时结束当前识别并另起一段。字幕句子以 `segment_index=-1` 进入 `utterances`/`sources`，纪要可直接引用。 |
| `POST /v1/work-meetings/{id}/events` | `{"event_id":"16–64位","kind":"pause|resume|mark","offset_ms":N,"origin":"app|headset"}`；仅录音中可写。同一 event_id 重试返回原记录，内容不同返回409；已暂停再暂停、未暂停时继续返回409。读 SQLite 快照写入，不等待片段 ASR 持有的账号锁。 |
| `GET /v1/work-meetings/{id}/audio?start_ms=0&duration_ms=600000` | 返回本场录音时间轴 `[start_ms, start_ms+duration_ms)` 的 16 kHz 单声道 WAV，单次最多 10 分钟，裁到最后一段已存音频；缺失片段补静音。无录音、已过期或已删除返回404。 |
| `DELETE /v1/work-meetings/{id}/audio` | 只删除本场录音文件，保留原文、句子与纪要；录音中返回409。返回会议详情。 |
| `POST /v1/work-meetings/{id}/retry-summary` | 只重新处理已保存的文字，不重新 ASR 或上传音频；并发轮询不重复启动后台任务。替换同场旧纪要和待办，保留相同待办的完成状态。 |

`status` 是原文保存状态：`recording/saved/partial/empty`；`summary_status` 为 `waiting/pending/ready/fallback/failed/unavailable`。`summary_stage` 为 `waiting/queued/analyzing/synthesizing/done`，`processed_sections/total_sections` 是实际完成的分析组数，不伪造百分比。Android 在本机停止至上传排空间显示“正在转写并保存”。网络失败保留上次确认状态并提示同步重试；后端重启后，读取列表／详情／state 会恢复持久化的 pending 任务。

`report` 含 `title/summary/topics/decisions/tasks/open_questions/chapters/highlights/source_ids/extraction/notice/capture_status`。`chapters` 最多12条 `{title, summary, source_ids, start_ms}`，按时间排序；`highlights` 最多8条 `{text, source_ids, start_ms}`，`text` 必须逐字出现在所引句子中，否则丢弃。`start_ms` 由服务端按所引句子计算，不由模型给出；格式不合格的章节或金句被丢弃，不导致整份纪要回退。旧纪要没有这两个字段，重试整理后补上。原文不经 AI 改写；长会议先按有界片段分析，再分层汇总，覆盖所有已保存文字。每条要点、决策、待办、待确认项引用本场句子 id（`<原文 id>.<序号>`，旧会议仍为原文 id）；负责人和截止日期必须能在来源中核对，身份不明时留空。模型不可用、结构不合法或额度不足返回明确的原文摘录 fallback，不宣称 AI 整理成功。原文保存不受聊天保留开关影响；新协议的录音不逐段进入个人记忆提取。ASR 和每次实际文字模型调用分别计入账号额度。

2026-10-17 说话人与时刻：实时字幕与会议分段识别开启火山流式 ASR 2.0 说话人聚类（`enable_speaker_info`、`ssd_version="200"`），确认句的 `additions.speaker_id`（"0"、"1"…）映射为 `speaker`“说话人1/2…”，并写入该句原文的 `speaker` 供纪要使用；编号只在同一上游识别段内稳定，暂停或满10分钟换段后可能错位，不代表真实身份。`utterances` 增加 `speaker`、`wall_at`（秒级 ISO 时刻 = 录音起点 + 录音内偏移 + 之前各次暂停时长）；会议增加 `speakers`（去重标签）与 `audio_started_at`（第一帧实时字幕音频到达时推算，或首个分段到达时间减去音频长度，误差约一两秒）。2026-10-17 已用合成双音色语音实测火山返回 `speaker_id` 0/1/0。

2026-10-17 时间轴与录音保存：`utterances` 每项 `{id, transcript_id, segment_index, start_ms, end_ms, text}`，时间为本场录音时间轴毫秒（段起点 + ASR 句内 `start_time/end_time`，裁到段长）；每条 ASR 句再按句末标点（。！？；）拆分：供应商返回逐字 `words` 且与文字吻合时按逐字时间，否则按字数比例估算时间；不足2字的语气词并入前句。供应商未给时间时整段按同样规则拆分并估算。`events` 为暂停、继续与重点标记（`offset_ms` 同一时间轴，`created_at` 为服务器时间）。`audio` 为 `{status: saved|partial|none|expired|deleted, bytes, segments_with_audio, retention_days, expires_at}`。设置 `work_audio_retention_days`（0–365，默认30，0 表示不保存）控制保存：每段 PCM 写入 `users/<UUID>/work-audio/<会议>/`，路径只由服务端生成；会议结束超过期限后，在读取列表/详情/音频或保存设置时删除录音并标为 `expired`，异常未结束的会议多宽限1天。缩短期限立即作用于已有录音。录音不进入导出；清除数据和删除账号删除整个录音目录。服务端不加密录音文件，依赖主机磁盘与访问控制；部署备份是否包含该目录需按 `deploy/` 另行配置。

`/v1/state` 增加 `work_meetings`；`/v1/export` 版本4增加全部会议详情。清除数据／删除账号包含新表；遗忘原文会撤回关联纪要和待办，后台结果不会重新发布已撤回的原文。旧 `/v1/meetings` 按时间范围整理入口保留兼容，不能据此倒推旧片段属于哪一场会议。

当前三模式命名、提问/记录区别及文字与语音一致性，统一见[三种模式的文案与播报约定](mode-experience.md)。历史版本说明不覆盖当前约定。

## 服务入口

APK 默认本地基址 `http://127.0.0.1:8108`，可切换内置远端 `https://123.207.219.174:7776`，由 `create_hosted_app` 提供。本地开发使用同一工厂与接口，基址 `http://127.0.0.1:8108`，手机通过 USB reverse 访问；账号与数据独立于远端。所有 JSON 为 UTF-8；供应商凭证不下发。除健康检查、注册、登录、恢复密码外，均需要 `Authorization: Bearer <登录返回的 token>`。响应设置 `Cache-Control: no-store`、`X-Content-Type-Options: nosniff` 和 `X-Frame-Options: DENY`。公开服务不提供 `/docs`、`/openapi.json`、旧 `/api/*`、Web 页面、SSE 或旧 `/ws/audio`。新增鉴权音频 WebSocket 见下方实时语音协议。

### 账号与会话

账号 `username` 去掉首尾空白后为 1–64 位，支持中文、英文、数字、内部空格和可见符号，不接受全空白、换行或控制字符；英文字母按 ASCII 小写归一后全站唯一，例如 `Alice_01` 与 `alice_01` 是同一个账号。密码 1–128 位，无复杂度要求，按原样匹配、不去掉首尾空格；注册、登录、修改密码和恢复密码使用相同规则。不要求邮箱、手机号或验证码。服务器保存随机盐 scrypt 派生值，不保存明文密码；Bearer 与恢复码仅保存摘要。

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
| `GET /v1/state` | 返回 `user/settings/capabilities/agent/transcripts/memories/tasks/drafts/meeting_report`；刷新时重新判断当前模式。`agent.commute` 为最近一次定位解析的通勤去向（`target=home/work/custom`、`name`、`basis=near_home/near_work/time_of_day/only_saved_place`；无可用去向时 `target=null` 并给出 `reason`），`agent.route.target` 与之对应 |
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

`interaction_source` 允许 `direct_question`、`ambient_capture` 及 `continuous_call`。生活/通勤的连续通话每轮都直接回答，不要求助手称呼；工作语音无论客户端填写哪种来源，均作为资料记录，返回 `interaction=silent_capture`、空 `response`、`speak=false`，不执行模式或记忆修改指令。工作记录始终保存转写，聊天历史保留开关不影响明确开始的工作记录。文字提问仍可显式操作记忆和模式。设置不再包含 `name`；旧字段兼容忽略。移动端设置始终关闭 `auto_mode` 和定时录音。详见 [三模式约定](mode-experience.md)。

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
  "commute_target": "auto",
  "home_name": "家",
  "home_city": "",
  "home_lon": null,
  "home_lat": null,
  "work_name": "公司",
  "work_city": "",
  "work_lon": null,
  "work_lat": null,
  "contact": "同事",
  "arrival_notice_minutes": 5,
  "retain_transcripts": true
}
```

| 字段 | 范围 / 语义 |
| --- | --- |
| `mode` | `work/commute/life`；`auto_mode=false` 时作为手动模式 |
| `provider` | 兼容旧设置，仍固定为 `cascade`；新版语音实际链路由 capabilities 和 WS 的 `engine` 协商，见千问扩展 |
| 工作时段 | 开始 `0..23`，结束 `1..24` 且晚于开始；当前不支持跨午夜班次 |
| `timezone` | 有效 IANA 时区 |
| 轮询间隔 / 监听时长 | `60..3600` 秒 / `5..60` 秒 |
| `vad_threshold` | `0.005..0.2`，客户端 RMS 触发阈值 |
| 目的地 | 经度 `-180..180`，纬度 `-90..90`，必须同时填写或同时为空，使用 GCJ-02 |
| 家 / 公司 | `home_*`、`work_*`，坐标规则同目的地；`*_city` 为空时沿用 `transit_city` |
| `commute_target` | `auto/home/work/custom`。`auto`：当前位置距家 1 公里内去公司、距公司 1 公里内回家，都不在附近时按工作时段中点前去公司、之后回家；家和公司都未设置时沿用 `destination_*`。`custom` 固定使用 `destination_*` |
| `transport` / `transit_city` | `driving/walking/transit/bicycling`；公交规划目的城市，默认上海 |
| 提醒分钟 | `1..60` |
| `retain_transcripts` | 仅控制原始转写保存，不关闭记忆提取 |

## 地点与天气响应

- `GET /v1/maps/search` 返回 `available/source/pois/coordinate_system/reason`；每个 POI 包含名称、地址、经纬度、城市和 adcode。结果坐标为 GCJ-02，可直接保存为目的地。
- `POST /v1/location` 和 `/v1/maps/nearby` 接收 WGS-84 坐标；附近搜索先转换坐标，查询 3 km 内最多 10 个 POI。`keyword` 默认为“美食”，最多 80 字。
- `GET /v1/maps/weather` 接收六位 adcode，省略时从当前账号目的地反查；返回天气、温度、湿度、风向、风力、发布时间和来源，属于实况。未配置目的地时不能省略 city。

缺少地图 Key 返回 `available=false`，上游失败返回 502。`transport=driving/walking/transit` 选择路线方式，`transit_city` 是公交规划目的城市。公交结果标记 `scheduled=true`，只有计划线路/时刻；路线细节在 `steps`，部分字段可能为空。

## 记忆与来源语义

2026-10-17 Demo 更新：普通文字/语音回答携带当前账号全部已保存记忆，不受 state 列表上限影响。配置回答模型时，历史问题直接使用该模型理解上下文，返回普通 `text_llm/qwen_realtime`，不保证附带检索引用；此类回答不会建立唯一更正/遗忘目标。明确保存后的对象和独立记忆检索仍遵守确定性修改规则。`/v1/recall` 与可选 `recall_memory` 保留证据检索；无回答模型时保留旧降级。详见 [记忆功能说明](memory-features.md)。

`POST /v1/memories/from-transcript/{id}` 不需要正文，读取本人已保存的完整原句，保存 `kind=fact`、`source=transcript:{id}`，默认不收藏。重复调用复用同来源笔记，保留用户后续更正和收藏。`PUT /v1/memories/{id}` 提交完整可编辑字段 `content/kind/pinned`，不修改原始转写。自动提取方式与精确转写引用是不同来源，不能凭相近文字猜测原话。

会议从最近最多 1000 条转写中选择时段内最多 80 条工作发言；模型输入另有限额，不能承诺覆盖时段全部记录。同一输入快照复用报告，不同快照可能形成相似任务；报告是生成时的快照，后续编辑记忆不会自动重算旧报告。普通 `kind=task` 记忆是线索，完成状态只来自行动项表。

问记忆检索当前账号的有界记忆、转写和行动项，返回引用与原文摘录。引用 ID 和摘录子串需通过校验，资料不足时 `insufficient=true`；不得补造事实或将记录内容当成系统指令。

`GET /v1/export` 返回 v3 快照，当前没有导入接口。`DELETE /v1/data` 要求当前密码，删除业务数据及幂等结果、保留账号和设置；账号注销使用 `DELETE /auth/account`。删除在线数据不自动删除运维历史备份，备份规则见部署文档。

## 当前范围

应用只提供本文件列出的账号与业务 REST API。旧单用户浏览器接口和供应商 Demo 已移除。M2 连续语音需要另行定义账号鉴权、停止、并发及持久化语义；产品规划中的候选地址不属于已实现接口。


## 2026-10-17 实时传输与只读工具增量

`GET /v1/state` 的 capabilities 新增 `voice_stream`、`speech_stream`、`web_search`，表示配置可用，不代替实际供应商权限检测。Android 按能力使用新接口，旧接口保留。

### 有界语音上传

`WS /v1/voice/stream?request_id=<16–64位字母数字连字符>&interaction_source=direct_question|ambient_capture|continuous_call`，Bearer 仅放 HTTP 握手头；禁止把 token 放 URL。鉴权失败关闭码 4401，其他受控错误接受连接后返回 `{type:"error",status,detail}` 并关闭。

服务端返回 `{type:"ready",sample_rate:16000}` 后，客户端发送 16kHz 单声道 little-endian PCM16 二进制帧，每帧 2–32000 字节且为偶数，总量最多 1920000 字节。设备确认停止排空后发送唯一文本帧 `{"type":"end"}`，总音频至少 3200 字节；不会根据识别中间结果执行业务。中间结果为 `{type:"transcript.partial",text}`，最终为 `{type:"result",result:<与/v1/voice相同结构>}`。输入等待最多 75 秒，包含上游识别的总期限 90 秒；队列最多 32 帧，拥塞超过 3 秒即失败。业务阶段重新检查登录状态。

流式 request_id 为账号内单次使用，包括失败的流式 ID。重复连接返回 409，不重放、不二次扣识别额度。用 `/v1/turns/{id}` 恢复状态；`streaming` 表示尚未进入业务，`business` 表示可能已产生记录。`retryable=false`，重新发起需新 ID；先核对是否进入业务或已有完成结果。断连发生在 end 前不执行业务；end 后若结果丢失需查询状态，不能假设取消已撤销保存。原始录音不写库。

### 流式播放

`POST /v1/speech/stream` 请求仍为 `{text}`（1–1500 字），需要登录、配额并占账号业务锁。响应 `application/x-aidol-pcm`，头 `X-Aidol-Audio-Rate: 24000`。每帧是四字节无符号大端长度，再接 24kHz 单声道 little-endian PCM16；长度为偶数且不超过 24000，总音频最多 6MiB。四字节零长度帧表示正常完成；缺少终止帧是中断，不能报告完整播放。首帧之前的供应商错误为 502，之后的错误终止流且不发成功帧。Android 使用原有焦点/真实耳机路由核验，只在核验成功后输出语音。

### 新工具与地图字段

- `GET /v1/search?q=...&freshness=noLimit`：q 1–500 字，freshness 为 noLimit/oneDay/oneWeek/oneMonth/oneYear 或有效 ISO 日期、`开始日期..结束日期`；返回 `available/source/query/results/reason`。每条结果含 title/url/summary/site/published；供应商错误不回显凭证或响应正文。
- `GET /v1/maps/geocode?address=...&city=...`：地址 1–100 字，城市最多 80；返回 GCJ-02 places（address/longitude/latitude/adcode/city）。
- `POST /v1/maps/reverse`：WGS-84 LocationInput，先转换再逆解析，返回 address/city/district/adcode，以及转换后的 GCJ-02 `longitude/latitude`（`coordinate_system="GCJ-02"`），可直接写入 `home_*`/`work_*` 实现"用当前位置设为家/公司"。
- `/v1/maps/nearby` 新增 radius（100–50000，默认3000）、metro_only（默认false）；地铁站类型固定150500。
- `/v1/maps/weather` 新增 forecast=true；返回 forecast 数组（date/week/dayweather/nightweather/daytemp/nighttemp/daywind/daypower）和 report_time/city/note。
- settings.transport 支持 bicycling，调用高德v4并核对errcode；其他路线沿用v3。公共交通 steps 新增 stations 列表，只来自选中路线，不合并备选线路；均为规划而非车辆实时位置。

主动对话在同一 Agent 内执行最多三轮、六次工具调用；只读工具包括公开搜索、地点/地址/周边、天气、路线与时钟，整段工具对话流程限45秒。每个非时钟工具调用额外占一次账号业务额度，现有一轮对话和TTS额度保持原口径。无配置/失败/额度不足均返回明确不可用结果。路线只能使用本轮地点搜索的 ID 或本人已保存的家/公司/目的地（`plan_route.target` 为 `auto/home/work`），不接受模型提供的任意坐标；定位须来自用户最近五分钟的主动GPS更新且精度≤200米。

返回的 `tool_results:[{tool,result}]` 与对话结果一起进入账号幂等回执，用于手机展示查询来源；外部结果不当作命令，不作为用户私人事实写入记忆。公开查询中的“我/今天”不再单独触发私人记忆召回，明确历史/约定问题仍优先使用记忆。环境录音不启用工具。未接入任意MCP、本机文件操作、网页抓取、真实消息发送或自动导航。

### AI 通话同连接流式回复（可选扩展）

`GET /v1/state` 的 `capabilities.voice_reply_stream` 表示 ASR/TTS 均配置。该能力为 true 时，在已有语音 WebSocket URL 增加 `reply_audio=1`，使用 `interaction_source=direct_question` 或 `continuous_call`。常态对话普通聊天只发 `thinking/result/stream.done`，不发 `reply.*` 或 PCM；明确提问/模式命令才发回复音频。省略参数保留旧协议；非法值或独立环境录音请求该扩展返回422，未配置TTS返回503。常态对话使用 cascade 识别后判意图，`engine=qwen` 仅允许 `direct_question`，避免实时模型先回答环境聊天。一次通话由多个不同 request_id 的有界轮次组成；新客户端与后端需要一同更新，旧后端会拒绝新增来源值。

录音与 `end` 协议不变。ASR 完成后依次发送 `thinking`、`reply.start`（包含完整 `text` 和 `sample_rate:24000`）。其后二进制帧为原始 24kHz 单声道 little-endian PCM16，单帧最多24000字节、总量最多6MiB。正常音频终止为 `reply.done`；音频失败为 `reply.error`（可展示 detail），不能当作完整播放，也不得自动重试。最终 `result` 与 `reply.done/error` 的先后顺序允许互换，**只有业务持久化后才发 result**。两者均结束后发 `stream.done` 并关闭连接。无转写时只有 `result`（speak=false）和 `stream.done`。

回复文本可先于记忆提取完成到达，不能把 `reply.start` 当作保存成功回执。服务在同一账号锁下等待业务和语音结束；发送等待最多15秒、TTS最多60秒、回复阶段最多100秒。end 后继续监听断连，取消并等待全部子任务结束再释放账号锁。已产生的写入不因断连回滚，仍用 `/v1/turns/{id}` 只读查询。Android 缓冲最多384000字节，打断/取消会唤醒阻塞读写，缺少成功终止帧视为中断。

同连接语音包含在该轮语音额度内，不额外调用 `/v1/speech/stream` 计费。新增 `recall_memory(question)` 是当前账号本地只读检索，不消耗外部查询额度；返回 `source=account_memory/available/answer/citations/insufficient`，引用也进入最终顶层 `citations`。外部查询额度规则保持不变。上述 cascade 链路完成文本后再合成音频，不宣称文本模型逐 token 播报或全双工语音。

### 千问原生音频扩展

`capabilities.voice_realtime=true` / `voice_realtime_provider=qwen` 表示后端配置了通义；独立于 ASR/TTS。`voice_continuous_qa` 同步此配置能力，`voice_barge_in=true`（持续全双工入口）。生活与通勤共用通义流式通话，能力缺失明确拒绝开始。工作使用 ASR 录音通道。能力字段不代表供应商在线、余额或权限已验证。

`WS /v1/voice/stream?...&engine=qwen&reply_audio=1`，只接受 `direct_question` 或 `continuous_call`。缺少千问配置返回503，不支持的 engine/组合返回422。省略 engine 等于 cascade，旧客户端行为不变。客户端与供应商之间始终由账号后端中转，token 只在握手头，模型 Key 不下发。

录音、结束帧、单次 request_id、配额、锁和恢复语义不变；`ready` 在上游确认后返回，增加 `engine:qwen`。通义使用原始音频生成增量文本/PCM，不执行名字匹配。工作模式拒绝 `engine=qwen` 回答通道（409），并在所有语音入口禁止资料触发工具/聊天。Android 工作录音保持麦克风连续采集，每20秒及结束尾段分别以独立 request_id 提交；全段成功后才提示已保存。

`reply.start.text` 可以为空；新增 `{type:"reply.text",text:<累计回答快照>}`，音频可以先于完整文字到达，24kHz PCM16 格式不变。`reply.done/error/interrupted` 与 `result`、`stream.done` 继续使用；缺少成功结束不能当作完整播报。原生回复结果标记 `interaction:qwen_realtime`、`response_complete`、`voice_interrupted`、`voice_error`；未完成回答不写入完整助手历史，当前回执仍保留收到的文字，失败不自动重放。

记忆/模式的确定性回执使用已有 TTS 播报，未配置时仅返回文字和明确 notice；普通回答不经过独立 ASR→文本回答→TTS。原始音频不落库，输入文字按保留设置与记忆规则处理。工具集沿用已有账号权限/配额/来源，仍有三轮六次上限。配置、时限和实际验收详见 [千问实时语音](qwen-realtime.md)。


### 持续全双工通话（2026-10-17 最新）

`capabilities.voice_prepare=true` 时，可在全双工入口增加 `prepare=1`。服务端短暂读取账号配置后释放账号锁，并行建立 ASR/Omni 连接，返回 `{"type":"prepared","expires_in":120}`；准备阶段拒绝 PCM、不登记 mobile turn、不扣业务调用额度。读取配置允许等待上一场通话收尾最多5秒；上游初始化限时25秒，就绪后等待接通最多120秒，每账号限一个准备连接、每分钟最多6次，全服务最多8个。`prepared` 不表示麦克风已开启。离开时发送 `{"type":"end"}` 或断开即可释放。

用户主动操作后，在该连接发送 `{"type":"start"}`。服务端重新鉴权、取得账号锁、检查 request_id/当前模式/额度，以最新指令、工具和对话历史接管连接，再返回现有 `ready`。配置未变化时省去重复配置确认；工作模式拒绝预连接和接通。准备失败/过期且尚未接管时，客户端可用全新 request_id 正常接通；正式接管失败不自动重放。省略 `prepare` 的客户端保持原协议。

在已鉴权 `/v1/voice/stream` 增加 `duplex=1`，仅允许 `engine=qwen&reply_audio=1&interaction_source=continuous_call`；工作拒绝此通道。`ready` 带 `duplex:true` 和 `interrupt_keyword:"小万"` 后持续发送 PCM16/16kHz，跨全部发言和回答，不逐轮发送 `end`。后端使用独立通义实时 ASR 连接持续识别，只将接纳的发言文本提交到 Omni 流式语音连接；播报中的普通声音不会进入回答上下文或取消输出。现有工具回灌仍走唯一账号编排器。只有播报期识别到“小万”才发送 `input.speech_start` / `reply.interrupted`，手机立即废弃旧PCM并停止播报，继续上传麦克风；聆听期无需称呼即可提问。`input.speech_end` 只更新思考状态，不停止麦克风。`reply.start`（包含整数 turn）、累计 `reply.text`、二进制PCM、`reply.done` 可在同一连接出现多次。`result` 带本轮子 request_id 和 turn，每轮单独持久化；打断的回答标记不完整，不作为完整助手历史。

Android 实际播放排空后发送 `{"type":"playback.done","turn":N}`，后端仅接纳当前输出轮的确认，恢复无需关键词的普通提问。`reply.done` 表示生成完成，不等于耳机已播完。单独说“小万”仅停止回答，继续聆听；同句后续问题被接纳。不存在静默退出或固定整场通话时限。语音要求切模式只提示使用双击，不能结束当前通话。生命周期由单击结束、双击结束并切模式控制；设备/网络错误仍须明确提示，不能伪装成用户结束。

关键词保护也覆盖已接纳问题的排队和思考阶段：此时普通起声和后续转写不会取消或替换已接纳问题；识别到“小万”仍可立即打断。避免噪声连续触发 VAD 导致回答尚未返回任何音频就反复被取消。

整场结束发送 `{"type":"end"}`，或客户端直接取消连接；服务端取消全部子任务、结束上游连接、释放账号锁。正常结束回 `call.done`。`voice_duplex` / `voice_barge_in` 表示后端实现协议能力，硬件效果以验收记录为准。工具查询和发言继续按账号额度计费。回复下行按播放速度限制提前量，避免插话控制帧排在大段音频之后；回复缓存、输入/输出队列有上限。
