# Aidol Android

保留同一个原生 Java 安卓项目，Android 8.0+。Windows/macOS 开发者通过 USB 安卓真机安装和调试同一套 App，耳机连接手机；账号、对话、记忆、会议和通勤界面共用代码，不需要浏览器或另做跨平台客户端。当前不提供模拟器产品入口，旧采集源码保留作参考。

| 测试方式 | 耳机连接 | 音频模式 | 后端 |
| --- | --- | --- | --- |
| Windows/macOS 通过 USB 真机 Run | 手机蓝牙 | debug系统蓝牙兼容或Rise SDK麦克风 | 本地电脑服务，固定 `http://127.0.0.1:8108`，需 adb reverse |
| 安卓真机，测试部署环境 | 手机蓝牙 | debug同两种音频来源 | 远端服务器，内置HTTPS |
| 安卓真机，安装发布 APK | 手机蓝牙 | release 真机蓝牙模式 | 内置 `https://123.207.219.174:7776` |

本地后端与共享服务器使用同样的 `/auth/*`、`/v1/*` API，账号和数据分别保存在各自后端，不互相同步。电脑开发无需连接共享服务器，但真实语音识别、AI 回复和语音合成仍需要本地后端配置有效的供应商凭证与网络；凭证永不打包进 APK。

界面分为对话、记忆、工作、通勤、我的。采用暖白与深松绿主题、原生绘制的动态球体、分层卡片和按需展开设置；保留明确的连接/录音/处理/播放状态。完整功能、视觉检查步骤和同事测试说明见 [交接说明](../docs/android-handoff.md)，服务器运维见 [部署文档](../docs/android-deployment.md)。

## 同事开发上手

要求 Python 3.11+、Android Studio、JDK 17、Android SDK 35 和安卓真机，Rise SDK 使用 ARM 设备。先取得官方 Word 与 demo_6_7.zip，Windows 运行 `powershell -File scripts/import-rise-sdk.ps1 -DemoZip <ZIP路径>`，macOS 运行 `bash scripts/import-rise-sdk.sh <ZIP路径>`（均从仓库根目录）。构建会核验固定 6.7 AAR SHA-256，缺失或不匹配会失败。

USB 真机流程：启动电脑后端 → 手机 USB 授权 → `adb -s <serial> reverse tcp:8108 tcp:8108` → Run debug → 选择“本地电脑服务” → 注册/文字 → 选择 Rise SDK 并连接耳机。后端只有“本地电脑服务/远端服务器”，音频只有“系统蓝牙兼容/Rise SDK 麦克风”。电脑无需与手机同 Wi-Fi，耳机配对手机。`connect-android-usb.ps1/.sh` 提供指定手机、USB 身份、转发和电脑健康检查；Windows get-devpath=unknown 时必须由目标 usb: 字段或 `adb -d get-serialno` 精确匹配核验，不能直接放行。

1. 在仓库根目录启动本地 Android 后端：Windows 执行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\start-android-backend.ps1`；macOS 执行 `sh ./start-android-backend.sh`。保持终端运行。
2. Android Studio 打开本目录 `android-app/`，Gradle JDK 选择 JDK 17，等待 Sync 完成，使用 `debug` 构建。
3. 手机与耳机配对，开启 USB 调试并授权本电脑；执行 `adb devices -l`，多手机指定同一 serial 建立 8108 reverse。
4. Android Studio 选择已授权真机并 Run。在“开发测试设置”选“本地电脑服务”或“远端服务器”，使用对应服务账号；独立选择系统蓝牙兼容或 Rise SDK 麦克风。
5. 先测试注册登录和文字，再连接目标耳机并开始短语音，核对结束尾帧、转写、实际听音、记忆和工作记录；断连、来电、取消与锁屏分别验收。

完整配置、排错和验收步骤见 [Windows/macOS USB 真机测试](../docs/android-local-testing.md)。Android Studio Run 负责 App 构建和启动，不会自动启动 Python 后端。

debug 名称为“Aidol · 开发”，包名 `com.aidol.app.debug`，可与发布版并存。登录页和“我的”均可打开两项后端与两项音频设置。旧模拟器本地偏好迁移为 USB，旧模拟器输入迁移为系统蓝牙，已保存的 Rise 选择保留。登录按精确源隔离，旧源凭证不复制至 USB；`10.0.2.2:8108` 已被拒绝。

## 构建与发布

要求 JDK17、Android SDK35。设置 `JAVA_HOME` 和 `ANDROID_HOME`（或使用不入库的 `local.properties`）。

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows 使用 `gradlew.bat`。本地 debug 签名 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。发布文件 `aidol-android.apk` 包名为 `com.aidol.app`，使用独立 release 签名，固定共享 HTTPS 服务器与真实蓝牙检查，不提供模拟器绕过或本地明文入口。手机测试者安装后注册账号即可，无需填写供应商 Key。

发布签名使用已有密钥，勿每次创建新密钥；保管好 keystore 和密码，丢失后将不能原地更新现有安装。Gradle 从 `AIDOL_KEYSTORE`、`AIDOL_KEYSTORE_PASSWORD` 读取发布凭证，alias 为 `aidol-release`，永不写入源码。

```powershell
../scripts/build-android.ps1 -JavaHome <JDK17目录> -AndroidHome <SDK目录> -KeyStore <私密keystore路径> -PasswordFile <私密密码文件>
```

首发密钥在负责人开发机忽略目录 `artifacts/android-signing/`，需另做私密备份；Git 不包含密钥、密码、APK、构建缓存或真实配置。首发签名证书 SHA-256：`e71b49a9c64613905a301334bb6c207cefc72f7aff91f20eb722c2062eab0da2`。

共享服务器改址时同时更新 `app/build.gradle`、`res/xml/network_security_config.xml` 和服务器证书；不能只改 URL 或绕过证书校验。`res/raw/aidol_server_ca.pem` 是可公开的验证证书，不是私钥。debug 的本地 HTTP 例外仅用于指定的本机开发地址。

## 音频生命周期

- 当前输入仅系统蓝牙与 Rise SDK；历史 EMULATOR 设置会迁移，UI 不可选，会话也拒绝启动模拟器来源。
- 真机蓝牙模式核验真实蓝牙麦克风后才接受 PCM16/16kHz/mono；不静默改录手机麦克风。
- Rise SDK输入通过注入的VendorCaptureSource，通道2采音、idle确认并排空；回复实际目标地址核验，失败不回落手机。正常结束与取消分开，提前3秒预留停止预算，含收尾最高60秒；generation隔离旧回调。
- `HostedSession` 管理有界录音、上传、识别/回答和 TTS；纯文字和重播复用同一流程。取消会清理麦克风、网络请求、播放器和延时任务。
- `ReplyPlayer` 使用流式 AudioTrack、音频焦点及路由检查，再输出 PCM16/24kHz/mono；真机模式断开耳机后停止播放。debug 对话页的“开发诊断”显示输入/输出、录音音量、请求状态与错误；这些状态不能代替人耳听音。
- 手动模式按结束提交。连续模式在停顿后提交、播放完成再录下一轮；这不是同时双工收听。工作模式每轮最多60秒，处理期间有采音间隙。定时模式只在生活模式且当前页面可见时运行。
- 前台自动聆听必须主动开启；停止、返回、切页、锁屏、离开 App 或失败后关闭。录音/等待保持屏幕点亮，注意测试用电。
- 手动短语音可另行开启本次锁屏耳机会话（前台通知可停止、最多一小时）；需要宿主仍存活，来电/Rise 断连/关闭任务会撤销，不自动恢复，也不授权后台连续或定时开麦。真实锁屏和功耗尚待设备验收。
- 新版定向语音可保存、检索、更正和遗忘唯一记忆，工作私人提问可以回答；手机可核对引用、查看纪要覆盖、只读查询失败请求结果。需使用同期后端，详见 [API](../docs/api.md)。
- `AuthVault` 用 Android Keystore 保存加密会话 Token；不保存账号密码。无后台录音服务；Rise仅显示实际上报电量/佩戴，不宣称按键、ANC或唤醒能力。

## 验证边界

编译、单元测试、Lint、供应商链路与物理耳机结果分别记录在 [验收记录](../docs/acceptance.md)。本机 Windows 已连接并安装本轮 debug 到 23113RKC6C（Android 16 / API 36），USB 身份、8108 转发及电脑健康检查通过；Rise VI-A10 的 READY、真实电量/佩戴上报已观察。手机 USB 请求、短语音、实际听音与异常场景仍待验，macOS 仍待验。握手与编译不能替代语音闭环。
