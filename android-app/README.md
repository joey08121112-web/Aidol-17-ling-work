# Aidol Android

原生 Java 安卓应用，包名 `com.aidol.app`，Android 8.0+。发布文件 `aidol-android.apk` 使用独立 release 签名，内置 `https://123.207.219.174:7776`。安装后注册自己的唯一账号即可，无需填写任何供应商 Key。

界面分为对话、记忆、工作、通勤、我的。深色青绿主题、自绘动态声波、按需权限与明确的连接/录音/处理/播放状态。完整功能和同事测试步骤见 [交接说明](../docs/android-handoff.md)，服务器运维见 [部署文档](../docs/android-deployment.md)。

## 构建

要求 JDK17、Android SDK35。设置 `JAVA_HOME` 和 `ANDROID_HOME`（或使用不入库的 `local.properties`）。

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows 也可使用 `gradlew.bat`。本地 debug 签名 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，不能覆盖不同签名的已安装 release 应用。

发布签名使用已有密钥，勿每次创建新密钥；保管好 keystore 和密码，丢失后将不能原地更新现有安装。Gradle 从 `AIDOL_KEYSTORE`、`AIDOL_KEYSTORE_PASSWORD` 读取发布凭证，alias 为 `aidol-release`，永不写入源码。

```powershell
../scripts/build-android.ps1 -JavaHome <JDK17目录> -AndroidHome <SDK目录> -KeyStore <私密keystore路径> -PasswordFile <私密密码文件>
```

首发密钥在负责人开发机忽略目录 `artifacts/android-signing/`，需另做私密备份；Git 不包含密钥、密码、APK、构建缓存或真实配置。首发签名证书 SHA-256：`e71b49a9c64613905a301334bb6c207cefc72f7aff91f20eb722c2062eab0da2`。

服务器改址时同时更新 `app/build.gradle`、`res/xml/network_security_config.xml` 和服务器证书；不能只改 URL 或绕过证书校验。`res/raw/aidol_server_ca.pem` 是可公开的验证证书，不是私钥。

## 音频生命周期

- `BluetoothCapture` 核验真实蓝牙麦克风后才接受 PCM16/16kHz/mono；不静默改录手机麦克风。
- `HostedSession` 管理有界录音、上传、识别/回答和 TTS；纯文字和重播复用同一流程。取消会清理麦克风、网络请求、播放器和延时任务。
- `ReplyPlayer` 使用流式 AudioTrack、音频焦点及静音路由预检，再输出 PCM16/24kHz/mono；断开耳机后停止播放。
- 手动模式按结束提交。连续模式在停顿后提交、播放完成再录下一轮；这不是同时双工收听。工作模式每轮最多60秒，处理期间有采音间隙。定时模式只在生活模式且当前页面可见时运行。
- 前台自动聆听必须主动开启；停止、返回、切页、锁屏、离开 App 或失败后关闭。录音/等待保持屏幕点亮，注意测试用电。
- `AuthVault` 用 Android Keystore 保存加密会话 Token；不保存账号密码。无后台录音服务，不宣称厂商按键、ANC、电量或唤醒能力。

## 验证边界

编译、协议/WAV/音频单元测试、Lint、APK 签名、服务器真实链路分别记录在 [验收记录](../docs/acceptance.md)。开发机没有蓝牙耳机真机；两版模拟器在宿主 Windows 上启动前原生崩溃，因此尚无真实屏幕截图验收或新版可听音质结论。
