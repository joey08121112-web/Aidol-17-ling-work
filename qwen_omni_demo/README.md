# Qwen3-Omni Web Demo

把火山引擎 Seeduplex（豆包实时全双工语音）Web Demo 改造为对接 **千问AI平台 Qwen-Omni-Realtime** 的版本。
复刻了原 demo 的大部分能力，并适配了千问 MaaS 的真实协议。

零第三方依赖：后端只用 Python 标准库（含手写的 RFC6455 WebSocket 客户端），Python 3.9+ 可直接跑。

> **重要更正**：早期版本按「自建 vLLM-Omni、连本地 `ws://127.0.0.1:8091/v1/realtime`」设计，那是错的。
> 千问 MaaS 的实时接口是 `wss://maas.qianwenaiapi.com/api-ws/v1/realtime`，**事件名也和多模态开源栈不同**。
> 本版所有端点、鉴权、事件名、session 字段均已对真实服务实测验证。

---

## 一、前置条件

只需要一把 **千问AI平台（platform.qianwenai.com）的 API Key**，不用自建 GPU。

Key 从平台控制台申请，格式形如 `sk-...`。填到页面里，或设为后端环境变量。

### 环境变量（全部可选）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `QWEN_OMNI_MODEL` | `qwen3.8-omni-flash-realtime` | 模型名，会拼进 URL 的 `?model=` |
| `QWEN_OMNI_VOICE` | `Tina` | 默认音色 |
| `QWEN_OMNI_HOST` | `maas.qianwenaiapi.com` | 上游主机 |
| `QWEN_OMNI_PORT` | `443` | 上游端口 |
| `QWEN_OMNI_TLS` | `1` | 置 `0` 走明文 `ws://` |
| `QWEN_OMNI_PATH` | `/api-ws/v1/realtime` | 上游路径 |
| `DASHSCOPE_API_KEY` | 空 | 设了就不用在页面填 key |

#### 模型名要选对（实测）

| 模型名 | 结果 |
|---|---|
| `qwen3.8-omni-flash-realtime` | **可用**（当前默认） |
| `qwen3-omni-flash-realtime` | 连接成功但 `session.update` 报 `Model not exist` |
| `qwen3.5-omni-flash-realtime` | 连接超时 |

#### 音色要选对（实测）

可用：`Tina`、`Dylan`、`Sunny`、`Serena`、`longanlingxin`
不支持：`Cherry`、`Ethan`、`Jada` —— 传了会在生成阶段报 `Voice 'xxx' is not supported`

---

## 二、启动

```bash
python3 server.py --host 127.0.0.1 --port 8765
```

要挂载外部 MCP 工具（可选）：

```bash
python3 server.py --mcp-config mcp_servers.example.json
```

打开 `http://127.0.0.1:8765`，填入 API Key，点「开始通话」。

> **Key 的存放方式**：页面里的 key 只存在于当前页面内存，**不写 localStorage**，刷新即失。
> 它会随建会话请求发给本地后端，由后端放在 `Authorization: Bearer` 头里直连上游。
> 服务默认只绑 `127.0.0.1`，**不要改成 `0.0.0.0`**，否则等于把 key 和本机文件 FC 一起对外暴露。
> 也不要把 key 写进命令行参数——会留在 shell 历史和进程列表里。

启动时终端会打印：

```
Serving web demo at http://127.0.0.1:8765
Upstream: wss://maas.qianwenaiapi.com:443/api-ws/v1/realtime?model=qwen3.8-omni-flash-realtime
Default local file tool root: C:\Users\<你>\Desktop
MCP: 未启用（加 --mcp-config 可挂载外部工具）
```

---

## 三、协议映射：前端为什么不用改

千问 MaaS 沿用 OpenAI Realtime 的**事件骨架**，但具体名字和开源多模态栈（vLLM-Omni）不一样。
差异全部在后端 `normalize_upstream_event()` 里抹平，前端 `handleUpstream()` 一行都没动。

### 服务端 → 客户端（实测枚举）

| 千问 MaaS 实际下发 | 归一化为 | 说明 |
|---|---|---|
| `response.audio.delta` | `response.output_audio.delta` | 下行音频，base64 PCM |
| `response.audio.done` | `response.output_audio.done` | 音频结束 |
| `response.audio_transcript.delta` | `response.output_text.delta` | 模型说的话（文本增量） |
| `response.audio_transcript.done` | `response.output_text.done` | 模型说的话（完整） |
| `response.text.delta` / `.done` | `response.output_text.*` | 纯文本模态时 |
| `input_audio_buffer.speech_started` | `conversation.item.input_audio_transcription.started` | 若平台下发则直接映射 |
| 其余（`session.*`、`response.done`、`error`…） | 原样透传 | 两边同名 |

### 关于打断

千问 MaaS **不单独下发「用户开始说话」**。原 demo 的打断逻辑挂在
`conversation.item.input_audio_transcription.started` 上，这个事件收不到就会失效。

后端因此做了一件事：**在第一段用户转写（`.delta`）到达时，先补发一个 `.started`**，再发转写本身。
这样前端「用户一开口立刻掐断播报」的逻辑原样生效，不用改前端。

同时 `turn_detection.interrupt_response: true` 让服务端也参与打断判定，双保险。

### 客户端 → 服务端

| 事件 | 用途 |
|---|---|
| `session.update` | 会话配置（音频格式、音色、指令、VAD、工具） |
| `input_audio_buffer.append` | 上行音频，base64 |
| `input_audio_buffer.commit` + `response.create` | 强制判停（手动兜底） |
| `conversation.item.create` + `response.create` | 文本输入（文本不触发 VAD，必须显式请求生成） |

**结束会话不发事件，直接断开 WebSocket。** 官方实时文档里写着「发送 `session.finish` 事件关闭会话，
或直接断开 WebSocket 连接」，但**客户端的「事件参考」页只列了 8 个客户端事件，不含 `session.finish`**；
实测本端点也明确拒绝它：

```json
{"type":"error","error":{"type":"invalid_request_error","code":"invalid_value",
 "message":"Invalid value: 'session.finish'.","param":"type"}}
```

所以后端走「断开连接」这条官方认可的路径：`close()` 置 `close_requested` 并 shutdown socket，
唤醒阻塞中的读线程，由 `_run` 的 `finally` 完成收尾。

### 音频规格

| 方向 | 格式 | 采样率 |
|---|---|---|
| 上行 | `pcm16` 单声道 | 16 kHz |
| 下行 | `pcm24` 单声道 | 24 kHz |

会话配置里用**字符串**字段名（`input_audio_format` / `output_audio_format`），
不是 OpenAI 那种嵌套的 `audio: {input: {format: {...}}}`。这是两个平台最容易踩的差异点之一。

### VAD 模式

默认用 `server_vad`，由服务端自动判断说话起止并触发响应，客户端只需持续 append 音频、**不用 commit**。
「强制判停」按钮走的是手动兜底路径（commit + response.create）。

---

## 四、和原 demo 的差异

### 保留的能力

- **实时语音对话**：麦克风持续采集 PCM16/16k 单声道，按 20ms 一包上行
- **上行永不断流**：静音、关麦、麦克风启动失败时继续发全零帧
- **随时插话打断**：见上一节
- **QA 轮次聚合展示**：识别文本 + 模型回复按问答对展示，支持搜索高亮
- **事件面板**：`↑` 上行 / `↓` 下行 / `•` 本地状态
- **本机文件 Function Calling**：`tools` 字段经实测可被 `session.update` 接受
- **可插拔 MCP 工具层**：挂载任意 MCP 服务，工具动态发现并注入 `session.update`；调用在独立线程池执行，不阻塞音频收发
- **危险工具二次确认**：会改外部状态的工具挂起等人点确认，60 秒超时自动取消
- **音色选择**：通过 `session.voice` 热切换，**这是 Qwen-Omni 真实支持的能力**（早期版本误删过）
- **自定义 System Prompt**：连接后改动走 `session.update` 热更新
- **三栏可拖拽布局**、错误 Toast、通话计时

### 本轮新增的 UI / 体验能力

| 能力 | 说明 |
|---|---|
| **明暗双主题** | 跟随系统 `prefers-color-scheme`，也可手动切换并记忆。首帧前由内联脚本定好主题，避免闪白 |
| **实时音量波形** | 56 根柱子的滑动电平表，替代原来的进度条。快起慢落，静音时是一排小点而不是整条消失 |
| **通话记录与导出** | 每轮问答落一条内存记录，可导出 Markdown / JSON |
| **轮次耗时统计** | 每轮展示判停耗时、首字延迟、总耗时 |
| **断线自动重连** | 指数退避 1s / 2.5s / 5s / 10s 共 4 次，顶部倒计时提示条，可「立即重连」或「取消」 |
| **移动端响应式** | 补了 640 / 480 两档断点，小屏单栏布局，无横向溢出 |

配色统一收敛到 `:root` 的设计令牌层（`--surface-*` / `--accent*` / `--danger*` / `--warn*` / `--call-*`），
暗色主题是叠加式覆盖块，改色只需动令牌。

### 砍掉的能力

| 被砍功能 | 原因 |
|---|---|
| Dialog ID 上下文继承 | 千问 MaaS 实时接口无此概念（上下文由连接本身维持） |
| 语速 / 音量 | 实时接口无对应参数 |
| 声音复刻 | 平台有独立的声音复刻接口，但不属于实时对话链路 |
| 联网搜索 / 地理位置 / 唱歌 / 主动回复 | 火山平台增值能力，千问侧没有 |
| Ogg / Opus 播放链路 | 下行是 PCM，约 400 行解复用代码不再需要 |

### 关于「兼容 Anthropic」那个地址

你提到的 `https://maas.qianwenaiapi.com/apps/anthropic` 是**文本对话的 Anthropic Messages 协议兼容端点**，
用于 Claude 风格的 `messages` 调用。**它跟实时语音完全是两条链路**，不能用来做语音对话。
实时语音必须走 `wss://maas.qianwenaiapi.com/api-ws/v1/realtime`。

---

## 五、需要留意的事

1. **静默不掉线**：后端每 20 秒发一次 WebSocket ping 保活，否则读超时会被误判为断线。
   单会话最长 120 分钟，到点服务端主动关闭。
2. **上行开销**：客户端每 20ms 一次 HTTP POST 到本地后端，这是原 demo 的设计，生产环境建议改 WebSocket 上行。
3. **下行码率**：PCM16/24kHz 单声道约 384 kbps，是 Opus 的 6～10 倍。
4. **音频采样率**：前端硬编码 24000。若改成 `session.output_audio_format` 的其他取值，需要同步改 `OUTPUT_SAMPLE_RATE`。
5. **上游报错不再掐断通话**：`error` 事件只弹提示，不结束会话（否则「缓冲区为空」这类小错会直接挂断）。
6. **重连只认「值得重试」的错**：只有 5xx 和网络类错误才自动重连；4xx（key 无效、参数错、配额不足）
   会直接弹「通话创建失败」并给出上游原文，不做无意义重试。后端在错误负载里多带了一个数字
   `status_code`，前端靠它区分这两类——只给 `HTTP/1.1 401 Unauthorized` 这种状态行字符串没法比大小。
7. **主题偏好**存在 `localStorage` 的 `qwen_omni_demo_theme` 键里。**只存主题，key 依旧不落盘。**
8. **本轮自检覆盖范围**：主题切换、波形渲染、导出、重连状态机（退避次数、去重、取消、错误分类）
   都在真实浏览器里跑过断言，29 项全通过。**但真实麦克风上行、打断的实际听感、真实断线后的重连成功率
   需要真人 + 真 key + 耳机才能验** —— 本次开发环境没有可用的 key，这几项留给首次实机通话时确认。

---

## 六、上行链路：背压、超时与回收

这三件事平时看不出来，但都直接决定「网络一抖，通话会不会就此哑掉」。

### 上行是串行的，所以要格外小心

音频帧按 20ms 一帧、走 `sendChain` 串行发出 —— 串行是为了保证 PCM 顺序，
乱序的音频在识别端听上去就是噪声。代价是**一帧卡住会拖住它后面所有帧**：

- 若只是「无上限排队」，网络一抖队列越积越长，通话延迟只增不减，base64 字符串还把内存吃掉
- 若只是「丢新帧、等旧帧超时」，25 帧要一帧帧超时，最坏几十秒里麦克风等于哑的
- 所以采用**放弃旧链**：在途帧超过 25 帧（约 500ms）判定拥塞，
  递增代际号、换一条新链，旧链里没跑到的回调靠代际号判断自己作废、直接跳过。
  这样上行能在 500ms 内回到可用状态，代价是丢掉约 500ms 音频。

| 参数 | 值 | 说明 |
|---|---|---|
| `MAX_INFLIGHT_AUDIO_FRAMES` | 25 | 在途上限，约 500ms |
| `AUDIO_POST_TIMEOUT_MS` | 1500 | 单帧超时。串行链上这个值不能大 |
| `POST_TIMEOUT_MS` | 15000 | 建会话等非音频请求 |

超时用 `AbortSignal.timeout`（不支持该 API 的浏览器会退化成无超时，行为同改造前）。

### 会话回收

会话对象持有事件队列、上游连接和工具配置，不摘除就会一直涨内存。
但**不能一结束就删** —— 前端 SSE 可能还在读最后几条事件（包括报错详情）。
所以跑完后保留 **120 秒**，再由后台线程按 TTL 回收。

事件队列有 2000 条上限，满了丢最旧的；但 `local.closed` 一定会挤进去 ——
它要是被挤掉，SSE 就得等下一次写失败才知道该退出。

---

## 七、文件结构

```
qwen_omni_demo/
├── README.md              本文件
├── server.py              后端全部逻辑
│   ├── MinimalWebSocketClient   手写 RFC6455 客户端，支持 wss，无 websockets 依赖
│   ├── LocalFileTools           本机文件 FC 工具（内置，只读）
│   ├── normalize_upstream_event 事件名归一化层
│   ├── DASHSCOPE_EVENT_ALIASES  事件别名表
│   ├── DuplexSession            每会话一线程，含事件泵、保活线程、工具线程池与 SSE 心跳
│   ├── DemoState                会话注册表，按 TTL 回收已结束会话
│   ├── configure_mcp            按配置拉起 MCP 服务
│   └── Handler                  HTTP 路由
├── mcp_client.py          零依赖 MCP 客户端（stdio JSON-RPC 2.0）
│   ├── McpStdioServer           单个 MCP 服务子进程，请求/响应按 id 配对
│   └── McpToolRegistry          多服务聚合、工具名前缀路由、只读判定
├── mcp_servers.example.json     MCP 服务配置样例
├── static/
│   ├── index.html
│   ├── app.js
│   └── style.css
└── tests/                 测试套件（零依赖，可独立跑）
    ├── run_all.py               一键入口
    ├── test_mcp.py              MCP 客户端 33 项
    ├── test_tool_flow.py        工具调用链路端到端 44 项
    ├── test_close_path.py       挂断路径 11 项
    ├── test_reap.py             会话回收 9 项
    ├── fake_mcp_server.py       假 MCP 服务（真按协议说话，不是 mock）
    ├── eval.mjs / shot.mjs / measure.mjs    CDP 驱动 headless 浏览器
    └── verify_*.js              前端断言脚本
```

### HTTP 接口

| 路径 | 方法 | 说明 |
|---|---|---|
| `/` | GET | 页面 |
| `/api/session` | POST | 创建会话，需带 `api_key`，返回 `session_id` |
| `/api/session_update` | POST | 热更新 instructions / tools / voice |
| `/api/audio` | POST | 上行一帧音频（base64） |
| `/api/audio_commit` | POST | 强制判停 |
| `/api/text` | POST | 文本输入并触发生成 |
| `/api/tool_confirm` | POST | 危险工具调用的确认/拒绝，需带 `session_id` 与 `token` |
| `/api/close` | POST | 结束会话 |
| `/api/events` | GET (SSE) | 事件流，15 秒心跳 |
| `/api/mcp_status` | GET | 查看已挂载的 MCP 服务与工具数 |

---

## 八、Function Calling

### 工具来自两处

| 来源 | 说明 |
|---|---|
| **内置** | `LocalFileTools`，默认只暴露 `list_local_directory`（只列一层，不递归）。全部只读，不需要确认 |
| **MCP** | 通过 `--mcp-config` 挂载的外部 MCP 服务，工具动态发现、可按需增删 |

MCP 工具名统一加前缀 `mcp__<服务名>__<工具名>`（超 64 字符会截断并去重），
既避免跨服务撞名，也让调用回来的路由不用查表猜。

### 完整闭环

```
用户开口 → 模型决策 → 后端执行 → 结果回灌 → 模型开口
         (function_call)  (工具)   (role=tool)  (response.create)
```

最后那个 **`response.create` 是必需的**。`role="tool"` 的消息本身不会触发新一轮生成 ——
跟 `send_text` / `commit_audio` 里那句是同一个道理。漏掉它，模型拿到结果后不会说话，
整条链路看起来像"没反应"。

### 工具在线程池里跑，不在事件泵线程里

`_handle_function_call()` 在事件泵线程里**只做派发**，真正的执行丢给 `MAX_TOOL_WORKERS = 4` 的线程池。

这一点对 MCP 尤其要紧：MCP 工具要跟子进程通信、甚至去操作别的软件，几百毫秒到几秒都正常。
内联执行会把事件泵按住，那段时间里**下行音频断流、上行帧堆到背压阈值、用户打断也没人响应**。

### 危险工具的二次确认

**语音场景格外需要这道闸**：VAD 误判、背景人声、甚至电视里的对白都可能触发工具调用，
模型自己不会替你踩刹车。所以凡是会修改外部状态的 MCP 工具，都不会直接执行。

判定优先级（`McpToolRegistry._read_only`）：

1. 配置里的 `write_tools` → 需要确认
2. 配置里的 `read_only_tools` → 免确认
3. MCP 工具自带 `annotations.readOnlyHint: true` → 免确认
4. 服务级 `auto_approve: true` → 免确认
5. **兜底：需要确认**（拿不准的工具宁可多问用户一句）

流程：

```
模型要调可写工具
  → 后端挂起，推 local.tool_confirm_request（带 token 和超时秒数）
  → 页面弹出确认卡片（确认执行 / 拒绝）
  → POST /api/tool_confirm {session_id, token, approve}
  → 通过才真正执行，然后回灌结果 + response.create
```

- 60 秒（`TOOL_CONFIRM_TIMEOUT_SECONDS`）没确认按拒绝处理，回灌 `expired`，不让模型干等
- 拒绝回灌 `denied`，同样要一次 `response.create`
- 会话结束会清空所有挂起的确认，页面上的卡片同步置灰

### 挂载一个 MCP 服务

```bash
python3 server.py --mcp-config mcp_servers.example.json
# 或用环境变量：QWEN_OMNI_MCP_CONFIG
```

配置格式（`mcp_servers.example.json` 有完整示例）：

```json
{
  "servers": [
    {
      "name": "filesystem",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "D:\\workbuddy"],
      "timeout": 30,
      "auto_approve": false,
      "read_only_tools": ["read_file", "list_directory", "search_files"],
      "write_tools": ["write_file", "edit_file", "move_file"]
    }
  ]
}
```

单个 MCP 服务起不来只记一笔失败（启动日志里能看到），**不影响主链路**。
挂载后可用 `GET /api/mcp_status` 或启动日志确认工具是否加载成功。

### 自定义工具 schema

内置工具之外，页面上的 `FC Tools JSON` 仍可手写额外工具（会被 `session.update` 接受，实测返回 `session.updated`）。
MCP 工具永远追加在这批手写工具之后，不会被覆盖。

---

## 九、自己从头跑一遍

### 1. 启动服务

```bash
cd qwen_omni_demo
python server.py
```

零依赖，Python 3.9+ 直接跑。终端应打印三行，见「二、启动」。

> **改了 `server.py` 必须重启进程。** Python 不热加载，否则你调的还是旧代码 —— 这个坑很容易浪费半小时。

### 2. 页面里接通

打开 `http://127.0.0.1:8765` → 填 API Key → 点「开始通话」→ 允许麦克风。

**第一次验请戴耳机**：扬声器外放会让模型听见自己的声音，来回抢话，听不出真实效果。

按顺序验，前一项过了再进下一项：

| 步骤 | 做什么 | 该看到什么 |
|---|---|---|
| ① 接通 | 随便说一句 | 事件面板出现 `↓ session.created`；延迟条正常走 |
| ② 打断 | 趁模型说话时开口 | 播报立刻停；面板出现 `↑ ...transcription.started` |
| ③ 工具 | 「列一下桌面有什么文件」 | 面板出现 `• local.tool_result` |
| ④ 闭环 | 看上一步之后模型是否开口 | **这是本轮改动的关键验证点**：工具结果出来了但模型不吭声，就是 `response.create` 那条路没走通 |

### 3. 跑测试套件

```bash
python tests/run_all.py          # 后端四项，约 35 秒，不需要 key、不需要浏览器
python tests/run_all.py --front  # 追加前端回归，约 110 秒
```

后端四项自带假上游（最小 RFC6455 服务端）和假 MCP 服务，用独立端口，**不会碰你正在跑的服务**。
`--front` 需要 demo 服务已启动；脚本会自己拉起 headless Edge，跑完自动收掉，不会留下浏览器进程。

### 4. 挂 MCP 服务试真工具

```bash
python server.py --mcp-config mcp_servers.example.json
curl --noproxy '*' http://127.0.0.1:8765/api/mcp_status
```

`--noproxy '*'` 别省：系统代理开着时 `curl` 会把 `127.0.0.1` 也送去代理，表现是看不懂的 502。

### 常见故障

| 现象 | 原因 | 处理 |
|---|---|---|
| 服务像是起来了，接口却 404 / 行为是旧代码 | 8765 上还挂着旧进程，Windows 允许同端口重复绑定，请求被随机分发 | `netstat -ano \| grep :8765` 找 PID，`taskkill //PID <pid> //T //F` |
| 页面点了没反应 | 服务没起 | 看终端有没有打印 `Serving web demo at ...` |
| 通话秒断并弹上游原文 | key 无效 / 模型名不对 / 配额不足（4xx） | 重连只对 5xx 和网络错生效，4xx 不做无意义重试 |
