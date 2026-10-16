# 聆 · Aidol

面向 24 小时黑客马拉松的耳机随身助手：通过 Viaim 蓝牙耳机采集和播放音频，由 Web 后端处理实时语音、工作记录、通勤提醒、生活陪伴与共享记忆。

**默认在每位队友自己的电脑运行。** 耳机连接自己的电脑，浏览器访问自己的 `localhost:8000`，Python 服务、`.env` 和 SQLite 各自独立；只通过 Git 的 `main` 分支协作代码。现阶段不需要公共服务器、部署平台或频繁发布。模型和高德仍是外部 API，本地运行不代表完全离线。

主应用位于 `app/` 和 `web/`，使用 Python 3.11+、FastAPI、SQLite 和原生 ES Modules 前端，无需 Node 构建。`web_duplex_demo/`、`qwen_omni_demo/` 保留为供应商协议参考，不是主应用启动入口。

## 先跑起来

三种系统首次运行都需要已安装 **Python 3.11+ 并加入 PATH**，以及联网安装 Python 依赖。队友拉取 `main` 后，将项目负责人私下提供的 `.env` 放在仓库根目录，即可复用其中已配置的服务；无需每人重新注册或配置一套新 API。已有自己的 `.env` 时先备份，再由本人决定是否替换；启动脚本不会覆盖现有文件。`.env` 永不提交 Git，也不要发到公开群或写进文档。

| 系统 | 根目录启动入口 | 终端命令 |
| --- | --- | --- |
| Windows | 双击 `start.bat`，或使用 `start.ps1` | `powershell -NoProfile -ExecutionPolicy Bypass -File .\start.ps1` |
| Linux | `start.sh` | `sh ./start.sh` |
| macOS | 双击 `start.command`，或使用 `start.sh` | `sh ./start.sh` |

所有入口均自动进入项目目录，无需激活虚拟环境。Windows 使用 `.venv/Scripts/python.exe`，Linux/macOS 使用 `.venv/bin/python`；只共享代码和获授权的 `.env`，不要复制其他系统的 `.venv`。Git 中已设置 Unix 脚本可执行权限和 LF 换行。若 ZIP 解压丢失权限，macOS 可运行 `chmod +x start.command`，或直接使用表中的 `sh` 命令。

Windows 也可以直接双击仓库根目录的 **`start.bat`**。它调用同一个启动脚本，首次自动安装依赖；保持窗口打开。或者在仓库根目录执行：

```powershell
# 已收到 .env 则直接运行下一行；仅未收到时才使用 .env.example 建立模板
powershell -NoProfile -ExecutionPolicy Bypass -File .\start.ps1
```

这里的执行策略仅作用于这次启动的 PowerShell 进程，不修改电脑的永久策略。脚本创建虚拟环境、安装依赖并启动。依赖已安装后的日常启动可直接执行：

```powershell
.\.venv\Scripts\python.exe run.py
```

Linux / macOS：

```sh
[ -f .env ] || cp .env.example .env # 已收到配置时保留它
sh ./start.sh
```

可选参数：Windows 的 `-Check` / Unix 的 `--check` 只检查环境，不安装依赖、不修改文件；`-InstallOnly` / `--install-only` 仅创建环境与安装依赖；`-Dev` / `--dev` 同时安装测试依赖。Linux 如果提示无法创建虚拟环境，请安装与 Python 版本匹配的 `python3-venv`；macOS 可从 python.org 安装 Python。发现已有但不兼容的 `.venv` 时脚本会停止并提示，不自动删除任何目录。

启动脚本创建 `.venv` 并安装依赖。打开 [http://localhost:8000](http://localhost:8000)，交互 API 文档在 [http://localhost:8000/docs](http://localhost:8000/docs)。没有模型 Key 也可点击「演示完整一天」，跑通模式切换、文字提取、记忆、消息草稿；演示发言均标记为模拟场景。

新建 SQLite 的默认语音通道是 `cascade`，与已验证的豆包 ASR 2.0 → Turbo → TTS 配置对应。已有数据库保留原设置：若之前选了 `auto`，请在「偏好与设备」选择「豆包完整链路」并保存。`auto` 只尝试原生实时供应商，不会自动转到 `cascade`。

可选本地 Docker（不要求使用，也不部署公共服务器）：先在 `.env` 设置非空 `APP_TOKEN`，启动后在页面「偏好与设备」填写同一口令。容器内部监听所有接口，启动保护要求口令；宿主仍只映射本机端口。

```sh
[ -f .env ] || cp .env.example .env # 随后编辑 APP_TOKEN
docker compose up --build -d
docker compose logs -f aidol
```

Compose 默认只绑定本机 `127.0.0.1:8000`，数据库存在本机 `aidol-data` 卷。日常协作直接运行 Python 脚本即可。手机跨设备访问属于后续选项，需另外解决 HTTPS；当前不要求搭建。

## 当前能力

| 能力 | 实现与边界 |
| --- | --- |
| 耳机音频 | 浏览器选择 Viaim 麦克风/输出，PCM 流经后端到实时模型；蓝牙先在系统配对 |
| 豆包 / Qwen | 独立实时协议适配，自动策略先尝试豆包，建连失败尝试 Qwen；Qwen 凭证已实测建立会话并返回音频，豆包实时需对应服务凭证 |
| 豆包语音流水线 | `cascade` 默认持续 PCM → ASR 2.0 → Turbo/记忆 → TTS；真实 stream WebSocket 全链路通过，输出 357804 字节 PCM、ASR 持久化；Viaim 实物待验收 |
| 工作记录 | 实时转写进入独立记忆队列；文本模型提取或规则降级；工作模式拦截助手播放 |
| 独立 ASR / TTS | ASR 2.0 连续 WebSocket、flash ASR 与 TTS 均已真实验证；原有凭证可用，无需因旧资源配置失败另购权限 |
| 多人发言 | 保留收到的转写；文本可指定发言人；实时输入未做声纹分离，远处拾音效果需真机测试 |
| 自动模式 | 手动选择优先，再按场景文字、最近运动、工作时段、今日行程记忆判断，并显示理由 |
| 通勤 | 浏览器 GPS → 高德坐标转换 → 驾车/步行/公交规划 → 提醒草稿；公交为计划数据，播报文本也可触发提醒 |
| 地点与天气 | 高德坐标转换、驾车/步行/公交规划与地点搜索已真实联调；另有 3 km 周边 POI、城市天气 API，后两者待真实验收 |
| 生活 | 默认间隔 300 秒监听 30 秒，需开关、当前页面授权和可见页面；VAD 用于已开启会话中的说话检测和打断 |
| 会议纪要与待办 | 按所选时段整理工作转写，生成带原话来源的纪要与行动项；待办可勾选完成；相同输入快照复用报告；不是自动识别单场会议 |
| 问记忆 | 在本地记忆、转写和行动项中查找相关证据，显示摘录与引用；依据不足明确提示，不自由编造答案 |
| 全局记忆 | 记忆时间线、重要收藏、内容更正、手动「立即记住」、转写卡「记住这句」、搜索/删除/导出；`task` 类型是待办线索，不等于有完成状态的行动项 |
| 联系人通知 | 生成草稿供复制或忽略；没有真实发送渠道 |
| 硬件控制 | ANC、按键、电量、厂商唤醒事件等待 Viaim SDK；浏览器降噪不等于耳机 ANC |

「已配置」只表示变量非空，不代表服务权限已验证。Qwen 文本触发返回音频的探测，也不等于 Viaim 真实麦克风端到端验收。

## 配置矩阵

供应商凭证由后端读取 `.env`，前端仅获得能力标记。`.env.example` 提供字段示例，不包含可用凭证；更改后重启后端。

| 变量 | 用途 | 缺少时 |
| --- | --- | --- |
| `HOST` / `PORT` | 默认 `127.0.0.1` / `8000` | 使用默认值 |
| `DATABASE_PATH` | 默认 `data/aidol.sqlite3` | 自动建库 |
| `APP_TOKEN` | 可选共享访问口令；在界面「偏好与设备」填写 | 本机 Demo 无口令 |
| `DOUBAO_API_KEY` | 豆包实时双工服务 Key | 自动策略尝试 Qwen |
| `DOUBAO_REALTIME_URL` | 比赛示例 `openspeech` JSON 双工 WebSocket | 使用示例地址 |
| `DOUBAO_MODEL` / `DOUBAO_VOICE` | 默认 `1.2.6.1` / `zh_female_vv_jupiter_bigtts` | 使用默认值 |
| `QWEN_API_KEY` | 比赛 MaaS 实时服务 Key | 无实时备份 |
| `QWEN_REALTIME_URL` | 默认 `wss://maas.qianwenaiapi.com/api-ws/v1/realtime` | 使用默认地址 |
| `QWEN_MODEL` / `QWEN_VOICE` | 默认 `qwen3.8-omni-flash-realtime` / `Tina` | 使用默认值 |
| `TEXT_API_KEY` / `TEXT_BASE_URL` / `TEXT_MODEL` | OpenAI 兼容文本服务，用于文字交互和记忆清洗；配置示例为 `doubao-seed-2-1-turbo-260628`，关闭思考模式 | 规则提取和模板建议 |
| `AMAP_API_KEY` | 高德 Web 服务 Key，坐标转换与驾车路线 | 明确显示无 ETA，播报提醒仍可演示 |
| `UPSTREAM_PROXY` | 可选上游代理 | 直接连接 |
| `SPEECH_VOLC_APP_KEY` / `SPEECH_VOLC_ACCESS_KEY` | 独立火山流式 ASR 凭证 | 录音文件识别不可用，实时模型自带 ASR 仍独立工作 |
| `SPEECH_VOLC_RESOURCE_ID` | 默认 `volc.seedasr.sauc.duration`，ASR 2.0 资源 | 使用默认资源 |
| `SPEECH_VOLC_WS_URL` | 默认 `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async` | 使用 ASR 2.0 连续识别地址 |
| `SPEECH_ASR_MODE` | `stream`（默认连续识别）或 `flash`（分段 HTTP 备用） | 默认 stream |
| `TTS_VOLC_APP_KEY` / `TTS_VOLC_ACCESS_KEY` | 独立火山 TTS 凭证 | 文字合成 API 不可用，实时模型语音仍独立工作 |
| `TTS_VOLC_RESOURCE_ID` / `TTS_VOLC_VOICE_TYPE` | 当前可用组合 `seed-tts-1.0` / `zh_female_jitangmeimei_mars_bigtts` | 使用默认值，资源与音色必须匹配 |
| `TTS_VOLC_WS_URL` | 默认火山 v3 bidirection TTS WebSocket | 使用默认地址 |
| `PROVIDER_PROXY` | 独立 ASR/TTS 适配器的可选代理 | 直接连接；与 `UPSTREAM_PROXY` 分开配置 |

火山方舟文本 Key、语音 ASR/TTS 凭证、豆包实时双工 Key 属于不同服务，不能仅凭名称相同替换。Qwen 默认端点属于比赛 MaaS，不应默认把 DashScope Key 用于该地址。

## 项目地图

```text
app/
  main.py                 REST、SSE、WebSocket 与应用生命周期
  config.py               后端配置与能力状态
  domain.py               请求模型、设置校验、模式枚举
  storage.py              SQLite 持久化
  events.py               有界事件总线
  providers/realtime.py   豆包 / Qwen 协议归一化
  providers/volc_speech.py 独立火山 ASR / TTS 二进制协议
  services/audio.py       音频会话与异步转写队列
  services/cascade.py     PCM VAD 切句 → 独立 ASR → 文本 Agent → TTS
  services/streaming_cascade.py 持续 PCM/ASR、独立记忆队列和可打断 TTS
  services/agent.py       模式决策与三种场景业务
  services/memory.py      文本 LLM 提取与规则降级
  services/insights.py    工作转写纪要、决策和行动项的生成与校验
  services/recall.py      有界本地检索与原文证据筛选
  services/conversation.py 文字交互模型，关闭思考并限制回复长度
  services/maps.py        高德坐标转换与驾车路线
web/
  app.js                  管理界面与 REST/SSE
  memory.js               纪要、待办、引用问答与记忆时间线
  audio/hardware.js       浏览器耳机适配和 Viaim SDK 边界
  audio/session.js        采集、传输、播放、打断、资源释放
  audio/pcm-worklet.js    PCM16 重采样与音量检测
scripts/                  启动与实时服务探测
docs/                     架构、接口、硬件、交接、演示手册
```

- [整体架构与模式决策](docs/architecture.md)
- [Viaim 接入与厂商资料清单](docs/hardware-integration.md)
- [REST / SSE / 音频协议](docs/api.md)
- [24 小时分工与交接验收](docs/handoff.md)
- [演示步骤与故障排查](docs/demo-runbook.md)
- [需求实现与验收证据](docs/acceptance.md)
- [会议纪要、问记忆与时间线用法](docs/memory-features.md)
- [硅基记忆官网调研与设计依据](docs/memory-product-research.md)

## 验证

```powershell
./.venv/Scripts/python.exe -m pip install -r requirements-dev.txt
./.venv/Scripts/python.exe -m pytest tests -q
node --test tests/audio-worklet.test.mjs tests/audio-session.test.mjs
./.venv/Scripts/python.exe -m compileall -q app
# 以下建立真实服务会话，需要相应 .env 配置并可能产生费用
./.venv/Scripts/python.exe -m scripts.probe_realtime --provider qwen --text-test
./.venv/Scripts/python.exe -m scripts.probe_realtime --provider doubao --text-test
# 文本提取和高德真实集成探测（按配置调用服务）
./.venv/Scripts/python.exe -m scripts.probe_services
# 独立 TTS → 重采样 → ASR 联调，不采集用户麦克风
./.venv/Scripts/python.exe -m scripts.probe_speech
# 主应用已在本机8000端口启动时，验证真实WebSocket级联，写入本地测试记忆
./.venv/Scripts/python.exe -m scripts.probe_e2e
```

Linux / macOS 使用 `.venv/bin/python`。实时探测输出连接结果、音频字节数、文本事件数和错误类型，不打印凭证；`session=true` 且 `audio_bytes>0` 说明文本触发到语音输出成功。

当前是单用户、单进程 Demo：共享设置和记忆，一次只应连接一个语音会话。不要启用多个 Uvicorn worker。关闭浏览器后不提供全天后台唤醒，后续需要厂商 SDK 或原生宿主。

## 运行通道与依赖

| 通道 | 配置 | 能力与选择理由 |
| --- | --- | --- |
| 文字演示 `demo` | 无 Key 可运行；配置 TEXT 后可文字模型交互 | 先联调模式、记忆、草稿与 UI |
| 豆包语音流水线 `cascade` | 独立 ASR；完整语音回复另需 TEXT + 独立 TTS | 默认 stream 持续传入音频，按供应商最终句子进入记忆和回复；flash 为可选分段备用 |
| 原生实时 `qwen` / `doubao` / `auto` | 对应实时服务 Key；TEXT 为可选记忆提取增强 | 适合低延迟语音，Qwen 已验证；auto 仅在原生实时供应商之间切换 |

各通道共用工作/通勤/生活、设置和记忆。`cascade` 是连续 ASR、文本模型与 TTS 组合，不是豆包原生实时模型，不承诺相同延迟。每位队友按自己的凭证选择通道；SQLite 不通过 Git 同步。ASR 2.0 配置参考[官方流式识别文档](https://docs.volcengine.com/docs/DoubaoVoice/LargemodelstreamingautomaticspeechrecognitionAPI?lang=zh)。
