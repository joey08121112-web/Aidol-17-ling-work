# Rise M0 / M1 实施交接

日期：2026-10-17。最初范围为 USB 本地开发和前台短语音接入。用户随后明确追加触控切模式/单轮控制、佩戴、电量、前台有界重连，见 [Rise 联动](rise-controls.md)；M2 实时语音、M3 后台服务、闪录与盒子未实施。真机通过状态以 [验收记录](acceptance.md) 为准。

> 后续范围更新：项目现仅维护 Android、账号后端和官网，旧浏览器与独立供应商 Demo 已退出源码。以下保留 M0/M1 当时的实现与验收交接；以前生成的 ZIP 是当时快照，交付当前精简源码时需重新运行打包脚本。

## 基线及已有未提交工作

开始时分支为 `main`，HEAD 为 `5fa8a552b471c001dc8389be7cb3c48a2ad3403b`。最初检查时没有重置或覆盖原有工作；用户在完成后又明确授权整理全部源码并推送 Git，最终提交以 Git 记录为准。规划中“上轮只新增文档”不等于当前仓库只改了文档。

开始时已修改：根/Android README、Android build.gradle、ApiClient、AuthVault、BluetoothCapture、CaptureSource、HostedSession、MainActivity、ReplyPlayer，以及 acceptance、android-handoff、architecture、handoff。已有未跟踪文件包括 debug 资源、AudioMode、DevelopmentSettings、EmulatorCapture、ServerEndpoint 及测试、本地后端启动器及测试、产品规划、本地测试文档、website、网站部署脚本和文档。本次只增量修改所需安卓/本地开发模块，网站工作原样保留。

## 本轮分工与固定接口

| 负责人 | 独占修改范围 | 对外交付 |
| --- | --- | --- |
| 硬件代理 | `hardware/`、SDK依赖、manifest、导入脚本 | HeadsetController、VendorCaptureSource、固定6.7 AAR、假回调测试 |
| 音频会话代理 | CaptureSource、HostedSession、CaptureTurn、ReplyPlayer、系统录音源、AudioMode | 注入来源、异步停止/取消、目标输出验证、状态机测试 |
| 开发环境/后端代理 | 后端选项与迁移、启动器、USB助手、服务端诊断 | 本地电脑/远端两项UI、内部精确源隔离、跨平台脚本、临时库与日志回归 |
| 集成负责人 | MainActivity、ApiClient、文档、整体验证与打包 | 保留五入口、设备详情、代次保护、构建与分层验收 |

共享工作区按文件所有权并行，没有多个代理同时修改同一个公共文件。收尾修复阶段将 ApiClient/传输测试交给会话代理，将 MainActivity/触控策略/模式切换重试交给开发环境代理；硬件代理只读审查，集成负责人负责验收和交付。未来真人协作使用各自克隆/分支或独立 worktree；按 `codex/` 功能分支审查，再共享到 `main`。产品运行时仍只有一套按账号隔离的业务编排。

- `CaptureSource.start(Listener)`、`stop(StopCallback)`、`cancel()`、`close()`；`StopCallback.ended()` 只在停止确认并排空后发生，失败走 `error(String)`。
- `HeadsetController` 在用户连接时才初始化 SDK，生命周期跨录音轮次；UI 只订阅 Snapshot。连接使用已配对 Rise 的明确地址，多设备由用户选择。状态/未知属性不根据名称伪造。
- `HostedSession(context, api, mode, capture, outputIdentity, listener)` 注入采集与目标输出验证。RISE_SDK 不启动系统 AudioRecord/SCO。当前音频 UI 只有“系统蓝牙兼容”和“Rise SDK 麦克风”，release 维持 BLUETOOTH；旧 EMULATOR 源码仅供历史参考，UI 不可选，会话构造也拒绝启动。
- 录音状态为 NEW → RECORDING → STOP_REQUESTED → DRAINING → ENDED；取消、失败为独立终态。提前预留停止预算，含尾帧不超过所选时长及 1920000 字节。未确认停止/超限不上传。
- SDK 回调复制麦克风缓冲进入有界队列，channel 1 不输入 ASR，255 不作为音频；偶数 PCM16 样本跨回调拼接，尾部半样本报错。SDK idle 是正常停止依据。
- UI 回调均核对会话代次，取消立即禁收、取消网络与播放、释放前台状态；旧 done/result 不得清空新会话。
- 请求 ID 贯穿语音/文字与 TTS 诊断。已有 `/v1/voice`、`/v1/chat` 幂等保持不变；可选 `X-Aidol-Request-ID` 仅关联日志，不能当成 speech 幂等。语音/聊天超时或409不自动换ID重放，先查看历史。模式设置仅对明确未应用的忙碌409做有界延后，规则见 Rise 联动文档。
- debug 精确 USB 源 `http://127.0.0.1:8108` 的每次请求使用 `Connection: close`，避免 adb reverse 中已失效的连接复用；远端 HTTPS 维持连接池。读请求允许安全恢复，写请求响应丢失不自动重发，保留原始异常用于诊断并显示中文提示。
- 后端 UI 只有“本地电脑服务”和“远端服务器”。`DevelopmentSettings.save(AudioMode, boolean)` 的本地选择固定为 USB `127.0.0.1:8108`，输入模式不影响后端。旧 `EMULATOR_LOCAL` / `local_backend=true` 迁移为 USB，旧音频 EMULATOR 迁移为 BLUETOOTH，已有 Rise 选择保留；旧枚举只留作迁移，`10.0.2.2:8108` 已拒绝。迁移只改偏好，不复制旧源 Token，USB 源无有效会话时重新登录。

## 登录与切换体验

2026-10-17 登录体验补充：同一后端只切音频时保留当前 ApiClient、登录、页面、输入草稿和结果；后端选项显示本机是否已保存登录，切回来按对应服务源恢复。登录页标明目标服务。本机密文存在不代表服务端仍有效；有效期目前为 30 天，主动退出、密码重置或会话失效后需重新登录。临时 Keystore 读取失败不再删除已保存密文。Windows/Mac 的本地账号库各自独立；同一 `127.0.0.1:8108` 换成另一台电脑后可能需要登录该电脑账号。覆盖安装应使用原签名并保留数据；卸载或清空 App 数据会丢失本机登录。

## 换电脑必须携带的内容

1. 当前源码提交或完整快照；交付前核对 `git status`，额外未提交和未跟踪实现也必须纳入。开发初期的 `5fa8a55` 基线不足以复现本次工作。
2. 官方 `耳机SDK Android 文档.docx` 和完整原始 `demo_6_7.zip`。本机来源 `D:/Backup/Downloads/` 不会随 Git 同步。
3. AAR SHA-256 固定为 `e074506fd3a46346272fdd2ab632a59b4cef2597f4cb37fd160737eb536e2b2c`。使用仓库导入脚本提取到被忽略的 `android-app/app/libs/`，Gradle 构建再次校验。
4. [USB/Windows/macOS 指南](android-local-testing.md)、[硬件契约](hardware-integration.md)、[API](api.md)、[验收记录](acceptance.md)。

本轮生成的 `artifacts/aidol-m0-m1-handoff.zip` 包含源码目录、官方 Word、原始 demo ZIP、状态/差异清单和 SHA-256 文件清单；manifest 记录打包时的提交。打包仍会纳入当时的额外未提交源码，ZIP 本身不执行 Git 提交或推送。解压到新目录，不覆盖另一位成员有改动的仓库。每台电脑独立创建虚拟环境和 `.env`，禁止传个人数据库、录音、Token、签名密钥或 `.venv`。APK 单独提供，未放入 Git。

重新打包可用 `python scripts/package_android_handoff.py --official-doc <官方.docx路径> --demo-zip <demo_6_7.zip路径>`；脚本核验AAR、收集Git跟踪及未跟踪源码（含未提交版本），排除忽略的本机数据，生成清单并逐文件核验ZIP内容。解压到Mac后运行 `chmod +x android-app/gradlew`，避免ZIP丢失执行权限。

## 共同调试

各机本地 `create_hosted_app` 固定回环 8108 和 `data/android-local/`；手机以 USB reverse 访问。共享集成环境使用已记录源码/APK/后端版本及每人独立测试账号，不边测边部署未知版本。测试顺序为后端健康/登录/文字 → SDK 就绪 → 首 PCM/结束排空 → ASR/业务 → TTS/实际目标输出。

Windows ADB 原生 USB 后端可能返回 `get-devpath=unknown`。更新后的 USB 助手使用目标 `usb:` 字段或 `adb -d get-serialno` 精确匹配验证身份；多设备不自动选择，无法确认时不建立转发。2026-10-17，本机 `23113RKC6C`（Android 16 / API 36）已授权、安装本轮 debug APK，USB 身份/8108转发通过；手机 Wi-Fi 和移动数据关闭时 App 健康检查成功，移除 reverse 并强停重开后失败，恢复 reverse 后再次成功，测试后恢复原 Wi-Fi 状态。Rise VI-A10 的真实 SDK `READY`、电量和佩戴上报已观察。手机断网注册/文字业务、SDK短语音与人耳听音仍待验，macOS 仍待验；这不是 M0/M1 整体验收通过。

使用固定合成句“请记住我喜欢清淡食物”和公共地标；只改变一个变量。收集主机系统、手机/Android、耳机/固件、APK哈希、SDK哈希、后端源、输入来源、操作步骤、session/request ID、脱敏阶段耗时、输入对照与人耳听音结果。SDK就绪、收到PCM、上游成功、人耳听见分别记录。SDK时钟/帧序号只是手机接收观察，不能证明硬件无丢包。

后续本地 USB 真机短语音已实际收到 SDK `mic_bytes=107520`，ASR/业务/TTS 成功，目标 Rise 输出核验通过，用户确认转写正确并从耳机听到回复；详见 acceptance 的请求 ID 与时延。记忆来源、断网业务、远端 SDK 组合、macOS 和异常场景仍单列待验。不能把这一轮通过扩展为所有手机/固件或后台能力均通过。

最小真机门槛与 Windows/Mac 分别的结果见 acceptance。没有设备的组合均标“待验”，不能把编译、mock、电脑 HTTP 健康检查或历史云端测试标为本轮 USB/Rise 成功。
