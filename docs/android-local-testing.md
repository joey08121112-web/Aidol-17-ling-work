# 同一个 Android 项目的电脑与手机测试

## M0 / M1：Windows 与 macOS 的 USB 真机入口

当前只使用安卓真机，Rise 配对手机。Windows/macOS 电脑运行后端，通过 USB 调试手机；不要求手机与电脑同一 Wi-Fi，供应商请求由电脑后端联网发出。模拟器已退出当前产品与操作流程，旧源码只作历史参考。当前代码、分工、未提交差异和完整换机交接见 [M0/M1交接](android-m0-m1-handoff.md)。

安装 Python 3.11+、JDK 17、Android Studio、Android SDK Platform 35 和 Platform Tools。设置 `JAVA_HOME` 与 `ANDROID_HOME`，或在 Android Studio 指定 JDK/SDK。Windows 必要时安装手机厂商官方 USB 驱动；macOS 通常无需厂商驱动。使用可传数据的 USB 线，手机启用开发者选项 → USB 调试，解锁并接受本台电脑 RSA 授权。每机独立创建 `.venv`，不得跨 Windows/Mac 复制。

官方 Word 与包含原版 AAR 的 `demo_6_7.zip` 已随 Git 存放在 `vendor/rise/6.7/`（Demo 已去除示例签名私钥和密码），拉取后导入固定 SDK：

```powershell
# Windows，在仓库根目录
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\import-rise-sdk.ps1 -DemoZip vendor/rise/6.7/demo_6_7.zip
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-android-backend.ps1
```

```sh
# macOS，在仓库根目录；路径替换为本机收到的官方包
bash scripts/import-rise-sdk.sh vendor/rise/6.7/demo_6_7.zip
chmod +x android-app/gradlew
sh ./start-android-backend.sh
```

启动器首次安装 Python 依赖；按 `.env.example` 在本机设置后端供应商配置。无配置时健康检查仍可返回 `ready=false`，注册/部分规则文字功能可测，ASR/TTS 不会伪装成功。服务保持电脑 `127.0.0.1:8108`，数据为 `data/android-local/`。可选 Windows `-Reload` / macOS `--reload`，默认关闭；重载会中断请求。不要改成监听全部网卡，手机统一通过 USB reverse 访问。

默认未开启重载时，修改后端代码后必须停止旧进程并重新启动，再与新 APK 联调。`/healthz` 成功只说明进程健康，不证明它已加载磁盘上的新代码；例如旧进程可能拒绝新版 `continuous_call` 参数并提示“语音会话参数不正确”。先挂断正在进行的通话，再重启后端。

在第二个终端建立 USB 转发（`adb` 在 SDK 的 `platform-tools/`；不在 PATH 时使用绝对路径）：

```sh
adb devices -l
adb -s <serial> reverse tcp:8108 tcp:8108
adb -s <serial> reverse --list
```

`<serial>` 替换成已授权 USB 手机序列号。多手机必须指定目标；`unauthorized` 时在手机确认授权，`offline` 时重插线/重新授权。无线 ADB 与模拟器不算 USB 验收。每次重插线、重启手机或 ADB 后重新检查 reverse。

仓库也提供同样功能的助手，先启动后端：

```powershell
.\connect-android-usb.ps1 -Serial <serial> -Adb '<SDK目录>\platform-tools\adb.exe'
# 只检查，不建立转发
.\connect-android-usb.ps1 -Serial <serial> -Adb '<SDK目录>\platform-tools\adb.exe' -Check
```

```sh
sh ./connect-android-usb.sh --serial <serial> --adb "$ANDROID_HOME/platform-tools/adb"
sh ./connect-android-usb.sh --serial <serial> --adb "$ANDROID_HOME/platform-tools/adb" --check
```

助手验证电脑健康检查、USB 设备与 reverse 规则；**不代表手机已经发出请求**。Android Studio 打开 `android-app/`、选择 debug 和手机后 Run；或构建后执行 `adb -s <serial> install -r android-app/app/build/outputs/apk/debug/app-debug.apk`。debug 包名 `com.aidol.app.debug`，可与已发布 APK 并存。

Windows 原生 ADB USB 后端可能对真实手机返回 `get-devpath=unknown`。助手会核对目标设备行的 `usb:` 字段，或使用 USB 专用选择器 `adb -d get-serialno` 验证返回序列号与目标完全一致；不会直接把 `unknown` 当成 USB。多台 USB 手机且没有路径证据时，先只保留目标手机再检查；多设备仍需指定 `--serial` / `-Serial`。本机 ADB 37.0.1 / `LIBADBUSB` 已通过这一身份核验。

App 登录页“连接设置”的后端只有 **本地电脑服务** 和 **远端服务器** 两项。本地电脑服务固定使用 `http://127.0.0.1:8108`（需 USB reverse）；远端服务器使用内置 HTTPS 地址。音频固定为“Rise SDK 麦克风”。当前不提供模拟器输入或后端，`http://10.0.2.2:8108` 已被地址校验和网络安全配置拒绝。

升级时，旧 `local_backend=true` 和 `EMULATOR_LOCAL` 偏好迁移为 USB 本地服务，旧 `AudioMode.EMULATOR/BLUETOOTH` 迁移为 Rise SDK；已保存的 Rise 选择保留。登录按精确源隔离：只读取 USB 源自己的加密登录状态，不复制或发送旧模拟器源的 Token；若 USB 源尚无有效会话，请重新登录。本地与远端账号、历史及记忆也不互相迁移。

1. 点“检查后端连接”，在本地注册独立测试账号，先发文字。
2. 在手机系统连接 Rise，在 debug 设置选 Rise SDK；“我的 → 设备详情”连接目标耳机，等待“耳机已就绪”。多副配对时选择目标。初始化/连接/重连不会自动开始录音。
3. 点开始，说一条合成句，点“结束并发送”；等待尾帧和结束确认，再显示转写/回复。正常AI输入只有耳机麦克风通道。回复播放需核对同一耳机的实际 Android 输出地址；系统不报告身份时会拒播，保留文字结果。
4. 工作模式的安静记录不自动朗读业务回复，主动提问与文字对话可回答并播报；操作提示按所选交互方式播放。分段记录后按选定时段生成纪要，不是完整长会议/连续双工。取消会丢弃未提交音频，已上传内容可能已保存，先查看历史再重试。
5. 权限拒绝仍可用文字；失败不会自动改为系统蓝牙/手机麦克风。系统蓝牙是用户显式选择的兼容输入。release 仍使用原系统蓝牙策略，等待 SDK 真机门槛后再改。

### 必须由手机完成的 USB 验收

- 关闭手机 Wi-Fi 与移动数据，保留 USB、蓝牙及电脑联网；从 App 完成健康检查、注册登录、文字请求。
- `adb -s <serial> reverse --remove tcp:8108` 后强制停止并重开 App，确保建立**新连接**再请求，应失败（避免已建立连接影响判断）。恢复 reverse 后重新请求成功。
- 在“本地电脑服务”和“远端服务器”之间切换，检查账号与界面不串用；升级旧模拟器偏好后核对只访问 USB 本地地址、没有带入旧源凭证。不要把部署账号误认为本地账号。
- 使用近距离发声/耳机静音等对照确认目标麦克风，记录尾句与实际听音；测试录音/上传/播放时取消、断连、来电、权限撤销、锁屏/切页和快速重开。锁屏应停止，返回不自动开麦。
- 按 [规划第8节](android-rise-product-plan.md#8-团队并行开发与共同调试) 用 session/request ID 对照 Logcat 与后端 `mobile request_id=... stage=... elapsed_ms=...` 日志。不得采集私人录音或输出凭证。

Windows 与 macOS 要分别填 [验收](acceptance.md)。2026-10-17，本机 Windows 已连接并授权 `23113RKC6C`（Android 16 / API 36），安装本轮 debug APK；USB 身份和 8108 转发通过。关闭手机 Wi-Fi 与移动数据后，App 健康检查成功；移除 reverse 并强停重开后失败，恢复 reverse 后成功。Rise VI-A10 已观察到 SDK `READY` 和真实电量/佩戴上报。Windows USB 本地服务与 SDK 6.7 的一次短语音实际收到 `mic_bytes=107520`，ASR、业务、TTS 和目标 Rise 输出核验成功，用户确认转写正确并从耳机听到回复。**记忆来源、手机断网注册/文字请求、远端 SDK 组合、macOS 连接与运行及异常场景仍待验**；这一轮通过不能代替所有手机、固件和场景的验收。

## 环境与连接方式

| 项目 | 本地电脑服务 | 远端服务器 |
| --- | --- | --- |
| App | USB 真机上的 debug APK | 真机上的 debug 或 release APK |
| 耳机 | 与手机配对 | 与手机配对 |
| 音频模式 | Rise SDK（唯一来源） | debug/release 均固定 Rise SDK |
| App 连接地址 | `http://127.0.0.1:8108`，需 USB reverse | `https://123.207.219.174:7776` |
| 账号数据 | 本机 `data/android-local/`，按账号隔离 | 部署服务器，按账号隔离 |

USB 流程通过 reverse 将手机 `127.0.0.1:8108` 映射到电脑回环，没有转发时不会直达电脑。Windows 和 macOS 使用同一手机地址。

本地与共享服务器的用户名可以相同，但它们是两套独立身份、密码、恢复码和数据。切换服务器后需在目标服务注册或登录，不会迁移历史、记忆或会议。debug 按后端地址分别保存会话，返回某个后端时可以恢复该地址已有的有效登录。不同电脑的本地数据库同样互不共享。

## 首次准备

安装 Git、Python 3.11+、Android Studio、JDK 17 和 Android SDK 35。Android Studio → Settings → Build Tools → Gradle 中选择 JDK 17；SDK Manager 安装 SDK Platform 35、所需 Build Tools 和 Platform Tools。准备安卓真机与可传数据的 USB 线；Rise SDK 使用 ARM 真机。手机系统版本和编译 SDK 分别记录，本机当前连接手机为 Android 16 / API 36，编译 SDK 仍为 35。

首次获取项目：

```sh
git clone --branch main https://github.com/joey08121112-web/Aidol-17-ling-work.git
cd Aidol-17-ling-work
```

已有仓库先保存自己的改动，再拉取目标分支。每台电脑自行创建虚拟环境，不从同事电脑复制 `.venv`。

## 启动电脑上的 Android 后端

在仓库根目录执行，保持终端运行：

Windows PowerShell：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-android-backend.ps1
```

macOS / Linux：

```sh
sh ./start-android-backend.sh
```

脚本准备本机 `.venv` 和 Python 依赖；已有 `.env` 保留不覆盖，不存在时从 `.env.example` 创建模板。启动的是与服务器一致的 `create_hosted_app`，只提供 `/healthz`、`/auth/*` 和 `/v1/*`。固定使用本地开发配置，监听 `127.0.0.1:8108`，将账号与业务 SQLite 放在忽略目录 `data/android-local/`，不会复用历史单用户库或部署数据目录。默认开启本地注册。

根目录 `start.ps1`、`start.sh`、`start.bat`、`start.command` 也已统一为上述本地账号后端入口。`run.py` 同样运行账号 API，但读取 `HOST`、`PORT`、`AIDOL_DATA_DIR`，默认数据目录为 `data/hosted/`；团队 USB 开发优先使用专用启动器，保证隔离目录一致。Android Studio Run 负责构建和启动 App，Python 后端需先独立启动。

只检查环境而不启动，可用 Windows `powershell -NoProfile -ExecutionPolicy Bypass -File .\start-android-backend.ps1 -Check` 或 macOS `sh ./start-android-backend.sh --check`。只安装依赖可用 `-InstallOnly` / `--install-only`。常规同事测试保留默认 `8108` 端口，避免与 App 的本地目标地址不一致。

依赖已经安装后，若不需要再次检查安装，可直接运行：Windows `.\.venv\Scripts\python.exe -m scripts.android_backend`；macOS `.venv/bin/python -m scripts.android_backend`。两种方式仍读取同一份后端 `.env`、使用同一套本地账号目录。

后端启动后，在电脑查看 `http://127.0.0.1:8108/healthz`（或使用 `curl`）。`ok=true` 表示服务已启动；`ready=false` 表示完整 AI 语音所需配置不齐，不等同于服务启动失败。凭证配置齐全也不等于已通过供应商真实调用，仍需下一节端到端测试。

### 本地凭证与无 Key 范围

供应商配置只保存在电脑仓库根目录的 `.env`，由后端读取；可使用负责人私下提供的授权测试配置。修改后重启后端，不把内容复制到 Android 源码、Gradle 参数、APK、公开日志或群聊。

| 功能 | 本地后端配置要求 |
| --- | --- |
| 注册、登录、账号隔离、设置、保存/读取本地业务数据 | 不需要供应商 Key |
| 文字流程、规则回复/提取、手动记忆与已有记录查询 | 无 Key 可检查流程，但规则结果不能当作真实模型效果 |
| 真实语音转写 | 有效 `SPEECH_VOLC_*` 凭证、资源与 ASR 配置 |
| AI 文本回复、模型记忆提取与纪要 | 有效 `TEXT_API_KEY`、`TEXT_BASE_URL`、`TEXT_MODEL` |
| 语音合成与实际听音 | 有效 `TTS_VOLC_*` 凭证、资源与音色 |
| 真实地图搜索、路线和天气 | 有效 `AMAP_API_KEY` 与相应权限 |

没有 ASR / TTS 凭证时，语音接口会提示服务不可用，不生成假的识别或播放成功。真实供应商测试会使用配置账号的额度；首次验收用合成会议内容和公共地标。无需连接共享测试服务器，并不表示完整 AI 功能能离线运行。

## Android Studio 点 Run

1. Open 选择仓库内的 `android-app/`，等待 Gradle Sync 完成。选择 `app` 与 `debug` 变体。
2. 手机开启 USB 调试并授权本电脑，执行 `adb devices -l`；多手机使用 `adb -s <serial>`，在 Android Studio 选择同一真机。
3. 将 Rise 与手机配对；本地测试先启动后端并建立 `reverse tcp:8108 tcp:8108`。电脑无需接耳机。
4. 点 Run 安装 debug。后端选“本地电脑服务”或“远端服务器”，使用目标服务的独立账号；保存注册恢复码，先确认文字请求。
5. 音频来源固定为 Rise SDK，允许所需麦克风/附近设备权限。Rise 在设备详情连接，显示就绪后再主动开始短录音。
6. 结束并发送后检查转写、回复、实际目标输出和人耳听音。用近距离发声或耳机静音对照核验真正的输入；仅有转写不能证明声音来自耳机。记录停止尾句、取消、断连和锁屏结果。

## 真机与发布 APK

开发者可以在手机开启开发者选项和 USB 调试，在 Android Studio 选择手机后 Run。将耳机连接到手机；测试本机后端时选择“本地电脑服务”并建立 USB reverse，测试部署服务时选择“远端服务器”。音频固定为“Rise SDK 麦克风”，再使用所选后端的账号验证流程。切换后端不会替代输入来源选择。

不参与开发的同事直接安装负责人发布的 `aidol-android.apk`；无需 Python、Android Studio 或供应商 Key。release 默认本地电脑服务，可切换远端 HTTPS，音频固定 Rise SDK，完整安装步骤见 [Android 使用与交接](android-handoff.md)。debug 名称“Aidol · 开发”，包名 `com.aidol.app.debug`；release 包名 `com.aidol.app`，两者可并存，设备上的登录会话与 App 设置独立。

release 保持证书和主机名验证。本地 HTTP 在所有构建中仅对精确地址 `127.0.0.1:8108` 开放，不为共享服务关闭 TLS 校验。旧的已分发 APK 不会因为拉取新源码自动更新；以实际安装的构建与验收记录为准。

## 验收清单

每次记录日期、代码提交/构建版本、Windows/macOS 主机系统、手机型号/Android 版本、耳机型号、后端目标、结果与错误提示。记录不得包含密码、Token、恢复码、Key 或他人私人录音。

### 真机与本地电脑服务

- [ ] 后端 `healthz` 可达，确认本地地址和 `ready` 状态；注册、退出、重新登录正常。
- [ ] 两个账号的历史、记忆和会议数据隔离；本地数据在后端重启后仍保留。
- [ ] 手机 USB 已授权，reverse 正确；耳机连接手机，用设备静音或近距离对照确认实际耳机拾音。
- [ ] App 诊断显示输入设备、录音电平变化、请求阶段、输出状态；没有 ASR / TTS 配置时明确失败。
- [ ] 生活模式短句：结束录音后出现转写、AI 回复，实际从耳机听到回复；把“服务返回音频”和“人耳听见”分别记录。
- [ ] 查看历史，新增/编辑/收藏/搜索记忆；工作模式分段记录合成会议，生成纪要并核对来源，完成待办后重新登录仍保留。
- [ ] 工作模式不主动播放；手动结束提交、取消停止、连续轮次、生活定时的行为符合界面提示。
- [ ] 录音时停止、等待回复时停止、播放时停止，随后重新开始均正常，无残留采音或旧回复。
- [ ] 停止本地后端或模拟网络失败，观察可理解错误并确认自动轮次停止；恢复后端后手动重新开始，先查看历史避免重复发送。
- [ ] App 离开前台、切页、锁屏后停止采音与自动调度；恢复前台不擅自开麦。
- [ ] 在本地与共享后端切换，地址、显示数据和对应登录会话一致；账号资料不混用。

### 安卓真机与蓝牙耳机

- [ ] 真实输入、输出路由都指向耳机；未接耳机、权限拒绝时明确提示，不悄悄改录手机麦克风。
- [ ] 在录音与播放中分别断开耳机，确认停止；重新连接后，由用户手动重新开始，验证输入与输出恢复。
- [ ] 蓝牙已连接但通话音频/媒体音频关闭，分别记录错误与恢复；多设备连接时核对实际路由。
- [ ] 来电、接听/挂断、其他应用占用麦克风/音频焦点，验证中断处理、资源释放及手动恢复，不错误宣称自动续录。
- [ ] 当前前台连续轮次在锁屏、切后台或离开页面后停止；重新打开后需主动开启。未来若增加后台持续录音，再单独验收前台服务、系统提示、电池限制和锁屏稳定性，本版不具备该能力。
- [ ] 共享服务的注册、对话、历史、记忆、会议流程通过；记录实际可听音质与延迟。

编译/单元测试、mock、供应商检查、完整链路和物理耳机测试分别填写 [验收记录](acceptance.md)。Windows 已有一次 USB 本地 SDK 6.7 短语音和用户听音确认，记忆来源、远端 SDK 组合、macOS 与异常场景仍需独立验证。历史模拟器源码保留用于参考，但入口已停用，历史运行记录不构成当前产品支持。

按项目清理要求，仓库不启用 GitHub CI workflow；后端离线测试、Python 编译、启动器配置和官网 `website/app.js` 语法检查在本地执行。Android 所需固定官方 AAR 包含在 Git 中的 `vendor/rise/6.7/demo_6_7.zip`；导入 SDK 后，使用 JDK 17 / SDK 35 执行 `testDebugUnitTest lintDebug assembleDebug`。软件检查通过不代表真机验收通过。

## 常见排查

| 现象 | 检查 |
| --- | --- |
| 手机未显示在 ADB 中 | 检查数据线、USB 调试及 RSA 授权；Windows 必要时安装官方驱动；多手机明确指定 serial |
| 本地登录连接失败 | 后端终端是否运行、电脑 `127.0.0.1:8108/healthz` 是否可达；App 应选“本地电脑服务”，核对 `127.0.0.1:8108` 与 USB reverse；不要使用其他端口或已停用的 `10.0.2.2` |
| Windows 已连手机但 `get-devpath=unknown` | 使用更新后的 USB 助手；必须由目标 `usb:` 字段或 `adb -d get-serialno` 精确匹配证明 USB，不能直接放行 unknown |
| 共享账号在本地无法登录 | 这是不同后端，先在本地单独注册；切回共享后使用共享账号 |
| 电平不动或没有转写 | 检查耳机与手机连接、App 麦克风/附近设备权限、Rise 就绪状态与实际PCM，再检查 ASR 配置/错误 |
| 有转写和回复但无声 | 先确认生活模式，再检查 TTS 错误、手机媒体音量、耳机媒体音频、App 目标输出诊断；有 TTS 字节不证明实际听见 |
| 升级后旧本地账号未自动登录 | 旧模拟器地址凭证不会迁入 USB 源；确认目标为本地电脑服务，再使用本地账号重新登录 |
| 改了 `.env` 仍失败 | 重启本地后端，按诊断检查供应商权限、网络和额度；不要在 App 中填写 Key |
