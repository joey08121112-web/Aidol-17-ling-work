# Aidol 安卓共享测试部署

这版是「安装 APK → 注册账号 → 使用」的共享服务。API Key 只保存在服务器，用户不需要填 Key、邮箱、短信验证码或电脑后端。原 `android-demo/aidol-android-direct-debug.apk` 是直连供应商的开发 Demo，不是本次多人服务版。

## 固定地址与账号

| 项目 | 值 |
| --- | --- |
| APK 内置服务 | `https://123.207.219.174:7776` |
| 后端私有监听 | `127.0.0.1:8108` |
| 服务器 | Ubuntu 24.04，Python 3.12，nginx + systemd |
| 账号 | 3–24 位英文字母、数字、下划线；忽略大小写，全站唯一 |
| 密码 | 8–128 位；服务器只存带盐密码哈希 |
| 注册 | 无需邮箱或手机；注册成功显示恢复码，请用户保存 |
| 忘记密码 | 账号 + 恢复码重设；没有邮箱/短信找回 |
| 数据 | 每个账号独立会话、记忆、设置、会议和任务 |

IP 可以直接使用，不需要域名。测试 APK 内置仅用于该服务器 IP 的公开 CA 证书，所以用户无需手工安装证书。证书校验和主机校验必须保持启用；不要增加 `trust-all`、忽略证书异常或全局允许明文流量的代码。HTTPS 与密码哈希由程序处理，不增加用户的操作步骤。

服务器证书首发有效期至 **2027-10-16 UTC**，CA 有效期十年。到期前按下文续签即可，续签沿用同一 CA 不需要用户重装 APK。换 IP 或 CA 时需要重新签发证书并更新 APK 的内置地址/信任配置。

## 服务器目录

```text
/opt/aidol/
  current -> releases/<release>   正在运行的代码
  previous -> releases/<release>  上一版本，用于回滚
  releases/                      每次发布独立代码和 Python 虚拟环境
  downloads/                     可公开下载的 APK 与校验文件
  shared/
    .env                         供应商密钥；root:aidol，640
    data/
      hosted/accounts.sqlite3    账号和登录会话
      hosted/users/              每个账号自己的 SQLite 数据
    backups/                     发布前 SQLite 快照；仅 root 可读
    tls/
      ca.pem                     公开 CA，APK 内置此文件
      ca.key                     CA 私钥，仅服务器 root 可读
      server.pem / server.key    nginx IP 证书/私钥
```

`aidol` 是不可登录的系统账号。服务代码由 root 持有，运行进程仅可写自己的数据目录。单进程单 worker 是当前 SQLite 和内存会话实现的部署要求，不要直接增加 uvicorn worker 数。已有 `lvming.xqjun.top`、`photo.xqjun.top` 等服务的配置不需要修改。

## 首次部署

SSH 使用 `root@123.207.219.174`、端口 22。SSH 密码和供应商 Key 不写入文档或 Git。用 SSH 客户端确认服务器指纹；本次实测的 Ed25519 公钥为：

```text
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMZfCmgpP9tVLJFPqnbY5WwR4C9C1ULT1Sr2SCURtziw
```

1. 云安全组放行 TCP **7776**（App API）和 **80**（可选 APK 下载），管理端保留 SSH 22。不需要开放 8108。
   如果主机 UFW 已启用，还需 `ufw allow 7776/tcp comment 'Aidol Android API'`；不要关闭防火墙或修改默认策略。当前服务器已经执行此规则。
2. 上传当前仓库的 `app/`、`web/`、`requirements.txt`、`deploy/` 到一个临时发布目录，例如 `/root/aidol-upload/`。不要上传 `.venv`、数据库、录音、Git 历史、Android 构建缓存。
3. 在服务器执行：

```bash
cd /root/aidol-upload
bash deploy/prepare-server.sh
```

4. 将开发机 `.env` 通过 SFTP 私密上传为 `/opt/aidol/shared/.env`。不要贴到终端聊天记录或 `cat` 输出日志。检查下面这些配置；保留原文件中的供应商真实值：

| 服务端环境变量 | 用途 |
| --- | --- |
| `TEXT_API_KEY`、`TEXT_BASE_URL`、`TEXT_MODEL` | 方舟文字对话、记忆提取和会议处理 |
| `SPEECH_VOLC_APP_KEY`、`SPEECH_VOLC_ACCESS_KEY`、`SPEECH_VOLC_RESOURCE_ID` | 语音识别；需要实际开通 ASR 的凭据与资源 |
| `TTS_VOLC_APP_KEY`、`TTS_VOLC_ACCESS_KEY`、`TTS_VOLC_RESOURCE_ID`、`TTS_VOLC_VOICE_TYPE` | 语音回复 |
| `AMAP_API_KEY` | 位置相关服务，只有配置且授权位置时使用 |
| `UPSTREAM_PROXY`、`PROVIDER_PROXY` | 云服务器通常留空；不能沿用开发机 `127.0.0.1` 代理 |
| `AIDOL_REGISTRATION_OPEN=true` | 开放自助注册；设 `false` 后已有用户仍能登录 |
| `AIDOL_MAX_USERS=500` | 体验注册上限 |
| `AIDOL_DAILY_REQUESTS=300` | 单用户计费操作窗口配额；不是资金预算上限 |

`.env` 中无需新增域名、Viaim Key、邮件或短信参数。不要把 TTS 凭据擅自视为已开通 ASR；识别失败需检查 ASR 权限与 resource id。systemd 固定持久化目录，不会使用开发机数据库路径。云端 `DATABASE_PATH` 和 `AIDOL_DATA_DIR` 由 service 文件覆盖。

5. 执行发布脚本：

```bash
chmod 600 /opt/aidol/shared/.env
cd /root/aidol-upload
bash deploy/install.sh /root/aidol-upload
```

脚本创建独立虚拟环境、安装锁定依赖、编译 Python、用临时数据库检查 `/healthz`、备份已有 SQLite、切换 `current`、启动 systemd 并校验 nginx。检测不调用付费供应商 API。已有版本若新服务启动失败会尝试恢复旧代码。临时上传目录可在确认发布成功后由运维自行清理。

6. 将服务器的**公开** `/opt/aidol/shared/tls/ca.pem` 下载到构建机 `android-app/app/src/main/res/raw/aidol_server_ca.pem`，构建 APK。不要下载或打包 `ca.key`、`server.key`、`.env`。

## APK 分发与同事操作

直接私聊发新版 APK 最方便。也可将已签名 APK 上传到 `/opt/aidol/downloads/aidol-android.apk`，文件权限 644，父目录 755，并生成 SHA-256：

```bash
cd /opt/aidol/downloads
sha256sum aidol-android.apk > aidol-android.apk.sha256
chmod 644 aidol-android.apk aidol-android.apk.sha256
```

下载链接是 `http://123.207.219.174/downloads/aidol-android.apk`。浏览器通常不信任 App 内置的测试 CA，所以 HTTP 只用于分发文件，不处理注册、密码或任何应用 API；同事从已核实的聊天消息比对 SHA-256。`http://123.207.219.174/auth/login` 等地址返回 404。

同事安装后创建自己唯一的账号和密码，保存恢复码，允许录音权限，再测试文字与语音对话。无需填写服务器地址和供应商密钥。蓝牙耳机先在 Android 系统配对；实际采音路径和播放路由需要不同真机验证，不能用服务器测试替代硬件验收。

## 检查、日志和常用运维

```bash
systemctl status aidol --no-pager
journalctl -u aidol -n 100 --no-pager
nginx -t
curl --fail http://127.0.0.1:8108/healthz
curl --fail --cacert /opt/aidol/shared/tls/ca.pem --resolve 123.207.219.174:7776:127.0.0.1 https://123.207.219.174:7776/healthz
ss -ltnp | grep -E ':(7776|8108) '
python3 /root/aidol-upload/deploy/smoke-test.py --ca /opt/aidol/shared/tls/ca.pem --connect-ip 127.0.0.1
```

`smoke-test.py` 自动注册两个临时账号，检查唯一性、密码、数据隔离、退出、改密、恢复码和注销，最后删除自己的测试账号；不调用 AI 供应商。限流针对同一 IP，避免在 15 分钟内反复运行此脚本。服务器访问自身公网 IP 可能不支持回环，`--connect-ip 127.0.0.1` 只改连接目的地，仍校验 IP 证书和完整 nginx HTTPS 链路；外部电脑运行时去掉此参数，单独验证公网可达性。

API 健康不等于供应商和耳机链路已经验证。部署验收至少检查：健康接口、无登录访问被拒绝、两个账号的数据隔离、重复账号被拒绝、退出后旧 token 失效、修改/恢复密码和删除账号。供应商真实语音调用、整个 App 操作和物理耳机分别记录到 `docs/acceptance.md`；不要写成同一种测试。

改动 `.env` 后 `systemctl restart aidol` 才生效。日志不要开启请求体或 `Authorization` 打印。nginx 日志在 `/var/log/nginx/aidol-*.log`，systemd 日志由 journald 管理。遇到 401 重新登录；429 等待限流窗口；503 可先检查服务负载；语音服务错误则检查对应供应商授权与额度。

## 升级、备份和回滚

升级：上传最新允许的源码到新暂存目录，再运行 `deploy/install.sh <目录>`。保留 `/opt/aidol/shared/`。安装脚本使用 SQLite backup API 而非直接拷贝正在写入的 `.sqlite3`，每个数据库快照内部一致；需要账号与所有用户库在同一时间点一致时，先停止服务后备份：

```bash
systemctl stop aidol
umask 077
python3 /root/aidol-upload/deploy/backup.py /opt/aidol/shared/data /opt/aidol/shared/backups/manual-$(date -u +%Y%m%dT%H%M%SZ)
systemctl start aidol
```

同时将 `.env`、TLS 私钥和备份妥善保存在受控的离线位置，不上传 Git。服务器损坏后只恢复代码不足以恢复账号，必须恢复 shared 数据。

上一代码版本回滚：

```bash
test -d /opt/aidol/previous
ln -sfn "$(readlink -f /opt/aidol/previous)" /opt/aidol/current.next
mv -Tf /opt/aidol/current.next /opt/aidol/current
systemctl restart aidol
curl --fail http://127.0.0.1:8108/healthz
```

代码回滚不回退用户数据。如果未来发布包含不兼容数据库迁移，先停止服务、保留失败现场再选择匹配快照恢复；这会丢失快照之后的新记录，因此应单独安排恢复窗口。

## 证书续签

至少提前一个月检查证书剩余时间：

```bash
openssl x509 -in /opt/aidol/shared/tls/server.pem -checkend 2592000 -noout
bash /root/aidol-upload/deploy/renew-certificate.sh
curl --fail --cacert /opt/aidol/shared/tls/ca.pem --resolve 123.207.219.174:7776:127.0.0.1 https://123.207.219.174:7776/healthz
```

续签脚本保留 CA 与服务器私钥，签发新的 365 天 IP 证书，校验后平滑重载 nginx。不要删除 `ca.pem` 后重跑首次准备脚本，否则旧 APK 不再信任新的 CA。未来正式公开发布时可改用域名和公有 CA，再更新 APK；当前实现面向这台服务器上的团队体验测试。
