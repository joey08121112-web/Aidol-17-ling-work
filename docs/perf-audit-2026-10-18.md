# 性能审计与并行重构计划（2026-10-18）

三轮审计（静态全量审查 → 修复回归复核 → 动态基准+第一性质重审）的结论汇总。七个并行重构窗口以本文档为唯一共享上下文：先读本文档再动代码。

## 已实施修复（随本文档一起提交）

| 修复 | 位置 | 效果 |
| --- | --- | --- |
| 删除 /v1/voice ASR 上传人为节流（每100ms音频sleep 20ms） | `app/main.py` process_turn chunks() | 60s 语音省最多 12s 纯等待；并消除逼近火山 40s 包间超时的隐患 |
| transcripts 建索引 `transcript_created(created_at)` + 确定性查询计划守卫测试 | `app/storage.py`、`tests/test_perf_guards.py` | 20k 行时 conversation_history 8.0ms→0.6ms（13 倍，实测） |
| WAL `synchronous=NORMAL` | `app/storage.py connect()` | 本机单行事务 0.10→0.01ms（10 倍，实测） |
| 会议详情快照指纹挪 worker 线程 + 未变提前返回 + 指纹补齐进度字段 + 录音中不短路 | `MainActivity.java` workMeetingSnapshot/renderWorkMeetingDetail | 2.5s 轮询不再主线程全量序列化；进度行不冻结 |
| 账号锁快速失败竞态修复（wait_for 0.05s 有界宽限） | `app/services/mobile.py use()` | 消除"锁刚释放、等待者未唤醒"时快速失败请求被挂一整轮 |
| qwen 下行事件尺寸核算改用原始帧长 | `app/providers/qwen_realtime.py receive()/_read()` | 每分钟音频省约 70ms 事件循环 CPU |

验证状态：后端 pytest 507 通过（含新守卫）、compileall 通过；Android 205 单测通过、lintDebug 0 错误。

## 集成落地状态（W7 汇总，2026-10-18）

五个代码窗口已全部合入 `perf/integration`（合并顺序 ui → android-system → net-infra → agent-tokens → locks-background，实际按落地时序滚动执行），每次合并后双端全量回归通过；期间主线（origin/main）晨批新功能经语义合并共存（maps/work_meetings/main.py 三处冲突按"连接池改造+新功能逻辑都保留"解决）。当前集成分支验证：后端 pytest **623 通过**、compileall 通过；Android **283 单测** 0 失败、lintDebug 0 错误。

| 窗口 | 状态 | 集成分支落点 |
| --- | --- | --- |
| W1 UI 增量渲染 | 全部合入（5 提交：字幕增量/tab 缓存/ioPool/Dialog/回归修复） | eb36196…c40c466 |
| W2 Android 系统层 | 全部合入（7 修复+工作日志，窗口完成） | eb36196…72fd179 |
| W3 网络基础设施 | 合入（共享 http 连接池；含与主线同步） | 4d1bdcb、4d17430 |
| W4 Agent 智能层 | 合入（TOP-K/意图捷径/events 停写） | 244d6c4 |
| W5 锁与后台任务 | 合入（锁语义+后台任务修复；其分支已自同步主线故零冲突） | 07ce91a |
| W6 真机测量 | 基线数字已采集（见下表与 docs/acceptance.md），分支待提交 | — |
| W7 集成与清理 | 本状态列、architecture.md 天花板节、残壳目录删除、work log | 3310c68…ddf8843 |

## 实测数字（2026-10-18，本机 macOS 临时库，可重复）

| 测量 | 结果 |
| --- | --- |
| Store 冷构造（全套迁移） | 4.5–10.4 ms；热构造 ~1 ms（**首请求迁移不是显著延迟源**，静态推断已修正） |
| conversation_history(12) 无→有索引 | 1k 行 0.9→0.6ms；20k 行 **8.0→0.6ms** |
| 记忆全量注入提示词 | 50 条=7.5KB；300 条=**45.5KB**；1000 条=152KB（每轮 LLM 都携带） |
| /v1/state 载荷（按 API 上限） | ~118 KB/次刷新 |
| 每次 Store 操作固定成本 | ~0.5ms（一操作一连接的连接建立为主体） |
| 供应商首 token（历史实测，docs/acceptance.md） | 千问 1547/3235ms；账号链 2922/2187ms；火山 TTS 首块 734ms、ASR 首字 1750ms |

W6 真机基线数字（K70 4fb3c538 / Android 16，**性能修复合入前的基线构建**，详见 docs/acceptance.md 的 W6 节）：

| 测量 | 结果 |
| --- | --- |
| 冷启动（5 次 force-stop 后 am start -W） | 390–433ms，**中位 406ms** |
| 热启动（5 次） | 49–68ms，**中位 55ms** |
| gfxinfo 静置 30s（对话页前台） | 1204 帧 ≈ 40fps 常驻渲染（orb 动画动态证据）；Janky 0.50%，P95 12ms |
| gfxinfo 快速切 tab（30s 87 次） | Janky **0.00%**，P95 7ms——整屏重建在该机型未见掉帧，卡顿结论需重新归因 |
| 会议字幕滚动帧统计 | **未采集**：无已连接 Rise 耳机，录音入口被拦截，待耳机就绪复测 |
| 语音首响分段（合成 5.71s 输入，measured 日志） | asr 1775 / business 2069 / turn 3851 / tts 1767ms；其中**约 1.1s 为上传节流纯等待**（合入修复后应消失） |
| Qwen 实时账号链（--live --account 成功轮） | 发送 end 后首包 **1652ms**；另有两轮 502 复现未定位（脱敏无日志） |

## 问题清单（按窗口分组，file:line 为审计时行号，可能有 ±10 行漂移）

### W1 UI 增量渲染（唯一可改 MainActivity.java 的窗口）
- 对话页字幕渲染上限 `Integer.MAX_VALUE`，每条 ASR partial 全量 removeAllViews+重建（`MainActivity.java` renderCaptions ~1372、调用点 ~1370）；长会议每秒多次重建上千 TextView —— 感知卡顿最热路径【静态确认，耗时待真机】
- 任何状态变化=showApp 整屏 setContentView 重建（~334-390）；mutate→refresh 双重重建（~1901-1911）【确认】
- 单线程 worker 被轮询/请求/导出共用，100s read timeout 可独占排队（~53, 868, 1917）【确认】
- meetingPage Dialog 在 onDestroy 未 dismiss（~2264）【确认】

### W2 Android 系统与音频会话层
- debug 后端强制 `Connection: close` 零复用（`ApiClient.java:41-43,69-71`）【确认】
- Keystore 加解密+commit() 在主线程（`AuthVault.java:30-38,57-61`）【确认】
- CompanionOrbView 25fps 常驻自续动画（`CompanionOrbView.java:62`）【行为确认，耗电待测】
- 断连清理主线程直呼 SDK+50ms 自旋最长 5.5s（`hardware/HeadsetController.java:597-615`）【确认】
- 通话停止→出声全串行 latch 链（`HostedSession.java:283-315`）；PlaybackLease 25ms 自旋（`PlaybackLease.java:35-37`）；ReplyPlayer Thread.sleep 忙等（`ReplyPlayer.java:104,151,182-205`）【确认】
- 工作录音主循环整场 10Hz 唤醒（`HostedSession.java:510-555`）【确认，电量小额】
- VoiceSessionService 每秒主线程查音频模式（`VoiceSessionService.java:26-40`）【确认，小额】
- VoicePreRoll 逐字节取模写环形缓冲且持 vadLock（`VoicePreRoll.java:7-9`）【确认，低端机可感】

### W3 后端网络与外部调用层
- httpx AsyncClient 每次调用新建（`maps.py` 7 处、`conversation.py:18,42`、`memory.py:24`、`recall.py:131`、`insights.py:188`、`work_meetings.py:140`、`bocha.py:49`、`volc_speech.py:298`、`qwen_work_asr.py:67`、`qwen_meeting_asr.py:39`）——每次外部调用多付 TCP+TLS 握手【确认】
- weather 工具 geocode+weather 两次串行 HTTP（`assistant_tools.py:134-150`）【确认】
- qwen receive() 不校验 response_id，上一轮超时后迟到事件可泄入下一轮（`qwen_realtime.py` receive/reply ~425-428）【代码确认，频率待动态】
- ASR/TTS WSS 每调用新建（`volc_speech.py:321,378`、`qwen_realtime.py:64,104`）【确认；复用方案需评估协议语义】

### W4 Agent 智能层
- 系统提示词注入全部记忆无 LIMIT（`agent.py instructions` ~253、`storage.py conversation_memories` ~315）——300 条=45.5KB/轮，随使用必然恶化延迟与费用【实测确认】。**产品口径已确认：TOP-K（上限 60，收藏+相关性+近期优先），超限部分提示词声明可用 recall_memory 查询**
- 意图捷径仅覆盖模式切换（`voice_intent.py:9-14`）；天气/时间/路线高频意图仍走完整 LLM+工具循环【确认】
- EventBus 全仓零订阅者；events 表只写不读、随轮次无界增长、热路径每轮同步 insert（`events.py`、`agent.py` 4 处 emit persist=True）【确认】

### W5 锁与后台任务语义
- duplex 通话全程持有账号锁，派生：同账号读请求 409/挂起、纪要落库超时【确认】
- summarize_work 模型成功后落库抢锁失败 → 已生成纪要被置 failed 丢弃（`main.py` ~899-914）【确认】
- work_summary_slots=Semaphore(2) 包住 30 分钟转写轮询，两场长会议饿死所有用户总结（`main.py` ~844-850、`qwen_meeting_asr.py:66-76`）【确认】
- duplex pending(4) 满即断整通电话且文案误导为网络错误（`duplex_voice.py:23,89-90,151-152` → `main.py:620-628`）【确认】
- work_live run.queue 完全无界（`work_live.py:21,104`，对照 voice_stream.py:145,163 有显式背压）【确认】
- /v1/state 聚合读是否占业务锁需核查并改为无锁快照读（`main.py` state 路由、`mobile.py use()`）【待核查】

### W6 真机测量（唯一用 adb/真机的窗口）
- 动态证据空白：冷启动、tab 切换、长会议字幕帧统计（dumpsys gfxinfo）、语音首响分段（measured 日志）、节流删除前后对比。历史数字见 docs/acceptance.md。

## 第一性质结论（三轮后的最终判断）

1. 中继拓扑（密钥/记忆只在后端）**保留**；真正要改的是**锁语义**，不是拓扑。
2. 单进程单 worker 在 ≤500 账号规模是**正确简化**；天花板写入架构文档即可，不加 worker。
3. Agent 延迟地板=供应商首 token 1.5–3.2s；代码侧只剩 TOP-K 与意图捷径两个真杠杆。
4. UI 三个缺陷（整屏重建/字幕无上限/无视图保留）可在现有程序化 View 风格内修；**再长出 2–3 个屏时重评 Compose**。
5. state 全量 118KB 拉取保留（简单一致性），改的是**触发频率**不是协议。
6. 死重量（events 持久化、demo 残壳、旧 /v1/voice 兜底路径）按计划清理。

## 七窗口文件所有权矩阵（并行安全的核心约束）

| 窗口 | 分支 | 独占文件 | 绝对禁改 |
| --- | --- | --- | --- |
| W1 UI 增量渲染 | perf/ui | MainActivity.java、新测试 | 其他一切 Android 文件 |
| W2 Android 系统层 | perf/android-system | ApiClient/AuthVault/CompanionOrbView/VoiceSessionService/HeadsetController/hardware/*、HostedSession/ReplyPlayer/PlaybackLease/VoicePreRoll/DuplexVoice | MainActivity.java |
| W3 网络基础设施 | perf/net-infra | providers/*、conversation/memory/recall/insights/maps/assistant_tools | main.py、mobile.py、agent.py、storage.py、duplex_voice.py、work_live.py |
| W4 Agent 智能层 | perf/agent-tokens | agent.py、storage.py、voice_intent.py、events.py、新测试 | main.py、mobile.py、conversation.py、providers/* |
| W5 锁与后台任务 | perf/locks-background | main.py、mobile.py、duplex_voice.py、work_live.py | agent.py、storage.py、providers/*、services 其余 |
| W6 真机测量 | perf/baseline-measure | docs/acceptance.md、临时脚本 | 任何 app/android-app 源码 |
| W7 集成与清理 | perf/integration | 集成分支合并、docs/architecture.md、删除残壳目录 | 不新写功能代码 |

API 契约冻结：所有窗口不改 `/v1/*` 请求/响应结构与错误码语义。
