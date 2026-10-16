# Aidol 安卓耳机直连 Demo

> 多人体验请使用新的 `android-app/aidol-android.apk`，不需要填 Key。本目录继续保留为独立硬件诊断 Demo。
>
> 2026-10-17 修复：实际截图显示 ASR、文本模型和 TTS 均完成，但播放报“蓝牙播放器初始化失败”。原因是旧静态 AudioTrack 写入前处于 `STATE_NO_STATIC_DATA`，被错误拒绝。现改为流式 AudioTrack、音频焦点和静音路由预检；已重新编译并覆盖 `aidol-android-direct-debug.apk`，仍需同一手机/耳机确认实际听音。

**独立 APK，手机直接访问豆包。无需电脑后端，无需 Viaim App Key，不依赖 Viaim 官方 SDK。**

本 Demo 只验证一件事：蓝牙耳机采音 → 豆包流式 ASR → 豆包 Turbo 回答 → 豆包 TTS → 蓝牙耳机播放。按按钮完成一轮，最多录音 60 秒。它不包含主项目的记忆、地图或三模式管理。

## 安装与测试

1. 把提供的 `aidol-android-direct-debug.apk` 发到安卓手机安装。安装时可能需要允许当前文件管理器/聊天软件安装应用。
2. 在系统蓝牙里连接 Viaim，打开设备的 **通话音频** 和 **媒体音频**。暂时断开其他蓝牙耳机，关闭其他录音/通话 App。
3. 打开「Aidol 耳机联调」，填入下面的凭证。手机需能访问火山云服务。
4. 点 **开始录音**，允许麦克风和附近设备权限。等待日志出现 **实际麦克风：设备名称** 再说话。
5. 说“你好，介绍一下你自己”，点 **结束录音并回复**。
6. 应先看到识别文本，再看到模型回答，最后听到耳机播放。音量请调节手机的 **媒体音量**。

蓝牙通话麦克风通过 Android 系统接口获取。仅配对成功不代表可录音：如果实际录音设备不是蓝牙耳机，Demo 会报错停止，不会静默改用手机麦克风。此方式验证标准蓝牙音频通路，不验证充电盒独立麦克风、ANC 控制或厂商 SDK。

## 凭证怎么填

| App 输入项 | 对应项目 `.env` | 用途 |
| --- | --- | --- |
| 火山语音 AppID / App Key | `TTS_VOLC_APP_KEY` | 语音服务的应用标识，通常为数字；不是 Ark API Key |
| 火山语音 Access Key | `TTS_VOLC_ACCESS_KEY` | 语音合成；不填独立 ASR Key 时也用于识别 |
| 火山方舟 Ark API Key | `TEXT_API_KEY` | 文本大模型 |
| 文本模型名称 | `TEXT_MODEL` | 默认 `doubao-seed-2-1-turbo-260628`，显式关闭思考 |
| 可选 ASR API Key | `SPEECH_VOLC_API_KEY` | ASR 使用独立 API Key 时填写；留空则使用前两项应用凭证 |

如果 ASR 只有另一套 AppID/Access Key，且与 TTS 不属于同一应用，当前极简 UI 不支持分别填写两套应用凭证：请使用该 ASR 应用的 API Key，或为同一应用开通 ASR 和 TTS。

以上凭证直接用于官方 HTTPS/WSS 端点，不经过本项目服务器。没有内置密钥，也不落盘保存密钥或录音；退出/重建页面后重新填写。每轮只发送本轮录音与文字，没有跨轮记忆。调用消耗你填写的云账号配额。

## 固定服务配置

- ASR：`wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`，资源 `volc.seedasr.sauc.duration`；上行 16 kHz / 单声道 / PCM16 little-endian，gzip 二进制流，停止发送 final 包并等待最终识别。
- 文本：`https://ark.cn-beijing.volces.com/api/v3/chat/completions`，`thinking.type=disabled`，短回答。
- TTS：`wss://openspeech.bytedance.com/api/v3/tts/bidirection`，资源 `seed-tts-1.0`，音色 `zh_female_jitangmeimei_mars_bigtts`；24 kHz / 单声道 / PCM16。
- 使用同仓库 `app/providers/volc_speech.py` 已验证的 v3 协议；Android 自己管理云连接。

## 常见问题

- **没有蓝牙通话设备 / SCO 超时**：确认设备「通话音频」开启；关闭其他录音应用；断开并重新连接耳机。标准蓝牙路线是否可用仍以实物为准。
- **HTTP 401 / 403**：核对 Key 类型、所属应用和资源权限。普通 Ark Key 不等于语音服务凭证。
- **识别成功但没有回答**：检查 `TEXT_API_KEY` 和模型权限，确认模型名称可用于你的账号。
- **回答文字正常，但 TTS 报错**：检查语音 AppID/Access Key 和 `seed-tts-1.0` 权限；此固定音色需要对应 1.0 资源。
- **实际播放不是耳机 / 没声音**：打开媒体音频，调高媒体音量；确保另一台设备没有抢占耳机。
- **离开 App 后停止**：有意设计为前台单轮测试，没有后台服务；屏幕保持点亮，切到其他页面会取消本轮。

## 源码构建

要求：JDK 17、Android SDK 35，Android 8.0+ 真机（推荐 Android 11+）。Android Studio 打开本目录后同步即可；它与根项目的 Python 环境独立。

```powershell
# Windows，当前目录为 android-demo
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

```sh
# macOS / Linux
sh ./gradlew assembleDebug testDebugUnitTest lintDebug
```

配置 `ANDROID_HOME` 或在本目录的 `local.properties` 填写本机 `sdk.dir`。不要把本机路径文件提交。

构建结果：`app/build/outputs/apk/debug/app-debug.apk`。这是本地调试签名 APK，供安装联调，不是商店发行包。若其他机器重新编译导致签名不同，需要先卸载旧 Demo。

## 代码分层

- `MainActivity.java`：极简表单、权限、状态显示。
- `BluetoothCapture.java`：Android 蓝牙路由、PCM 采音，核对实际麦克风。
- `DirectSession.java`：一轮 ASR → 文本 → TTS。
- `SpeechSocket.java` / `SpeechProtocol.java`：云端 WebSocket 与二进制帧。
- `ReplyPlayer.java`：采集结束后通过蓝牙媒体音频播放。
- `AudioFrames.java`：PCM16 编码、分片和电平。

## 验证边界

2026-10-17：已完成 Android Debug 构建、5 项协议/PCM 单元测试、Lint（0 错误）及 APK 签名校验。凭证配置已核对与本机 `.env` 的资源和音色一致，源码没有写入实际密钥。

尚无开发机连接的 Viaim 真机，不能把编译通过当成 VI-A10 实物验收。队友测试时请记录：手机型号/Android 版本、实际麦克风名称、识别文本、是否听到耳机回复、报错阶段。请勿把密钥随截图发群。
