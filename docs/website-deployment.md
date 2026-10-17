# Aidol 产品官网部署

官网源码位于 `website/`，是独立的静态产品介绍页。Android APK 下载路径为 `/downloads/aidol-android.apk`，复用当前已发布的安装包。官网不接收账号密码，不代理 App API，不需要供应商密钥。

| 项目 | 配置 |
| --- | --- |
| 官网 | `http://123.207.219.174:6655/` |
| APK 下载 | `http://123.207.219.174:6655/downloads/aidol-android.apk` |
| 校验文件 | `http://123.207.219.174:6655/downloads/aidol-android.apk.sha256` |
| 静态发布目录 | `/opt/aidol/website/releases/<release>/` |
| 当前版本 | `/opt/aidol/website/current` 符号链接 |
| 上一版本 | `/opt/aidol/website/previous` 符号链接 |
| nginx 配置 | `/etc/nginx/sites-available/aidol-website` |
| 安装脚本 | `deploy/website-install.sh` |

官网通过 nginx 独立监听 TCP 6655。原 80/443 网站、7776 Android API、8108 后端和用户数据均沿用原配置。APK 内置的账号服务仍为 `https://123.207.219.174:7776`，官网端口不是账号服务地址。

## 发布

1. 只上传 `website/`、`deploy/website-install.sh`、`deploy/website-nginx.conf` 到独立暂存目录，例如 `/root/aidol-website-upload-<timestamp>/`。不要上传整个仓库、`.env`、录音、数据库、私钥、构建缓存或 Git 历史。
2. SSH 登录服务器，执行：

```bash
bash /root/aidol-website-upload-<timestamp>/deploy/website-install.sh /root/aidol-website-upload-<timestamp>
```

脚本校验已发布 APK 的 SHA-256，新建静态版本目录，原子切换 `current`，执行 `nginx -t` 后平滑重载 nginx，再检查首页与 APK。检查失败会恢复原官网链接和配置。若 UFW 已启用，只增补 TCP 6655 规则；不会修改其他端口或默认策略。云安全组需要允许 TCP 6655，脚本无法代替云平台设置。每次发布后从外部网络验证可达性。

脚本不上传或重建 APK。需要更新安装包时，按 [Android 部署文档](android-deployment.md) 发布同签名 APK 和校验文件，再同步官网版本信息。不要给官网链接替换成 debug 或直连供应商 demo APK。

## 验证与回滚

```bash
nginx -t
curl --fail --head http://127.0.0.1:6655/
curl --fail --head http://127.0.0.1:6655/downloads/aidol-android.apk
curl --fail http://127.0.0.1:6655/downloads/aidol-android.apk.sha256
systemctl is-active nginx aidol
```

在外部电脑打开官网，检查桌面与手机宽度下的展示、导航和下载按钮；下载后比对 SHA-256。HTTP 仅分发公开页面和安装包，账号与密码通过 App 内置 HTTPS 服务传输。公开正式分发可后续接入域名及公有 CA HTTPS；当前 IP 端口部署使用 HTTP。

回滚静态页面不影响 APK 和用户数据：

```bash
test -d /opt/aidol/website/previous
ln -sfn "$(readlink -f /opt/aidol/website/previous)" /opt/aidol/website/current.next
mv -Tf /opt/aidol/website/current.next /opt/aidol/website/current
curl --fail --head http://127.0.0.1:6655/
```

静态页面回滚无需重启 App。nginx 官网访问与错误日志分别为 `/var/log/nginx/aidol-website-access.log`、`/var/log/nginx/aidol-website-error.log`。

## 2026-10-17 发布前观察

- 通过已有 SSH 密钥连接服务器，未将 SSH 密码写入脚本、文档或文件。
- 服务器为 Ubuntu 24.04 LTS；nginx、aidol 均为 active；TCP 6655 尚未监听。
- UFW 已启用，发布前没有 TCP 6655 规则。
- 服务器 `/opt/aidol/downloads/aidol-android.apk` 与本地 `android-app/aidol-android.apk` 内容一致：1,080,198 bytes，SHA-256 `b847304291db9c374aa13ce4942b6497eecf310c03498a12336a96e1913acce0`。
- APK 包名 `com.aidol.app`，版本 `0.2.0`，versionCode 2，最低 Android 8.0 / API 26，target API 35。
- `apksigner verify --verbose --print-certs` 通过，使用 APK Signature Scheme v2；签名证书 SHA-256 为 `e71b49a9c64613905a301334bb6c207cefc72f7aff91f20eb722c2062eab0da2`，与现有发布文档一致。清单未启用 debuggable，明确关闭 cleartext traffic；包内含固定 HTTPS 服务地址。
- 以上是静态发布文件与服务器检查，不代表新的供应商调用、完整 App 链路或物理耳机验收。

## 2026-10-17 实际发布结果

北京时间 13:24 完成独立官网发布，版本目录为 `/opt/aidol/website/releases/20261017T052429Z-CO4PHF`。上传范围仅 `website/`、`deploy/website-install.sh` 和 `deploy/website-nginx.conf`，使用现有 SSH 密钥；复用已发布 APK，未重新构建 App。

- `nginx -t` 通过，平滑重载成功；官网 TCP 6655 已监听。UFW 新增 `6655/tcp` 的 IPv4/IPv6 规则，其他规则保留。
- 从开发机通过公网 IP 完整 GET 首页、CSS、JS、favicon、三张产品图片、APK 与 SHA-256 文件，全部返回 HTTP 200，资源 MIME 类型正确。公网无需追加云安全组操作即可访问。
- 公网完整下载 APK 为 1,080,198 bytes，SHA-256 与本地及服务器已发布文件一致：`b847304291db9c374aa13ce4942b6497eecf310c03498a12336a96e1913acce0`。
- APK 响应为 `Content-Type: application/vnd.android.package-archive`，附带 `Content-Disposition: attachment; filename="aidol-android.apk"`、`Cache-Control: no-cache`、`X-Content-Type-Options: nosniff`。
- 官网 `/auth/login` 与 `/downloads/` 返回 404，`/.env` 返回 403；未提供 API 代理或目录列表。
- 发布后 nginx 与 aidol 服务均为 active，原 `http://127.0.0.1:8108/healthz` 返回 `{"ok":true,"version":"0.2.0","ready":true}`。

以上为实际部署、静态资源及 APK 分发验证。页面视觉与交互 QA 单独进行，不将官网验证记为供应商或耳机实测。

## 2026-10-17 极简产品图更新

北京时间 13:31 第二次原子发布，当前版本为 `/opt/aidol/website/releases/20261017T053145Z-8h67ha`，`previous` 指向首版 `/opt/aidol/website/releases/20261017T052429Z-CO4PHF`。更新内容为浅灰首屏样式 `hero-refinement.css`、首屏与分享预览图 `assets/viaim-minimal-earbuds.png`、产品细节图 `assets/viaim-minimal-detail.png`，保留生活场景图。旧黑色与银色场景图未进入本次发布目录。

从开发机直连公网地址，解析已发布 HTML 动态提取资源链接，完整 GET 首页及引用的 8 个不同资源（包括 APK），全部 HTTP 200 且逐字节与本地文件一致。新资源实测如下：

| 资源 | 公网响应大小 | SHA-256 |
| --- | ---: | --- |
| `hero-refinement.css`（最终微调后） | 1,324 bytes | `4014e8594fcd29b475ac7ac152ef68f73e7d3fa4d7c5a5d328d95d1bb420fb2d` |
| `assets/viaim-minimal-earbuds.png` | 1,131,488 bytes | `d1981a84c935999835b7c9f7a99df5fc7baf356b49f7c9b1779750e890fc26c7` |
| `assets/viaim-minimal-detail.png` | 1,540,235 bytes | `216af5f7a5577e75b4406b83a879d3d1e283991a840f76673f515088e1fe27e5` |

新 CSS 返回 `text/css`，两张新图返回 `image/png`。APK 完整下载仍为 1,080,198 bytes，SHA-256 `b847304291db9c374aa13ce4942b6497eecf310c03498a12336a96e1913acce0`，下载 MIME 与 attachment 文件名保持正确。旧 `/assets/viaim-black.png` 返回 404。nginx 配置检查通过，nginx/aidol 均 active，后端健康接口仍为 ready。既有防火墙规则未重复新增。

随后仅将 `.site-header` 背景最终微调为 `rgba(250,250,252,.96)`，以临时文件加原子重命名更新上述版本目录中的 `hero-refinement.css`。再次通过公网完整 GET 该 CSS，HTTP 200、`text/css`、1,324 bytes，逐字节与本地一致；上表记录最终校验值。其他文件保持二次发布时的已验证内容。

## 2026-10-17 产品定位与购买入口更新

北京时间 13:45 原子发布 `/opt/aidol/website/releases/20261017T054523Z-hTGJPQ`，`previous` 指向 `/opt/aidol/website/releases/20261017T053145Z-8h67ha`。本次变更为 `index.html` 与 `hero-refinement.css`：产品定位说明为基于 viaim AI 耳机二次开发的 Aidol，补充硬件品牌归属与型号以店铺为准的说明，添加 viaim 官网及天猫购买入口。

购买入口采用 [viaim 官网](https://www.viaim.cn/) 公开页面脚本 [chunk-d03d103c.39746e8c.js](https://www.viaim.cn/static/js/chunk-d03d103c.39746e8c.js) 页脚提供的店铺地址 `https://weilaizhineng.tmall.com`。这是品牌店铺入口；未确认 Rise 特定 SKU、当前在售库存或完整购买流程，不把链接存在性检查记为购买流程验收。

发布后从开发机直连公网，完整 GET 两个修改文件并与本地逐字节比较，通过：

| 文件 | 响应 | 大小 | SHA-256 |
| --- | --- | ---: | --- |
| `index.html` | 200 / `text/html` | 18,499 bytes | `93319433412052b11bbb4c2c4b771d7061ba3a9ea64cce62d47ad94199d97161` |
| `hero-refinement.css` | 200 / `text/css` | 2,419 bytes | `4a1625a18f3cd26ad19f15c25a1b9d1de524255631d5a8fe10b0157a0d8e7b7e` |

解析已发布 HTML 确认恰有 3 个 `https://weilaizhineng.tmall.com` 购买链接和 2 个 `https://www.viaim.cn/` 官网链接，全部带 `target="_blank"`、`rel="noopener noreferrer"`。APK 未重新构建或替换，安装脚本再次校验既有 SHA-256；公网 APK HEAD 为 200，大小 1,080,198 bytes，MIME 与附件文件名保持正确。沿用已验证的其他资源，本次未重复下载 APK、测试全仓库或运行供应商调用。

## 本地页面与代码验证

2026-10-17：桌面1280px、手机390px及窄屏320px检查，无页面横向溢出；新版浅灰双耳首屏与细节图正常加载。记忆示例、场景Tab（含方向键）、移动菜单展开与锚点关闭已实测。浏览器未报告JavaScript错误。FAQ为原生details，自动点击验证未取得明确open状态，未记为通过；公网浏览器截图工具后期出现超时，但公网页面和所有引用资源的HTTP与字节校验已通过，桌面和手机视觉已在同内容的本地预览中检查。

当次发布的历史仓库检查：`python -m pytest tests -q` 260 passed（1条依赖弃用警告）；当时的Node音频测试27 passed；`python -m compileall -q app`通过。旧Web音频现已移除，这些数量不作为当前精简后的回归结果。未调用实时供应商探测，未重新构建或修改已发布APK。

最终产品图使用内置ImageGen，以用户提供的viaim实物照片为形状依据，Apple官方图仅作风格参考。图片为AI视觉制作，不属于真实摄影或硬件验证。两张最终图及完整提示词包保存在本地 `artifacts/website/aidol-minimal-product-images.zip`，未发布原始参考照片。无Git提交。


## 2026-10-17 耳机联动功能介绍更新

发布 `/opt/aidol/website/releases/20261017T060509Z-UOEXeS`。当时新增三击切换模式、双击单轮录音、长按取消，以及设备页主动开启与事件状态介绍；说明摘下/断连停止当前操作、前台仅重试原 Rise 最多三次且不自动录音。新增个人记忆可自定义添加、编辑的说明。明确触控联动处于开发体验版验证阶段，手势映射固定，未声称现有下载 APK 已含联动或全部手势已完成实物验收。随后用户改为单击开始/结束、双击切模式，当前官网源码已同步这一规则；此次安卓收尾未重新部署官网。

HTML ID 与锚点检查通过；nginx 配置检查及 APK SHA 校验通过，APK HEAD 200；公网首页完整 GET 为 200，内容与本地逐字节一致。本次仅修改静态文案，未构建或替换 APK，未运行供应商及实物测试。

随后按用户要求改为正式产品文案，移除“体验版”和“下载体验”，保留 Rise SDK 对应版本及事件适配说明。发布目录 `/opt/aidol/website/releases/20261017T060537Z-mOhmNK`，公网首页 200 且逐字节匹配本地；nginx 与 APK 校验通过。仅更新官网，未改变 APK 或硬件验收状态。

## 2026-10-17 京东购买入口更新

按用户提供链接，将首页、产品区和下载区的三个购买入口统一改为京东 viaim Rise 商品链接，更新元描述、FAQ 与购买文案，移除天猫入口。发布 `/opt/aidol/website/releases/20261017T070606Z-y1CzvG`，公网首页 HTTP 200 且逐字节匹配本地，确认三个京东链接一致。nginx 配置与既有 APK 校验通过；APK 未替换。商品链接由用户提供，未进行下单验证。
