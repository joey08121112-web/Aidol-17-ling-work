# 聆 · Aidol

语音增量：新版 Android 已接入 Qwen Omni 原生音频输入/音频输出；后端配置 `QWEN_OMNI_API_KEY` 后，主动提问和连续通话自动选用。密钥只保留在后端。配置、工具查询、记忆回执和验收边界见 [千问实时语音](docs/qwen-realtime.md)。

耳机里的随身助手：语音陪伴、工作记录、会议纪要、个人记忆和通勤提醒。项目只维护三个产品模块，放在同一个仓库，通过 `main` 共享源码。

| 模块 | 职责 |
| --- | --- |
| `android-app/` | 唯一用户客户端：账号、耳机连接、录音播放、对话、记忆、工作和通勤 |
| `app/` | Python / FastAPI 后端：鉴权、按账号隔离的 SQLite、业务编排、ASR、文本模型、TTS 和地图 |
| `website/` | 静态官网：产品介绍、使用说明和 APK 下载入口 |

`deploy/`、`scripts/`、`tests/`、`docs/` 是这三个模块的部署、开发与验收支撑。旧浏览器应用、直连供应商 Android Demo 和两套供应商参考 Demo 已从当前源码移除；官网与旧浏览器应用是不同模块。

## 手机体验

安装 [aidol-android.apk](http://123.207.219.174/downloads/aidol-android.apk)，注册自己的账号，保存恢复码，连接蓝牙耳机。手机用户不需要电脑后端、API Key、邮箱或短信验证码。业务请求使用 APK 内置的 `https://123.207.219.174:7776`，供应商凭证只保存在后端，各账号的数据独立。

现有发布包为 `com.aidol.app` 0.2.0，使用系统蓝牙和远端 HTTPS。当前源码已加入 debug 可选的 Rise SDK 6.7 接入，源码更新不代表公网 APK 已更新。安装、账号恢复和功能范围见 [Android 使用说明](docs/android-handoff.md)；公网可用性及硬件实测结果以 [验收记录](docs/acceptance.md) 为准。

当前源码每轮语音最多60秒，支持边录边传、识别中间文字和流式TTS；工作环境记录保持安静，主动提问可以答复。连续轮次和生活定时录音需要用户主动开启并保持前台，停止或离开前台后不再接续。实时双工、锁屏持续采音、ANC、按键、闪录和盒子能力属于后续范围。

## 本地开发：安卓真机 + 电脑后端

要求 Python 3.11+、JDK 17、Android SDK 35，以及允许 USB 调试的安卓真机。耳机配对手机，Windows/macOS 电脑运行同一套多账号后端。

| 系统 | 在仓库根目录启动后端 |
| --- | --- |
| Windows PowerShell | `powershell -NoProfile -ExecutionPolicy Bypass -File .\start-android-backend.ps1` |
| macOS / Linux Shell | `sh ./start-android-backend.sh` |

后端监听 `127.0.0.1:8108`，数据独立保存在 `data/android-local/`。根目录 `start.ps1`、`start.sh`、`start.bat`、`start.command` 也指向此账号后端。`python run.py` 也运行账号 API，但读取 `HOST`、`PORT`、`AIDOL_DATA_DIR`，默认数据目录为 `data/hosted/`；USB 开发优先使用上述专用启动器。健康检查为 `http://127.0.0.1:8108/healthz`。

USB 授权后建立 `adb -s <serial> reverse tcp:8108 tcp:8108`，Android Studio 打开 `android-app/` 并在手机 Run。debug 的“连接设置”分别选择音频来源和后端：唯一音频 Rise SDK 麦克风，本地电脑服务 / 远端服务器。本地固定 `http://127.0.0.1:8108`，不提供模拟器入口；本地与远端账号、会话和数据互不复制。

完整步骤和无 Key 测试范围见 [USB 真机与本地后端测试](docs/android-local-testing.md)。Rise 官方 Word、Demo 源码和固定版本 AAR 已随 Git 存放在 [vendor/rise/6.7](vendor/rise/6.7/README.md)，新电脑先从该目录的 ZIP 导入 SDK；Demo 已去除示例签名私钥和密码。这些厂商资料并不是已移除的项目 Demo。换电脑时需包含当前未提交源码，不能只发送旧 HEAD。

本机已观察到 Rise VI-A10 的 SDK READY、电量和佩戴上报；手机 USB 业务请求、短语音、真实输入来源及人耳听音仍待完整验收，macOS 组合单独待验。测试结果不能由编译成功或供应商返回音频替代。

## 后端配置与部署

本地供应商配置写入根目录 `.env`，字段模板见 `.env.example`，已有配置不覆盖。账号、记忆管理等可用 mock / 无 Key 环境验证；完整语音和地图需要后端对应服务已开通。

| 配置 | 用途 |
| --- | --- |
| `TEXT_API_KEY` / `TEXT_BASE_URL` / `TEXT_MODEL` | 文字对话、记忆提取、纪要及证据筛选 |
| `SPEECH_VOLC_*` | 账号语音接口使用 ASR 2.0 stream；flash 仅供独立探测脚本诊断 |
| `TTS_VOLC_*` | 独立语音合成；资源和音色必须匹配 |
| `AMAP_API_KEY` | 地点、地址解析、驾车/步行/公交/骑行、地铁站、实况/预报 |
| `BOCHA_API_KEY` | 公开网页搜索与来源摘要 |
| `UPSTREAM_PROXY` / `PROVIDER_PROXY` | 后端上游代理，可选 |
| `AIDOL_REGISTRATION_OPEN` / `AIDOL_MAX_USERS` / `AIDOL_DAILY_REQUESTS` | 注册开关、账号容量及业务操作配额 |

“配置齐备”不代表供应商权限已经实测。凭证、录音、数据库、签名私钥和构建产物不提交 Git。开发者各自建立本地环境和数据库，只共享源码与脱敏验收材料。

后端只提供 `/healthz`、`/auth/*`、`/v1/*`，使用 `app.main:create_hosted_app`。部署保持单进程单 worker，HTTPS 代理后的服务只监听回环地址。部署、备份和回滚见 [后端部署](docs/android-deployment.md)。本地 Docker Compose 同样运行账号后端，端口只映射 `127.0.0.1:8108`；容器数据卷与直接启动的本地目录分别保存。

官网 `website/` 独立静态发布，当前地址为 [Aidol 官网](http://123.207.219.174:6655/)，只展示公开内容并分发 APK，不处理账号密码。发布与回滚见 [官网部署](docs/website-deployment.md)。

同事 Demo 的适配内容、流式协议和拉取后的启用步骤见 [语音、地图与搜索整合](docs/colleague-integration.md)。

## 验证与交接

```sh
python -m pytest tests -q
python -m compileall -q app
# 在 android-app/，JDK 17 + Android SDK 35：
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows Android 构建使用 `gradlew.bat`。真实 `scripts/probe_*.py` 调用可能消耗 API 配额，按需使用合成语音和公共地标，仅由开发者按需手动执行。官网修改另做桌面/手机视觉、交互、资源和下载检查。

- [架构与模块边界](docs/architecture.md)
- [多账号 API 契约](docs/api.md)
- [团队交接](docs/handoff.md)
- [Rise SDK 与音频边界](docs/hardware-integration.md)
- [记忆、纪要和行动项](docs/memory-features.md)
- [后续 Android 产品规划](docs/android-rise-product-plan.md)
- [验收证据与待验项](docs/acceptance.md)
