# 同事交接与环境复制

当前项目包含 Android App、账号后端和静态官网。代码通过 Git 共享，账号与数据库按电脑和服务器独立。真机结果见 [验收记录](acceptance.md)，完整 USB 步骤见 [本地开发](android-local-testing.md)。

2026-10-18 Mac Studio 接续：双人中文实时转写与在线说话人区分的选型、Mac 环境准备、当前 live 协议差距及逐项实施清单见 [Mac 双人语音部署方案](macos-two-speaker-deployment.md)。本次共享方案文档，尚未部署模型或改变运行配置；另一台电脑先拉取 `main`，按文中第5节验证环境，再继续第6节接入，不复制本机账号库、录音、凭证或虚拟环境。

本轮追加的触控、佩戴、重连和电量联动操作见 [Rise 联动](rise-controls.md)。

App 的固定操作语音已改为本地无语音耳标；三个模式各有固定声音，其他操作共用确认音。WAV是产品源资产，按Python脚本可重建，无需语音引擎或网络，见[操作提示音](android-audio-prompts.md)。

## 需要发给同事的文件

1. Git 仓库地址及本轮提交号，或 `artifacts/aidol-m0-m1-handoff.zip` 源码快照。Git 不含生成 APK。
2. 厂商资料已随 Git 存放在 `vendor/rise/6.7/`：官方 `耳机SDK Android 文档.docx` 和包含原版 AAR 的 `demo_6_7.zip`。ZIP 已移除示例签名私钥和密码，详见该目录 README；新电脑无需另行索取。
3. 如需共用供应商配置，单独私密发送 `artifacts/aidol-team-env.private.zip`。它包含真实后端凭证，ZIP 本身未加密，不能放入 Git、公开群、Issue、官网或普通源码交接包。同事使用会消耗同一供应商账号额度；也可以让同事使用自己的凭证。

私密包从当前 `.env` 按字段白名单生成，只保留文本模型、ASR、TTS、地图配置及本地默认值。它不复制 SSH 密码、Android 签名密钥、手机账号密码、数据库、旧 Demo Key 或本机代理地址。创建命令不输出任何凭证值：

```powershell
.\.venv\Scripts\python.exe scripts/package_team_env.py
```

## 同事如何复制 .env

将私密 ZIP 中的 `.env` 解压到克隆后的仓库根目录，与 `requirements.txt`、`start-android-backend.ps1` 同级。已有 `.env` 时先备份，不要覆盖同事自己的配置。没有共享凭证时按以下命令创建空模板，再自行填写供应商配置：

```powershell
# Windows，仓库根目录
Copy-Item .env.example .env
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-android-backend.ps1
```

```sh
# macOS，仓库根目录
cp .env.example .env
sh ./start-android-backend.sh
```

若已从私密包取得 `.env`，跳过复制模板这一步，直接启动后端。不要复制 `.venv`；每台电脑由启动器创建自己的 Python 环境。代理如有需要由同事按本机网络配置填写。

## 安卓真机与 SDK

安装 JDK 17、Android Studio、Android SDK 35 和 Platform Tools，手机开启 USB 调试并授权电脑。耳机配对手机。

```powershell
# Windows
powershell -File scripts/import-rise-sdk.ps1 -DemoZip vendor/rise/6.7/demo_6_7.zip
.\connect-android-usb.ps1 -Serial '<手机序列号>' -Adb '<SDK目录>\platform-tools\adb.exe'
```

```sh
# macOS
bash scripts/import-rise-sdk.sh vendor/rise/6.7/demo_6_7.zip
chmod +x android-app/gradlew
sh ./connect-android-usb.sh --serial '<手机序列号>' --adb "$ANDROID_HOME/platform-tools/adb"
```

Android Studio 打开 `android-app/`，选 USB 真机运行 debug。App 后端选“本地电脑服务（USB）”，音频选“Rise SDK 麦克风”；先登录该电脑的账号，再连接 Rise。远端服务器和本地电脑的账号独立，但每处登录后在本机加密保存；只切音频不会退出或清空当前页面。

保持后端终端运行。拔插 USB 后重新建立转发。Windows/macOS 换电脑时各自配置 `.env`、导入同一 6.7 SDK、创建自己的测试账号；不要把旧电脑的数据库和手机 Token 当作共享配置。

## 验证与交付边界

```sh
python -m pytest tests -q
python -m compileall -q app
cd android-app
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows 使用 `gradlew.bat`。按项目清理要求，仓库不启用 GitHub CI workflow；上述验证需在各自环境执行。官方 AAR 需要本地导入，Android 构建及实物验收单独记录。同签名覆盖安装可保留手机登录；用另一台电脑的不同 debug 签名可能无法直接覆盖，不要默认卸载清空数据。

Windows USB + Rise SDK 收音、转写、实际耳机听音已有实测。macOS 实机、其他手机/固件、异常及新联动功能以验收文档逐项结果为准，不能由编译成功推定。
