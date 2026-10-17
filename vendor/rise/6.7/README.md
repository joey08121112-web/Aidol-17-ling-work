# Rise Android SDK 6.7

此目录随 Git 提供厂商 Demo 与官方 Android 接口文档，供新电脑构建 Aidol 和查阅硬件接口。

- `demo_6_7.zip`：厂商 Demo 源码与官方 AAR。为满足仓库不存储签名私钥和密码的要求，已移除 `app/demo.jks`、`app/build.gradle` 中的 `signingConfigs` 及对应 release 签名引用；其余文件保持原样。它不是原始 ZIP 的逐字节副本。
- `耳机SDK Android 文档.docx`：官方原始文档。
- AAR SHA-256：`e074506fd3a46346272fdd2ab632a59b4cef2597f4cb37fd160737eb536e2b2c`，与厂商原版一致。

在仓库根目录执行导入，然后按 `docs/android-local-testing.md` 编译 `android-app/`，无需编译厂商 Demo：

```powershell
powershell -File scripts/import-rise-sdk.ps1 -DemoZip vendor/rise/6.7/demo_6_7.zip
```

```sh
bash scripts/import-rise-sdk.sh vendor/rise/6.7/demo_6_7.zip
```

提取出的 `android-app/app/libs/*.aar` 仍被 Git 忽略；SDK 已包含在上述受版本管理的 ZIP 中。厂商 Demo 仅作接口参考，不是 Aidol 产品入口。若单独构建 Demo 的 release，需自行提供签名。
