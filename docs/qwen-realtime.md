# 千问原生实时语音接入

最新 Android 生活/通勤已改为同一持续全双工通道 `duplex=1`，无需唤醒词。旧的单轮录放交替协议保留兼容；产品当前约定以 [三模式约定](mode-experience.md) 与 [API全双工补充](api.md) 为准。

复用同事 Demo 的独立实时 ASR 与回复通道设计；没有恢复浏览器客户端、复制其 `.env` 或新增产品内编排器。普通回答仍由 Omni 增量返回文字和音频，工具使用现有账号工具集。

## 当前链路

Rise SDK 16 kHz 单声道 PCM16 → 已鉴权 `/v1/voice/stream?engine=qwen&reply_audio=1&duplex=1` → Qwen 实时 ASR 持续识别 → 现有账号编排器接纳发言 → Omni 增量文字及 24 kHz PCM16 → Android 耳机媒体输出。整个过程保持麦克风开启；不是录完退出后再播放。

播报中普通人声只参与识别，不提交到回复模型；识别到“小万”时停止当前回答，继续聆听同句后续问题。聆听期不需要称呼。手机以当前 turn 的 `playback.done` 确认实际排空，避免生成完成但耳机还在播放时过早解除关键词保护。没有静默挂断或固定整场时长；单击结束，双击结束并切换到目标模式待机。

生活与通勤共用此逻辑和工具。工作持续录音转写，不执行资料中的命令。显式记忆回执使用既有 TTS；普通回答不调用独立 TTS。兼容单轮入口（不带 duplex）仍保留原始音频进入 Omni 的流程及每轮60秒上限，不能与当前持续通话混同。

## 配置和启用

实时 ASR 人声检测门槛为 `QWEN_OMNI_ASR_VAD_THRESHOLD=0.65`（范围大于0、小于1），生活与通勤共用。它是云端人声检测阈值，不是麦克风音量倍数；没有裁掉较小振幅的原始PCM。已接纳的问题从排队、思考到实际播完都受到“小万”打断规则保护，普通 VAD 起声事件不能取消回答或把旧问题标记过时。无静默退出。

后端 `.env` 使用以下字段，默认地址、模型和音色来自原压缩包：

```dotenv
QWEN_OMNI_API_KEY=
QWEN_OMNI_WS_URL=wss://maas.qianwenaiapi.com/api-ws/v1/realtime
QWEN_OMNI_MODEL=qwen3.8-omni-flash-realtime
QWEN_OMNI_VOICE=Tina
QWEN_OMNI_TRANSCRIPTION_MODEL=qwen3-asr-flash-realtime
QWEN_OMNI_OUTPUT_FORMAT=pcm24
```

`pcm24` 是该接口的 24 kHz 输出格式名称，客户端接收的采样位宽仍为 16 bit。配置要求 TLS，不接受带用户名、密码、查询参数或片段的上游 URL。`UPSTREAM_PROXY` 可配置本机代理。

填入 Key 并重启后端后，`/v1/state` 返回 `voice_realtime=true`、`voice_continuous_qa=true`、`voice_realtime_provider=qwen`、`voice_barge_in=true`（持续全双工入口）。生活/通勤统一选择通义。缺少能力时客户端拒绝开麦并提示，失败不重放音频或切换为静默记录。能力仅表示配置完整，不保证权限、余额或网络。

当前真实验证针对上述 MaaS 地址；其他地址/模型需单独验证，不把它当作已验证的百炼兼容性承诺。密钥只存在后端 `.env`，不进入 Git、APK、网站、诊断输出或手机设置。

## 协议与可靠性

- Android 在对话页前台、耳机就绪、已有麦克风权限且生活/通勤待机时提前准备。ASR与Omni并行建立，提示音 AudioTrack 在后台预分配；不提前播放、不申请音频焦点、不启动采音。用户接通后复用连接与播放器，耳机实际输出仍在播放时重新核验。网络预连接不改变工作录音流程。
- 手机在符合条件的前台待机期间每秒检查准备状态；失败或过期的对象会清除并补建，尝试间隔至少12秒。单次手机准备资源110秒后释放并更新，服务端就绪后最多等待120秒；离开页面/进入后台、退出账号、切换后端、断连/双耳摘下时停止维护并清理。单击恰好遇到准备不可用时走正常接通流程。该阶段不扣应用业务额度，但供应商连接资源和计费受供应商规则约束。

- `ready` 在上游会话确认后发出。PCM 大小、录音上限、账号锁、配额和 request_id 单次使用语义沿用原接口。
- `reply.start.text` 可为空，随后 `reply.text.text` 是累计回答快照；音频可先于完整文字到达，手机立即缓冲播放。
- `reply.done` 只表示正常生成结束；`reply.error` 表示不完整；`reply.interrupted` 表示取消播放/生成。最终仍需 `result` 和 `stream.done`。未完成的回答标记 `response_complete=false`，保留在本轮回执供检查，不作为完整助手发言写入后续历史。
- 打断立即清空手机音频队列，并请求 `response.cancel`；断连取消上游与业务子任务，清理后释放账号锁。已发生的业务写入不承诺回滚，按 request_id 查询结果。
- 工具只使用现有账号工具集：检索、地图、天气、时间、私人记忆；最多三轮、六次调用，保留配额和来源回执。先在初始会话声明工具；本次真实接口使用 flat function call 与 `function_call_output` 回灌，也兼容 Demo 较早的批量 `items` 工具事件。按真实事件形状选择回灌格式，不能用音频格式推断。
- 音频总量最多 6 MiB，单帧最多 24000 字节，手机队列最多 384000 字节；文字、工具参数、上游帧和等待均有上限。供应商错误不回显原始正文或凭证。

## 验证

2026-10-17 最新持续通话使用独立 Qwen ASR 与 Omni 回复连接，取代上一版持续通话内的原生自动抢话。供应商实测同连接切换 VAD/生成状态出现 InternalError，因此采用同事 Demo 的独立识别方式：ASR 持续收音，业务侧只放行有效提问，Omni 按明确请求增量输出文字/音频。播报期间“小万”是唯一语音打断词；普通声音不进入回答上下文。它不是唤醒词，通话开始和聆听期没有名字门槛。兼容单轮原生音频入口仍保留。`scripts/probe_qwen_keyword.py --live` 验证完整服务编排、普通声音过滤、关键词打断、静默保持及时间工具；会消耗真实供应商额度。

离线回归使用 `tests/test_qwen_realtime.py` 和 Android `VoiceUploadTest`，所有凭证与供应商均为模拟。

以下脚本会消耗真实供应商额度，必须手动运行，使用合成语音而非用户录音：

```sh
python scripts/probe_qwen_realtime.py --live
python scripts/probe_qwen_realtime.py --live --tools
python scripts/probe_qwen_realtime.py --live --tools --account
```

第一项验证原生语音；第二项验证真实时间工具回灌；第三项创建临时隔离账号，验证两轮账号接口、来源回执、持久化和历史上下文。脚本不写音频文件、不修改开发者账号数据，`--account` 的记忆抽取使用本地规则。实际观测和硬件边界见 [验收记录](acceptance.md)。
