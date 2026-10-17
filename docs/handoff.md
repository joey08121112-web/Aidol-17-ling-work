# 团队交接

项目只维护 `android-app/`、`app/` 和 `website/` 三个产品模块。Android 是唯一业务客户端，Python 后端负责账号、模型与数据，官网负责介绍和下载。旧浏览器客户端与独立供应商 Demo 已移除；测试、脚本、部署和文档继续随对应模块维护。

## 开发入口

1. 拉取 `main` 后先检查 `git status`，保留已有未提交和未跟踪工作。按模块认领文件；功能分支可用 `codex/` 前缀，审查后集成回 `main`。
2. 每位开发者独立创建 Python 环境和 `.env`，运行根目录 `start-android-backend.ps1`（Windows）或 `sh ./start-android-backend.sh`（macOS）。后端固定 `127.0.0.1:8108`，本地账号库在 `data/android-local/`。
3. 耳机配对安卓真机，USB 授权后建立 `adb -s <serial> reverse tcp:8108 tcp:8108`。Android Studio 打开 `android-app/` 并 Run；debug 选择“本地电脑服务”或“远端服务器”，再独立选择“系统蓝牙兼容”或“Rise SDK 麦克风”。
4. 先核对健康、登录和文字，再检查 SDK 就绪、采集和输出。完整 Windows/macOS 步骤见 [本地测试](android-local-testing.md)，本轮实施契约见 [M0/M1 交接](android-m0-m1-handoff.md)。

本地服务和远端服务的账号、历史、记忆与 Token 独立；切换后端不复制登录凭据。旧模拟器偏好迁移为 USB / 系统蓝牙，`10.0.2.2:8108` 已拒绝。release 仍使用系统蓝牙与远端 HTTPS，debug SDK 功能不表示已发布到公网 APK。

## 模块分工

| 范围 | 主要文件 | 交付要求 |
| --- | --- | --- |
| Android UI / 会话 | `android-app/` 的 MainActivity、HostedSession、CaptureTurn、ReplyPlayer | 用户操作、停止/取消、前台生命周期与真实输出路由 |
| Rise / 系统蓝牙 | Android `hardware/`、CaptureSource、BluetoothCapture | 单一 SDK 所有者、明确来源、尾帧排空和有界缓冲 |
| 后端账号 / 路由 | `app/main.py`、`app/services/accounts.py`、`mobile.py` | 鉴权、每账号隔离、幂等、限流、诊断脱敏 |
| 后端业务 / 数据 | `app/services/`、`app/storage.py`、`app/domain.py` | 场景、记忆、纪要、引用与事务一致性 |
| 供应商 | `app/providers/` | ASR/TTS 协议、超时与失败语义，不含 UI |
| 官网 | `website/`、`deploy/website-*` | 公开说明、下载和独立静态发布 |
| 发布与验收 | `deploy/`、`scripts/`、`tests/`、`docs/` | 可回滚发布、临时库测试、分层验收证据 |

多人共享源码、契约、脱敏用例和验收记录，各自持有本地数据库与获授权的配置。密钥、Token、恢复码、私密录音、数据库、构建缓存和签名私钥不进 Git。产品运行时使用一套按账号隔离的编排服务，开发代理数量不改变产品架构。

## 换机与硬件资料

当前 Rise 交接必须包含官方 `耳机SDK Android 文档.docx`、`demo_6_7.zip` 和当前源码快照。官方 ZIP 是导入 SDK 的来源，不能与已移除的项目 Demo 混淆。固定 AAR 校验与导入步骤见 [M0/M1 交接](android-m0-m1-handoff.md)。只发旧 HEAD 无法复现当前未提交实现。

`python scripts/package_android_handoff.py --official-doc <官方.docx路径> --demo-zip <demo_6_7.zip路径>` 可重新生成源码交接包。以前生成的 ZIP 和 APK 是当时快照，收窄目录后需要重新打包才能反映当前源码；不把本机数据或凭证装入交接包。

## 验收与发布

本地回归运行 `python -m pytest tests -q`、`python -m compileall -q app`；Android 使用 JDK 17 / SDK 35 运行 `testDebugUnitTest lintDebug assembleDebug`。官网单独检查桌面、窄屏、交互、资源和 APK 下载。真实供应商脚本可能消耗配额，仅由开发者按需手动执行。

按项目清理要求，仓库不启用 GitHub CI workflow。每位开发者在本地执行后端离线测试、编译、启动器配置检查及官网 `node --check website/app.js`。官方 SDK AAR 不进 Git，取得官方 Word 与 `demo_6_7.zip` 并导入后完成 Android 检查；软件检查不能代替硬件验收。

Windows 已观察到真实手机连接和 Rise READY、电量/佩戴上报；手机断网健康检查及移除/恢复 USB reverse 的对照通过。Windows USB 本地服务与 SDK 6.7 的一次真机短语音收到 `mic_bytes=107520`，ASR、业务、TTS 和目标 Rise 输出核验成功，用户确认转写正确并从耳机听到回复。记忆来源、手机断网注册/文字请求、远端 SDK 组合、macOS、麦克风来源对照、尾句和异常场景仍待验。追加的触控、佩戴与重连联动见 [Rise 联动](rise-controls.md)。当前会话前台结束后停止，没有实时双工、锁屏服务、ANC 或盒子接入。结果分别记入 [验收记录](acceptance.md)，不得把单轮通过扩展为全部硬件场景通过。

接口修改同步 [API](api.md)；账号与共享服务发布按 [后端部署](android-deployment.md)，官网发布按 [官网部署](website-deployment.md)。故障记录包含源码/APK版本、主机和手机系统、耳机/固件、后端源、输入来源、步骤和脱敏 session/request ID；输入字节、SDK 就绪和人耳听见分别陈述。
