# Android 多账号 API 契约

## 独立工作会议（2026-10-17）

以下均需账号鉴权，数据只进入该账号 UUID SQLite。`capabilities.work_meetings=true` 表示支持此协议，不代表 ASR／文字模型在线健康。

| 请求 | 契约 |
| --- | --- |
| `POST /v1/work-meetings` | `{"meeting_id":"客户端生成16–64位唯一编号"}`；工作模式下幂等建场，已结束或已删除编号不能复用。成功后才开启采音。 |
| `POST /v1/voice?request_id=…&meeting_id=…&segment_index=0&offset_ms=0` | PCM16/16kHz；`meeting_id` 与从0递增的 `segment_index` 必须同时提供，来源为 `ambient_capture`。`recognize=0` 表示该段文字已由实时字幕保存：只存录音，不做 ASR、不计额度。默认 `recognize=1` 时做整段识别，并删除起点落在该段内的实时字幕句子再写入识别结果（实时字幕中断时的兜底）。`offset_ms` 为该段在本场录音时间轴上的起点（只计已录 PCM，32 字节/毫秒，不含暂停）；旧客户端省略时按 `segment_index×20000`。只能与会议参数一起使用。每段独立 request_id；原文与场次关联在一个事务内提交。静音段保留无原文回执，不假装记录了文字。同一编号重试不重复 ASR 或原文，不同请求占用同一片段编号返回409。 |
| `POST /v1/work-meetings/{id}/finish` | `{"segment_count":N,"complete":true}`；客户端排空后告知本场总片段数。核对0到N−1回执，完整且有文字才为 `saved`；无讲话为 `empty`，缺段／非正常结束为 `partial`。返回原文和后台整理状态；结束请求幂等，不再接受追加。异常中断可用 `complete:false` 收尾已保存部分。 |
| `GET /v1/work-meetings` | `{"meetings":[…]}`；最多100场，置顶优先，同组按开始时间从新到旧（按实际时刻比较时区），同刻按ID稳定排序；含pinned、开始／结束时间、原文段数、标题、摘要和处理状态。只读 SQLite 快照不等待 ASR 账号锁。 |
| `GET /v1/work-meetings/{id}` | 本场完整有序 `sources`（含 `offset_ms/duration_ms`）、`utterances`、`events`、`audio` 和 `report`；原文不按最近100条聊天裁剪。未知／其他账号编号返回404。列表另含 `duration_ms`。 |
| `WS /v1/work-meetings/{id}/live?offset_ms=N` | 实时字幕（仅请求头 Bearer 鉴权）。会议须为录音中且工作模式，否则以 4409 关闭；每条连接计一次额度，不占账号锁。客户端发送已录 PCM 二进制帧（16 kHz 单声道 PCM16，单帧 ≤64000 字节；暂停期间不发），控制消息 `{"type":"pause"}` / `{"type":"resume","offset_ms":N}` / `{"type":"end"}`。服务端发送 `ready`、`partial {text,start_ms}`（仅显示、不保存）、`final {sentences:[{id,transcript_id,start_ms,end_ms,text,speaker}]}`（已保存到本场）、`paused`、`done`（收到 end 后保存完最后几句）、`error`（上游失败，随后关闭）。时间轴为 `offset_ms` 起累计的已录 PCM；暂停或单次上游满10分钟时结束当前识别并另起一段。字幕句子以 `segment_index=-1` 进入 `utterances`/`sources`，纪要可直接引用。 |
| `POST /v1/work-meetings/{id}/events` | `{"event_id":"16–64位","kind":"pause|resume|mark|call_start|call_end","offset_ms":N,"origin":"app|headset"}`；仅录音中可写。同一 event_id 重试返回原记录，内容不同返回409；已暂停再暂停、未暂停时继续返回409。`call_start/call_end` 标注当前会议内的耳机通话录音区间，重复开始或未开始就结束返回409；与手动暂停状态独立。读 SQLite 快照写入，不等待片段 ASR 持有的账号锁。 |
| `GET /v1/work-meetings/{id}/audio?start_ms=0&duration_ms=600000` | 返回本场录音时间轴 `[start_ms, start_ms+duration_ms)` 的 16 kHz 单声道 WAV，单次最多 10 分钟，裁到最后一段已存音频；缺失片段补静音。无录音、已过期或已删除返回404。 |
| `DELETE /v1/work-meetings/{id}/audio` | 只删除本场录音文件，保留原文、句子与纪要；录音中返回409。返回会议详情。 |
| `PATCH /v1/work-meetings/{id}` | `{pinned:true/false}`（严格布尔值）；返回`{meeting_id,pinned,meetings}`。重复设置幂等，未找到/跨账号404；不改原开始时间、状态或纪要。 |
| `PUT /v1/work-meetings/{id}/speakers` | `{"label":"说话人N","name":"张三"}`；`name` 合并空白后最多20字，空字符串清除该名称，不能是另一个“说话人N/发言人N”编号或含控制字符（422）。返回完整会议，其中 `speaker_names` 为 `{label: name}`，只含本场仍存在的标签。录音中或整场识别 pending 时返回409；会议不存在或本场没有该标签返回404。仅改显示：原文句子、`speakers`、纪要和任务仍保存标签，客户端在文字记录、纪要和导出中把标签替换为名称，无需重新生成纪要。整场说话人识别写入新结果时清空本场 `speaker_names`。会议列表也返回 `speaker_names`。 |
| `DELETE /v1/work-meetings/{id}` | 删除已结束会议；录音中409，未知/跨账号404。成功返回`{ok,deleted_task_ids,meetings,meeting_report,tasks,transcripts}`，后四项为删除后的当前账号快照；同账号已删除ID重复请求返回相同删除回执，不恢复会议。 |
| `POST /v1/work-meetings/{id}/retry-summary` | 只重新处理已保存的文字，不重新 ASR 或上传音频；并发轮询不重复启动后台任务。替换同场旧纪要和待办，保留相同待办的完成状态。 |

2026-10-18 会议管理：列表/详情/导出增加`pinned`布尔值，旧库无损增加默认0字段。Android在每条标题右上角提供书签，未置顶为空心，置顶后红色实心；多条置顶按会议开始时间降序排列，取消回到普通时间组。点击有短缩放反馈，列表原地重排并保留scrollY；对话页的最近会议独立按时间选取，不受书签排序影响。会议卡片整面可左滑露出删除，确认窗说明删除范围、确认后才提交；取消/失败不移除行，失败可重试，过期轮询不得覆盖修改结果。

删除事务清理本场原文、句子、事件、上传记录、纪要和引用本场原文的关联行动项（含已编辑行动项），随后清理本场录音目录；引用被删原文的历史整理快照失效，其未引用被删来源的行动项独立保留。单独保存的记忆、独立对话及其他会议保留；曾返回原文的请求缓存失效，防止迟到重放旧原文。Android取消被删行动项提醒、清理该会议本地编辑稿/详情缓存并更新历史整理结果。删除回执只保存会议ID与关联任务ID，防止迟到建场重建；迟到ASR/AI/地址结果无法恢复已删会议。全量清空同时清除此回执表。正在录音的会议先结束；录音已中断时可从原详情入口结束已上传部分再删除。

`status` 是原文保存状态：`recording/saved/partial/empty`；`summary_status` 为 `waiting/pending/ready/fallback/failed/unavailable`。`summary_stage` 为 `waiting/queued/analyzing/synthesizing/done`，`processed_sections/total_sections` 是实际完成的分析组数，不伪造百分比。Android 在本机停止至上传排空间显示“正在转写并保存”。网络失败保留上次确认状态并提示同步重试；后端重启后，读取列表／详情／state 会恢复持久化的 pending 任务。

`report` 含 `title/summary/topics/decisions/tasks/open_questions/chapters/highlights/source_ids/extraction/notice/capture_status`。`chapters` 最多12条 `{title, summary, source_ids, start_ms}`，按时间排序；`highlights` 最多8条 `{text, source_ids, start_ms}`，`text` 必须逐字出现在所引句子中，否则丢弃。`start_ms` 由服务端按所引句子计算，不由模型给出；格式不合格的章节或金句被丢弃，不导致整份纪要回退。旧纪要没有这两个字段，重试整理后补上。原文不经 AI 改写；长会议先按有界片段分析，再分层汇总，覆盖所有已保存文字。每条要点、决策、待办、待确认项引用本场句子 id（`<原文 id>.<序号>`，旧会议仍为原文 id）；负责人和截止日期必须能在来源中核对，身份不明时留空。模型不可用、结构不合法或额度不足返回明确的原文摘录 fallback，不宣称 AI 整理成功。原文保存不受聊天保留开关影响；新协议的录音不逐段进入个人记忆提取。ASR 和每次实际文字模型调用分别计入账号额度。

2026-10-18 总结失败处理：整场 JSON 未通过格式或原文依据校验时，保留完整原文上下文，请模型纠正一次，并再次执行相同的严格校验；最多两次模型请求，每次分别计入额度。若纠正后仅待办负责人/截止时间、人物身份或事件时间/地点缺少逐字依据，则将对应字段置空、重新完整校验，并在 `metadata_omissions` 和 `notice` 中说明未明确字段；不保留无依据归属，不放宽引用校验，也不因单个未知字段丢弃其余合格纪要。`metadata_omissions` 可含 `task_owner/task_deadline/viewpoint_person/event_time/event_location`。未知原文 ID、错误结构或其他不合格内容仍回退。请求鉴权、限流、网络、超时、截断与内容过滤不进入格式纠正流程。回退报告新增 `failure_reason`，取值为 `no_sources/not_configured/quota_exceeded/authentication/rate_limited/timeout/network/provider_error/output_truncated/content_filtered/invalid_output`；`notice` 显示对应的原因与原文已保存说明，日志只记录固定原因码。会议文件导出成功仅保留文字提示，不播放“已导出”提示音。

2026-10-17 说话人与时刻：实时字幕与会议分段识别开启火山流式 ASR 2.0 说话人聚类（`enable_speaker_info`、`ssd_version="200"`），确认句的 `additions.speaker_id`（"0"、"1"…）映射为 `speaker`“说话人1/2…”，并写入该句原文的 `speaker` 供纪要使用；编号只在同一上游识别段内稳定，暂停或满10分钟换段后可能错位，不代表真实身份。`utterances` 增加 `speaker`、`wall_at`（秒级 ISO 时刻 = 录音起点 + 录音内偏移 + 之前各次暂停时长）；会议增加 `speakers`（去重标签）与 `audio_started_at`（第一帧实时字幕音频到达时推算，或首个分段到达时间减去音频长度，误差约一两秒）。2026-10-17 已用合成双音色语音实测火山返回 `speaker_id` 0/1/0。

2026-10-17 时间轴与录音保存：`utterances` 每项 `{id, transcript_id, segment_index, start_ms, end_ms, text}`，时间为本场录音时间轴毫秒（段起点 + ASR 句内 `start_time/end_time`，裁到段长）；每条 ASR 句再按句末标点（。！？；）拆分：供应商返回逐字 `words` 且与文字吻合时按逐字时间，否则按字数比例估算时间；不足2字的语气词并入前句。供应商未给时间时整段按同样规则拆分并估算。`events` 为暂停、继续、重点标记及电话对话开始/结束（`offset_ms` 同一时间轴，`created_at` 为服务器时间）。`audio` 为 `{status: saved|partial|none|expired|deleted, bytes, segments_with_audio, retention_days, expires_at}`。设置 `work_audio_retention_days`（0–365，默认30，0 表示不保存）控制保存：每段 PCM 写入 `users/<UUID>/work-audio/<会议>/`，路径只由服务端生成；会议结束超过期限后，在读取列表/详情/音频或保存设置时删除录音并标为 `expired`，异常未结束的会议多宽限1天。缩短期限立即作用于已有录音。录音不进入导出；清除数据和删除账号删除整个录音目录。服务端不加密录音文件，依赖主机磁盘与访问控制；部署备份是否包含该目录需按 `deploy/` 另行配置。

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
| `PUT /v1/tasks/{id}` | 编辑内容、负责人、截止时间与提前提醒，返回原ID行动项；详见下文 |
| `PATCH /v1/tasks/{id}` | `{status:"open"或"done"}`；`{ok:true}`；可修改的任务 ID 从 state.tasks 取得 |
| `GET /v1/maps/search?q=...&city=...` | q 1–100 字符，city 最多 80；高德地点搜索结果，沿用下方地图结构 |
| `POST /v1/location` | `{longitude,latitude,accuracy?,speed?,source?}`，WGS-84 定位；返回 agent 快照，可能更新模式、路线与提醒草稿 |
| `POST /v1/location-records` | 前台被动位置记录，见下文；不触发模式判断、路线或提示音 |
| `GET /v1/location-records?limit=100` | 当前账号最近位置，`limit` 1–1000，返回 `{records:[…]}`，按观测时间倒序 |
| `POST /v1/maps/nearby` | 定位字段加 `{keyword}`，默认“美食”；高德 3 km 周边结果 |
| `GET /v1/maps/weather?city=310000` | city 可选，为六位 adcode；省略/留空则用本人保存的目的地坐标反查区域后查询天气，无目的地 422；返回高德实况天气结构 |
| `POST /v1/commute/preview` | 只读起终点与路线预览；接受未选齐的端点，不创建通勤或改变模式。请求与坐标规则见文末“通勤地图预览” |
| `POST /v1/maps/snapshot` | 已鉴权的高德静态地图图片代理；接收 GCJ-02 起终点与有界线形，返回 PNG/JPEG，不接受任意上游 URL |
| `POST /v1/maps/weather-at?forecast=false` | 接收已定位的 `PlacePoint`，按目的地行政区域查询实况天气；不依赖手机当前位置，不占语音会话锁 |
| `POST /v1/drafts/{id}/{action}` | action 仅 `copied/dismissed`；返回 `{ok:true,status,sent:false}`；不支持真实发送 |
| `GET /v1/export` | v4 JSON：`version/user/settings/memories/transcripts/reports/tasks/drafts/work_meetings/location_records` |
| `DELETE /v1/data` | `{password}`；清空业务数据和对话幂等结果，保留账号与设置；返回 `{ok:true}` |

`PUT /v1/settings` 是完整替换：先取 state.settings 再合并修改。字段取值范围沿用下方“设置”，Android 强制值优先；新账号默认手动生活模式，偏好为空。关闭 `retain_transcripts` 阻止新增转写历史，但不清除旧数据，也不阻止抽取记忆或幂等结果保存；完整清除使用 `/v1/data`。

服务端 `life_poll_enabled=false` 禁止服务器触发后台采集，不妨碍 Android 在当前前台页面、用户本次主动开启后按 `life_poll_seconds`（60–3600，默认 300）及 `listen_seconds`（5–60，默认 30）调度有界录音。连续模式也是一轮识别/回复/播放完成后开启下一轮；各轮单独使用 HTTP 或新的有界 WebSocket 上传，不是持续全双工。停止、页面失去前台或错误会停止自动接续。用户另外开启本次锁屏耳机会话后，可在宿主仍存活期间继续手动短语音；它不授权后台连续或定时开麦。

`interaction_source` 允许 `direct_question`、`ambient_capture` 及 `continuous_call`。生活/通勤的连续通话每轮都直接回答，不要求助手称呼；工作语音无论客户端填写哪种来源，均作为资料记录，返回 `interaction=silent_capture`、空 `response`、`speak=false`，不执行模式或记忆修改指令。工作记录始终保存转写，聊天历史保留开关不影响明确开始的工作记录。文字提问仍可显式操作记忆和模式。设置不再包含 `name`；旧字段兼容忽略。移动端设置始终关闭 `auto_mode` 和定时录音。详见 [三模式约定](mode-experience.md)。

定向记忆操作支持“记住，周四交方案”→问回→“后来改成周五了”→“忘记刚才那条”。`interaction` 为 `memory_remember/recall/correct/forget`。更正、遗忘仅接受十分钟内唯一命中的记忆，歧义、已失效指代或不存在目标返回明确解释，不猜测删除对象；普通新话题会清理旧指代。日期局部替换要求原文只有一个明确日期，否则要求完整更正。纯转写或多条任务/记忆引用不能直接口头更改，当前任务状态仍通过 `/v1/tasks` 操作。保存约定不代表设置提醒或生成可调度任务。

索引在当前账号 SQLite 内用 FTS5 存储中文双字词与英文词，首次启动回填全历史，写入/更正/删除由同事务触发器同步。结构条件先于候选限额：上个月按账号时区的**记录时间**筛选，未完成按任务真实状态筛选；不是事件发生日期解析。每类最多取 100 条候选，再选择最多 8 条证据、回答最多 4 条引用。关键词索引覆盖旧记录，仍不等于任意同义表达均能命中。问题、假设和助手输出不作为事实证据；模型只选择可核验连续原文，失败时明确降级为原文摘录。

更正保留原始来源供 `/source` 核对，但原文不再作为当前召回或普通对话依据。口头遗忘删除选定记忆、同来源派生记忆、关联原始转写、引用它的纪要和任务，并返回 `deletion` 计数；其他独立记录、运维备份不在此次范围。旧 `DELETE /v1/memories/{id}` 保持仅删记忆语义，但屏蔽关联旧原文的召回。上述修改均失效已完成请求正文缓存、清理助手历史，保留请求身份以防旧请求重放恢复内容；旧结果重试返回 `invalidated=true`。单纯关闭转写不是临时会话。

纪要 `coverage` 披露可用/纳入/遗漏片段、截短来源与记录首尾时间；`continuous_capture_verified=false` 明示这些信息不能证明没有采集缺段。模型仍最多使用 80 条、18000 字材料。新增片段后重生成纪要时，仅内容、负责人、期限相同且来源重叠的任务复用旧 ID/完成状态；语义改写仍需后续任务关联设计。

state 最多返回 100 条转写（按时间正序）、300 条记忆、300 条行动项、30 条草稿及最新纪要；记忆按收藏优先及时间倒序。导出各集合最多 10000 条，暂不提供分页。纪要中的 tasks 是生成时的草稿快照，当前完成状态以 state.tasks 或 export.tasks 为准。`include_demo` 为兼容模型字段，不会为托管账号注入模拟内容。

### 自定义行动清单与通知（2026-10-18）

- `GET /v1/tasks`：当前账号最多10000条行动项，含原有字段及 `due_at`（UTC ISO时间，旧AI待办为空）、`reminder_hours`（旧记录为0）。此快照不等待语音业务锁。state原有最近300条保持兼容。
- `POST /v1/tasks`：`{task_id, content, owner, due_at, reminder_hours}`。客户端UUID（16–64位字母/数字/连字符），内容1–500有效字、负责人0–100字、带时区的未来截止时间，提前量0/1/6/24小时（0为不提醒）。返回行动项，同ID同内容幂等，不同内容409；已删除ID不能被迟到重试重建。手动任务没有report或录音来源，不消耗模型额度。
- `PUT /v1/tasks/{id}`：`{content, owner, due?, due_at?, reminder_hours}`，编辑已有手动或AI任务，返回更新后的行动项；未知/跨账号/已删除404。内容1–500有效字、负责人0–100字、文字截止时间0–500字；`due_at`可为空，否则须含时区并统一为UTC；有具体时间时覆盖文字due。允许保留已过期截止时间，过期不安排通知；提前量0/1/6/24，有提醒时必须提供具体截止时间。保留原ID、创建时间、完成状态和来源，不创建副本。修改过的AI行动项独立保存，原会议纪要的生成快照不变，重新整理不会覆盖该行动项或恢复完全相同的原始行动项；撤回原来源时仍可联动删除。未改变字段的保存不解除原纪要关联；重复相同PUT幂等，不消耗模型额度。
- `PATCH /v1/tasks/{id}`：原有open/done契约保留。Android只更新对应卡片、计数及提醒，不刷新整页、不改变排序或当前滚动位置。
- `DELETE /v1/tasks/{id}`：同账号删除，未知/跨账号404。删除AI待办保留会议本身，并记住同场内容/负责人/截止时间指纹，后续重新生成不重建完全相同的已删除行动项；不影响其他手动任务。

Android添加/编辑共用圆角表单；左滑露出编辑和删除，编辑预填内容、负责人、截止时间和提醒选项。保存只替换原卡片，保留列表顺序与视口；取消不保存，失败保留输入。没有具体时间的旧AI任务可保留原文字截止时间，选择日期和时间后启用提醒。

Android以系统AlarmManager安排本地通知，提前量按绝对截止时间计算。编辑截止或提前量会取消旧闹钟、清除旧的已通知状态并重新安排；仅改内容/负责人不重发已送达提醒，待发送通知使用最新内容；关闭提醒取消闹钟和通知。提醒时刻已过去而截止时间仍在未来时尽快提醒；已到期、完成、删除的任务不安排。通知直接进入工作页的“行动清单”选项卡；重启、时间/时区变动及包更新后恢复，前台/刷新/任务修改时同步。退出账号、切换服务、清空和注销取消相应本地提醒；权限未允许时显示说明与通知设置入口。采用无额外特殊授权的非精确闹钟，省电策略可能延迟（[Android官方说明](https://developer.android.com/develop/background-work/services/alarms)）。本地已知状态下完成/删除会取消提醒；其他设备的更改在本机下次同步后生效，不是服务器远程推送。位置与录音的前后台策略保持原样。

### 用户修正并重新生成纪要（2026-10-18）

`GET /v1/work-meetings/{id}/revision` 返回 `{base_report_id, document}`，仅已结束且有可编辑纪要、当前未在整理时可用。document含title，以及summary/tasks/viewpoints/conflicts/events/topics/decisions/open_questions/chapters/highlights分类。summary为逐段 `{id,text}`；其他分类保留原字段、隐藏source_ids并增加稳定编辑id。Android按分区提供文字编辑、添加与删除；新条目id为 `new-UUID`，技术标识及依据不展示为可编辑文字。失败时优先返回同一版保留的编辑稿。

`POST /v1/work-meetings/{id}/revise-summary`：`{request_id, base_report_id, document}`。请求ID幂等；基版已变化或整理中409，不覆盖其他更新。严格校验分类、条目ID、文字/数量及总长（最多90000字JSON，总结最多8000字）；客户端提交的引用不可信，服务端由基版恢复并为用户改动生成独立 `user-edit:` 依据。编辑稿和pending任务先持久保存；不重新上传/识别录音，不占用原语音业务锁进行模型网络请求。

AI保留剩余条目顺序/数量，分析用户新增、修改、删除后的版本，同步修改未编辑字段中的人物、时间、任务、决策等关联表述。用户改过的字段及新增条目在服务端强制保留；删除项不可被模型恢复，空分类保持空；总结按段保护，允许用户全部清空。未改部分仍执行原有结构与引用校验；不将用户修正冒充录音发言或附上伪造音频时间。真实模型请求逐次计额度，结构不合法最多纠正一次，鉴权/限流/超时等不盲目重试。

`revision_request` 包含id/base_report_id/document/created_at/status（pending/done/failed），失败另含固定error原因码。成功原子替换本场纪要和关联行动项，匹配的旧任务保持完成状态，独立手动任务保留；返回report含revision_id/revision_sources。失败保留上一版report和完整编辑稿，状态恢复到可查看/编辑，不发布规则摘录覆盖用户修改。已有用户修正的纪要拒绝旧retry-summary入口（409），防止旧客户端绕过编辑稿从原文覆盖修正。服务重启后正常读取恢复pending，清空/注销/撤回来源后迟到结果不能重新发布。Android编辑过程中还按服务/账号/会议/基版保存私有本地草稿，稍后继续可恢复，退出账号/清空时删除。

### 前台位置记录（2026-10-18）

`POST /v1/location-records` 接收 `{record_id, observed_at, trigger, longitude, latitude, accuracy, source, speed?}`。`record_id` 为16–64位字母、数字或连字符；`observed_at` 必须含时区，接受过去10分钟至未来2分钟，统一保存为UTC；`trigger` 仅 `app_open/page_return/interval`；坐标为手机系统 WGS-84，精度0–200米。响应 `{id, observed_at, trigger, longitude, latitude, accuracy, source, speed}`。同一编号和相同内容幂等返回；编号已存在但内容不同返回409。Android 进一步只接收60秒内、带精度的系统结果，每次等待最多25秒。

记录按账号隔离持久保存，可通过上述GET读取并包含在v4导出中；清空业务数据/注销账号同时删除。它使用独立接口和手机网络线程，不等待语音业务锁，不调用地图或文字模型，不扣语音/业务请求额度。最新坐标可供后续请求使用；业务处理期间暂存，待原有业务释放锁后再更新上下文，不主动重算模式、路线、提醒或播报。手动定位仍走原 `/v1/location`。

Android 在已登录且前台可见时，打开App、切换主页面、进入/退出会议详情即尝试获取位置，此后每180秒发起一次；同一轮未结束时合并重复触发。退到后台或锁屏停止监听、计时和未完成的上传，返回前台重新刷新；不启用后台常驻定位或新增后台定位权限。取得位置并收到保存成功回执后仅更新状态行，不再显示“定位已更新”浮层；失败也只更新状态文字。蓝牙连接成功的3秒浮层保留。

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
| 计数操作 | 新 chat/voice、每次 speech、recall、meetings、地图 search/nearby/weather/weather-at/commute-preview/snapshot、location 各计一次；聊天后合成语音通常两次，录音后合成语音也通常两次。已完成 chat/voice 重试不计；其他操作可能即使上游失败或会议命中缓存仍计数 |
| 同账号并发 | 需要业务锁的操作同账号同时一项，第二项 409；全局最多八项此类活动业务操作，满时 503。只读状态查询与地图预览 `POST /v1/commute/preview`、静态图 `POST /v1/maps/snapshot`、目的地天气 `POST /v1/weather/at` 不占该账号业务锁或全局活动名额；后三者仍鉴权并计入每日业务额度 |
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

`GET /v1/export` 返回 v4 快照，包含工作会议及位置记录，当前没有导入接口。`DELETE /v1/data` 要求当前密码，删除业务数据及幂等结果、保留账号和设置；账号注销使用 `DELETE /auth/account`。删除在线数据不自动删除运维历史备份，备份规则见部署文档。

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

## 工作会议 Qwen 3.1 转写与开始信息（2026-10-17）

工作会议 `/v1/voice?meeting_id=...&segment_index=...` 使用独立的
`qwen-audio-3.1-asr-flash` HTTP 接口，开启 `speaker_diarization_enabled`。
`capabilities.work_asr` 表示该配置就绪，`work_asr_model` 返回模型名；工作模式客户端不再以通用 `asr` 能力判断是否可以开始。未配置返回 503，不自动回退旧模型。普通语音和实时通话保持各自原有接口。

`POST /v1/work-meetings` 增加可选 `started_at`（带时区、距当前不超过一天）和
`location: {longitude, latitude, accuracy, source: "gps", observed_at?, cached?}`。`observed_at` 为实际定位获取时间（带时区，不能比服务器时间晚超过120秒）；`cached` 为严格布尔值，默认 false，true 时必须提供获取时间。旧客户端省略两字段时保留原响应结构。Android 从账号/服务隔离的本地缓存及手机系统缓存选择最近24小时内、精度不超过200米的位置；仅页面前台、屏幕亮且未锁定时，才会为超过两分钟或缺失的位置最多等待三秒新定位。熄屏/后台只读缓存，前台获取失败也回退缓存，保留原获取时间并标记 cached=true。无授权或无合格位置时不附位置，不阻塞会议。前台每180秒定位继续更新私有缓存，上传失败仍保留，不增加后台定位权限或持续定位。后端在响应发送后后台使用高德将保存的开始坐标转换为 `location.address`，每次转换最多12秒，不等待地址结果才开始录音。首次创建的时间地点不可被重试覆盖，也不使用会议结束时的位置替换。

列表、详情均返回 `location` 对象；无位置为 `{}`。完成的 report 同样包含 `started_at`、`location`。地址解析、重试、纪要重新生成与数据导出保留 `observed_at/cached`；Android 页面及纪要文件仍只展示地址，并对缓存标注“缓存定位”和原始获取时间。退出账号、清除数据、删除账号或切换服务会清除本机缓存。
创建会议、读取 `/v1/state` 或会议列表/详情时，对缺失地址且尚未用尽次数的历史坐标安排补查。每场首次失败后，服务端每隔30秒重试一次，最多额外3次；间隔从上次请求失败返回起算，每次请求最多12秒。`address_attempts`（0–4）与 `address_retry_at`（Unix秒或null）持久保存，重启不会重置次数，重启后下一次鉴权读取恢复未到期/到期的任务。成功后停止并同步已有 report，重试用尽后停止。正常运行时重试不依赖页面轮询；同一账号会议去重，每次读取最多安排两条、全局最多八条待办和两条并发转换；未配置地图 Key 时不发请求、不消耗尝试。App 列表、详情和导出只展示地址，等待时显示待解析，重试用尽显示“地址暂未获取（已重试3次）”，不展示经纬度。仅使用会议开始时保存的坐标，不用后来的定位替代；没有开始坐标时无法反查。晚到地址仅可补入仍存在且开始坐标未改变的会议，不恢复已清空记录。
每条 sources 保留原始 `text`，另有 `utterances: [{text,speaker_id,begin_time,end_time}]`。
时间单位为片段内毫秒，speaker_id 仅在该片段内有效，不代表真实身份或跨片段稳定身份。

`summary_status=pending` 显示“正在总结”（分组或汇总进度），`ready` 显示“总结完成”；
失败/摘录降级不显示成功。完成时详情自动展示摘要，用户仍可切换原始文稿。

### 千问服务商配置补充

已按现有服务商文档确认 Flash HTTP 地址为 `https://maas.qianwenaiapi.com/api/v1/services/aigc/multimodal-generation/generation`。默认复用同域名实时服务的 `QWEN_OMNI_API_KEY`，独立 `QWEN_WORK_ASR_API_KEY` 优先。其他域名不继承该密钥。仅该服务明确返回的 `CLIENT_ERROR / ASR_RESPONSE_HAVE_NO_WORDS` 被视为空语音片段，其余错误仍报识别失败。

用户提供的 Filetrans 文档为整段文件 URL 异步识别接口；当前保留分段原文流程，使用同服务商 Flash 多人识别接口，不把 Filetrans 协议套用到 Flash。

## 整场文稿总结（2026-10-17 最新约定）

此节取代此前“分组分析/层级合并”的总结约定。录音上传仍采用原有可靠提交机制，但 Android 将所有识别原文按顺序展示为一份连续文稿，不显示上传片段标题。录音过程中不生成小结；结束后把全部已保存原文一次交给文字模型，不切分分析、不截断内容、不使用中间摘要替代原文。来源 ID 仅用于引用追溯。

总结新增 `viewpoints: [{person,text,source_ids}]`、`conflicts: [{text,source_ids}]`、`events: [{content,time,location,task,source_ids}]`，并保留 tasks、topics、decisions、open_questions。未知人物/事件时间/地点/任务使用空字符串，界面显示未明确；人物、时间、地点必须出现在引用的原文里。任务与已确认决策沿用原有来源校验。

工作页顺序为整场摘要、任务总结、各人物观点、内容冲突与澄清、重要事件、其他议题与重要信息、决策、待确认事项。重要事件逐项显示内容、时间、地点和相关任务，不能用录音开始时间地点替代事件本身的信息。录音开始快照仍单独显示。

输入没有人为字符截断或分段总结。若超出所配文字模型的上下文容量、模型调用失败、输出被截断或证据校验失败，状态为未完成并保留原文，不宣称整场总结完成。接口结构新增字段不要求重写历史纪要，历史记录可重新生成。

会议标题：成功总结后，模型按整场主要议题/核心决策生成单行标题，自动成为该记录在列表和详情中的标题；最多12个字符（标点/数字/字母计入）。提示词要求关键主题前置，后端去除空白和“标题：”包装并兜底限制长度，避免超长标题导致整份总结丢弃。已有历史记录不会批量改名，重新总结时应用新规则。

## 整场说话人对话（2026-10-17 最新修正，覆盖前述 Flash/连续纯文本展示）

新版 Android 创建会议时传 `whole_audio=true`，能力要求 `work_whole_audio=true`。录音只通过已鉴权 `POST /v1/work-meetings/{id}/audio?upload_index=N&request_id=UUID` 保存 16kHz PCM16 音频数据；这是可靠上传机制，不做分段 ASR，也不形成界面上的“片段”。每次请求限制100毫秒至60秒，单场限制两小时。旧客户端/旧记录仍保留兼容路径。

结束后将整场录音拼成一个 WAV，以现有千问服务密钥调用 `qwen-audio-3.1-asr-flash-filetrans`，明确启用 `diarization_enabled=true`。通过服务商 `/api/v1/uploads?action=getPolicy&model=...` 临时上传音频，随后提交异步转写任务并持久化 task_id，重启后恢复查询；明确失败时由用户点击重试重新提交。供应商临时资源绑定主账号/模型，有效期48小时；该临时上传方式的生产容量限制见供应商文档，正式高并发部署应换为生产对象存储。无需新增公开音频下载路由。

账户 SQLite 暂存上传音频，转写成功后删除，清空私密数据同样删除；超过24小时的原始音频在 Store 初始化时清理，过期后可能无法重试。临时 WAV 用临时文件对象，处理退出自动关闭。未把音频加入导出、Git、APK或网站。完整会议若上传数据缺失，不能静默转写成“完整”。

`whole_audio` 返回布尔值，`transcription_status` 为 recording/pending/ready/empty（旧记录为legacy）。处理中显示“正在识别整场发言人”，之后再显示“正在总结/总结完成”。成功 sources 为按发言顺序保存的句子，带 `speaker_scope=meeting`、`speaker=发言人 N` 及原始句子时间/供应商 speaker_id。编号按整场结果首次出现顺序映射，同号共享同一人物标签，不代表真实姓名。无法识别时显示未识别发言人，不凭内容猜测。

原始文稿按“发言人＋原话”逐条展示与复制，不展示上传边界。总结仍一次使用整场已保存原文，包含发言人标签，允许按整场匿名人物分别整理观点；明确第一人称承诺可使用该匿名标签作为任务负责人。旧记录没有整场音频可重做时不能凭局部编号建立整场身份。

参考：https://platform.qianwenai.com/docs/developer-guides/speech/asr 和 https://platform.qianwenai.com/docs/api-reference/more/upload-file-get-temporary-url

## 2026-10-17 双分支整合后的工作会议契约（覆盖前述冲突说明）

- 当前 Android 使用 `whole_audio=false` 开场，保留开始时间与可用位置快照，通过 `/live` 接收实时字幕、通过 `/v1/voice` 上传含 `offset_ms` 的录音段。字幕覆盖时 `recognize=0` 不重复识别；字幕不可用时使用已配置的 Qwen Flash 或火山按段兜底。实时说话人仅在各上游识别段内稳定。
- 结束后，在 Qwen 工作 ASR 已配置且音频可用时，以录音时间轴组装整场 WAV，提交一次 Filetrans 说话人识别，再一次性总结全部已保存原文。最终句子替换实时句子，保留时间轴、暂停、重点标记和录音回放；统一标签为 `说话人1/2/...`，仅最终整场结果标注 `speaker_scope=meeting`。完整录音缺失时保留原有转写，不伪造完整识别。
- 无 Qwen 配置时保留火山实时/分段转写和文字纪要。`capabilities.work_asr` 表示至少一种工作转写可用；`work_whole_audio` 表示旧整场上传协议仍受支持，不是当前 Android 开场的必选门槛。
- 兼容 `whole_audio=true` 的旧调用方和 `POST /v1/work-meetings/{id}/audio`。旧 SQLite 暂存上传在 Store 初始化时幂等导入账号音频目录，转写成功前保留旧副本。新上传在统一音频目录保存；同一个请求号携带不同 PCM 返回409。
- `transcription_status` 补充 `failed`。整场识别失败后，有实时原文的会议仍可整理该原文，并明确提示整场说话人识别未完成；无原文则显示失败。手动重试可重试识别，终态失败清除 task_id，暂时性查询错误保留 task_id。已忘记的原文不得由正在返回的识别结果重建。
- 当前 Android 的录音保存遵守 `work_audio_retention_days`（默认30天，0不落盘，因此不再做结束后的整场识别）。旧 `whole_audio=true` 客户端即使设0也需暂存以完成识别，成功立即删除，失败最多额外保留24小时供重试；不是所有录音都转写后立即删除。已保存录音窗口起点超出最后样本时返回404。
- AI 纪要保留整场任务、人物观点、冲突、事件、议题、决策、待确认，并结合可选的 `chapters/highlights`；章节时间由服务端依据句子计算，非逐字摘录的重点丢弃。Android 全屏会议页及 Markdown 导出包含这些内容和对应时间引用。

以上是本地源码/协议整合，真实服务配额、长会议、实体耳机和远端部署另行验收，见 [工作日志](work-log-2026-10-17.md)。


## 2026-10-18 常用地址与主动通勤

全部接口沿用 Bearer 账号鉴权，数据位于账号 SQLite，互不共享。

- `GET /v1/saved-addresses` 返回 `{addresses:[...]}`；`PUT /v1/saved-addresses/{id}` 保存，`DELETE` 删除。地址字段为 `tag: home/work/school/custom`、`name/address/city`、有限且合法的 GCJ-02 `longitude/latitude`、可选六位 `adcode`。家/公司/学校各一个，自定义可多个，总数最多40。客户端先调用既有 geocode 或手机定位后的 reverse，再保存结果；CRUD、重新选用、仅改名称/标签都不调用地图服务。修改城市或地址后必须重新定位。既有 home/work 设置无查询迁入，常用地址成为对应设置的权威值。
- `POST /v1/commute` 接收 `journey_id`（16–64位字母数字或连字符）、`origin_mode: gps/fixed`、`origin`（fixed必填、gps为空）、`destination`、`transport: driving/walking/transit/bicycling`。起终点均为上述地址字段，标签可省略。明确开始才建立 active 通勤并切换通勤模式；工作录音中拒绝。相同 ID/内容幂等，不同内容409；已结束的同 ID 不重新开始。开始另一通勤前需结束当前通勤。
- `GET /v1/commute` 返回本账号最近一趟通勤或 `{}`。返回 `id/active/origin_mode/origin/destination/transport/started_at/route/error/updated_at` 等状态；route沿用地图路线协议，含耗时、距离、步骤与来源提示。保存地址后再修改/删除地址不会改写已开始旅程快照。
- `POST /v1/commute/{id}/refresh` 接收唯一 `request_id`；gps模式同时提交 `location:{longitude,latitude,accuracy,source:'gps',observed_at}`，要求有时区、两分钟内、精度<=200米。fixed模式禁止提交手机位置，以已保存GCJ-02起点计算，公共交通使用保存城市，不重复坐标转换/城市反查。正常30秒最短间隔，进行中45秒租约，每次供应商调用最长35秒，计入账号每日查询额度。GPS坐标仍按原地图适配流程转换。网络失败保留上次路线并明确 error；停止、清空或新旅程后到达的旧结果不能覆盖当前状态。刷新不占用语音会话锁。
- `DELETE /v1/commute/{id}` 结束，重复结束相同旅程安全；不存在404。切换到非通勤模式也结束。服务端不自行采集设备位置，Android在前台服务内每180秒提交。
- `POST /v1/maps/weather-at?forecast=false` 接收已保存地址，优先复用adcode查询天气；历史地址无adcode时才按坐标反查。`GET /v1/drafts` 返回最近30条草稿；GPS通勤进入提醒时间范围时每趟生成一次到达草稿，仍需用户复制发送。fixed路线不据此推断实际到达。
- 导出补充 `saved_addresses/commute`；清空业务数据删除旅程和位置记录，保留账号设置及常用地址；删除账号移除全部数据。手机断网结束时立即停止定位，私有队列在下次登录进入后补发结束，开始新旅程前先处理该队列。

保存地址消除的是重复地址解析查询；实时路线、天气和周边查询仍需联网地图 API。公共交通数据为线路规划/计划时刻，不表示实时车辆位置。

## 通勤地图预览（2026-10-18）

- 新增 `POST /v1/commute/preview`：需鉴权；接收 `origin_mode: gps/fixed`、可选 `origin`/`location`、可选 `destination` 和四种 `transport`。地址遵循上面的 PlacePoint 契约；GPS location 必须为两分钟内且精度≤200米的手机坐标，转换一次后以 GCJ-02 规划。只有一个端点时返回该标记，不请求路线。返回 `origin/destination/route/coordinate_system`，不创建旅程、不改模式或设置、不写位置记录，不争用语音锁。路线失败仍可返回已知端点，`route.available=false`。
- 通勤预览及旅程刷新优先选高德返回候选中距离较短、同距离耗时较短的一条。驾车使用 v3 `strategy=11` 获取候选，其他方式按实际候选选择；不声称所有道路组合的数学全局最短。普通对话工具的默认路线策略保持原样。
- `route` 新增 GCJ-02 `origin/destination_point/geometry/geometry_partial/selection`。`geometry` 为最多4条折线、每条最多120个经纬度对，抽稀保留端点与主要转折。公交只取所选方案与首条可替代公交线；无铁路线形时不伪造直线。超过静态图4条覆盖物限制时显示较长的4段并标记 `geometry_partial=true`，客户端明确提示部分路段缺少线形。
- 新增 `POST /v1/maps/snapshot`：鉴权后提交 GCJ-02 `origin`/`destination`（至少一个）和可选 `paths`；坐标必须有限且合法，路径数量/点数有界。后端用现有 Web 服务 Key 请求高德静态底图并自动适配所有端点和路线，返回 `image/png` 或 `image/jpeg`，上游图片上限4MiB。客户端不接收 Key 或含 Key 的URL，供应商异常不透出凭据。失效鉴权401、参数错误422、额度用完429、上游失败502。
- `POST /v1/maps/weather-at` 改为独立只读查询，不等语音锁；仍优先复用地址 adcode，缺少时才反查。以上三个查询入口各计一次既有账号每日业务额度；未配置/不可用不制造天气或地图。
- Android 仅通勤页在前台时更新预览/图片，正常间隔180秒；更换端点/方式立即更新，旧请求取消并按账号/请求身份过滤迟到结果。目的地天气自动获取，正常10分钟刷新；失败有界间隔重试，手动刷新可提前更新。真正通勤的180秒锁屏定位和路线服务仍由 CommuteService 负责，切走页面只取消预览请求。

依据高德官方[静态地图](https://lbs.amap.com/api/webservice/guide/api/staticmaps)、[路径规划](https://lbs.amap.com/api/webservice/guide/api/direction)、[天气查询](https://lbs.amap.com/api/webservice/guide/api/weatherinfo)接入。

## 对话启动通勤与站点信息（2026-10-18）

- `capabilities.commute_dialogue=true`：在已选通勤模式中，直接文字提问/连续语音可收集起点、目的地和出行方式。缺项返回 `interaction=commute_collect`，不默认当前位置或驾车。命名地址有多个候选时询问序号；家/公司/学校复用本账号保存的坐标。收集草稿按账号保存在 `commute_data.dialogue`，30分钟过期，取消和清除数据会清空。
- 信息完整后返回 `interaction=commute_ready`、`speak=false` 和 `commute_plan`。计划字段与 `POST /v1/commute` 相同。此时尚未启动行程；Android 收到新结果，正常释放语音通话后，经过定位权限流程再提交。连续语音返回 `call.done`。历史请求恢复只展示结果，不自动执行计划。
- `GET /v1/commute/{id}/briefing`：本账号活跃行程的播报资料，返回 `journey_id/text/weather/route`。复用已计算路线，另查询目的地实况天气；路线/天气不可用会在播报中说明。接口占用查询额度、独立于对话锁并复核认证；非当前活跃行程404。固定起点估算不冒充当前位置实时到达时间。
- 公共交通 `route.steps[].station_points` 保留每段出发站、途经站、到达站的顺序，`name` 必有，GCJ-02 `longitude/latitude` 仅供应商返回有效值时存在；原 `stations` 字符串列表保留。每个 segment 的 buslines 为备选，仅使用选定线路。
- Android 站点提醒无短信接口、无号码配置、无 SEND_SMS 权限。使用本机通知和 `/v1/speech`；原有“给联系人到达草稿”功能不变。
