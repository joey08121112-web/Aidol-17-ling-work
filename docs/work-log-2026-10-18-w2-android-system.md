# 2026-10-18 工作日志：W2 Android 系统层性能重构

窗口：`docs/perf-audit-2026-10-18.md` 的 W2（Android 系统与音频会话层）。git worktree
`/Users/ami/Documents/ZCode/Aidol-Aidol-android-system`，分支 `perf/android-system`，基线
`de6fcf9`（本地自建 worktree；`local.properties` 与 Rise SDK AAR 不入库，从主 worktree
复制）。构建环境：homebrew JDK 21、SDK 35。

## 完成的七项修复（每项独立提交）

| 提交 | 审计项 | 做法 |
| --- | --- | --- |
| `26ed4ca` | ApiClient.java:41-43,69-71 debug 后端强制 `Connection: close` 零复用 | 去掉逐请求关闭头。安全性本不依赖该头：GET/HEAD 走 safeReads 可在陈旧套接字上自动换连重试，写请求 retryOnConnectionFailure(false) 从不重放。127.0.0.1 判定保留为 `isDebugUsbOrigin`，改为仅给该源 10 秒池 keep-alive（adb reverse 重载后的陈旧窗口收窄），远端 TLS 语义不变 |
| `903b4f2` | AuthVault.java:30-38,57-61 主线程 Keystore 加解密+commit() | 加解密与落盘全部移到单线程守护线程（`aidol-auth-vault`），`commit()` 改 `apply()`。`token()/username()` 首读有界等待（2.5s）后台加载：MainActivity 主线程的同步首屏判定保持正确，主线程不再执行加密。嵌套 `VaultState` 承载顺序规则（晚到 load 不覆盖更新的 save/clear）并单测 |
| `84ca512` | CompanionOrbView.java:62 25fps 常驻自续动画 | 声级 1.5 秒不动后 40ms→250ms（约 -84% 空闲绘制）。不可完全停绘：meter 回调只赋值 `level` 不调 invalidate，自续循环是唯一的重绘驱动。隐藏/分离停绘逻辑不变 |
| `e7f7fef` | hardware/HeadsetController.java:597-615 断连清理主线程直呼 SDK+50ms 自旋最长 5.5s | SDK 原生调用与轮询移到守护线程，仅最终决定（已释放/未确认/被取代）回主线程；`releaseOperation` 转 volatile。纯决策类 `ReleaseWatch` 单测（stale/release/timeout 优先级、含边界的 deadline）。destroy 失败保持原语义：按未确认报错并要求重启应用 |
| `588464d` | HostedSession.java:283-315 串行 latch 链；PlaybackLease.java:35-37 25ms 自旋；ReplyPlayer.java:104,151,182-205 Thread.sleep 忙等；HostedSession.java:510-555 10Hz 轮询 | ① `awaitReady+finish` 与 END 提示音经 `TurnFlush` 并行（链路付 max 而非 sum），失败仍在原位置抛出，弃置时中断 flush 线程；② PlaybackLease close 改 `EventSignal`（版本守卫监视器，无丢信号）事件唤醒，25ms 上限保留（SystemCaptureLease 无信号机制、取消标志无钩子）；③ ReplyPlayer 三处状态等待事件化：AudioDeviceCallback（注册即送当前设备表）、提前注册的路由监听器（armed 门控防误杀启动期路由）、播放位置 marker；写循环保留 20ms 小睡（框架无缓冲空间事件 API，注释说明）；④ 工作录音循环事件驱动：分段入队（pcm 回调）、结束、暂停、失败、取消都发信号，settle 分支精确等到边界，空闲仅留 1 秒防丢醒。对外协议语义不变 |
| `1654396` | VoicePreRoll.java:7-9 逐字节取模写环形缓冲 | append/snapshot 均改 `System.arraycopy` 双段拷贝，含超长截尾与环绕边界 |
| `77ae6d2` | VoiceSessionService.java:26-40 每秒主线程查音频模式 | API 31+ 用 `OnModeChangedListener` 事件驱动（创建时补一次初检捕获已在通话），<31 保留原轮询；唤醒锁续期改为半租期（300s）精确调度，各 API 一致。停会话判定抽为纯代数 `shouldStopForCall`/`wakeRenewDue` 并单测（含通信路由所有权豁免） |

新增共享纯类全部嵌套在 W2 拥有的宿主类内（`PlaybackLease.EventSignal`、
`HostedSession.TurnFlush`、`AuthVault.VaultState`、`HeadsetController.ReleaseWatch`），
因为 W2 文件清单不含新文件；W7 集成时按宿主文件合并即可。

## 验证

- `./gradlew testDebugUnitTest`：**235 通过 0 失败**（基线 205 + 新增 30：EventSignal 5、
  TurnFlush 3、VaultState 5、CompanionOrbView 3、ReleaseWatch 4、VoicePreRoll 净增 5、
  VoiceSessionService 5）。线程/超时语义改动均有对应新测试，沿用现有纯类+代数/超时风格。
- `./gradlew lintDebug`：**0 错误**（12 警告，与基线同为已存警告类）。
- `./gradlew assembleDebug`：APK 正常产出。
- `git diff de6fcf9 HEAD -- MainActivity.java` 为空：**MainActivity 零改动**，W1 边界遵守。

## 虚拟环境（Android 模拟器）冒烟

环境：本机 AVD `Aidol_Pixel_7`（android-36 google_apis/arm64），headless 启动
（`-no-window -gpu swiftshader_indirect`）；未触碰 USB 真机（4fb3c538 仅枚举）。

| 步骤 | 结果 |
| --- | --- |
| 安装 app-debug.apk（com.aidol.app.debug） | Success |
| 冷启动到登录页 | 进程存活，登录页完整渲染（orb 球体、文案、登录/创建账号 tab、同意条款），截图人工确认无渲染异常 |
| 交互 | 点击"创建账号"tab 正常切换，输入区随之变化 |
| AuthVault 后台线程 | `ps -T` 见 `aidol-auth-vaul`（内核 15 字符截断）守护线程存活，T2 路径在设备上运行 |
| Orb 动画 | 见下方排查，最终确认运行 |
| 稳定性 | 前台驻留 20 秒以上，无 FATAL EXCEPTION、无 AndroidRuntime 崩溃记录 |

### 动画静止的排查（重要，记录完整过程）

初测像素差分（1.2s 两帧）完全一致，疑似动画未跑。`dumpsys gfxinfo` reset 后
Total frames rendered 恒为 0——即使发生确定的交互重绘后仍为 0，判定该 headless+
swiftshader 组合下 gfxinfo 计数不可用，测量手段失效而非应用不渲染。

随后做 2×2 对照：把 CompanionOrbView 单文件切回基线 `de6fcf9` 构建安装，同环境同样
静止——**无回归**。根因是该 AVD 的 `animator_duration_scale` 设置缺失，进程按禁用处理，
两版代码都经 `areAnimatorsEnabled()` 尊重该开关（设计行为）。将缩放经
`settings put 0.5→1.0` 触发 ContentObserver 刷新进程缓存后，我的版本动画确认运行
（像素差分检出帧间变化）。

### 模拟器未覆盖、移交 W6 真机窗口

- Rise SDK 全链路（连接、断连清理线程、触控、Fusion 录音）：`supportsSdkAbi` 按设计
  拒绝模拟器，需真机+耳机。
- 帧率/耗电定量（orb 4fps 空闲、工作录音循环唤醒次数、断连主线程占用）：headless 模拟器
  的 gfxinfo 不可用，需真机 `dumpsys gfxinfo`/battery 统计。
- debug USB 源连接复用的实测收益（adb reverse 场景）。
- VoiceSessionService 的 OnModeChangedListener 在真机来电场景的行为。

## 验收边界

本次验证范围：单元测试、lint、构建产物、模拟器安装/启动/交互/线程/动画/稳定性冒烟。
未操作 USB 手机或实体耳机，未调用真实供应商，未部署后端，未发布 APK。模拟器结论
（渲染正常、无崩溃、无回归）不替代真机验收；定量性能数字以 W6 在集成分支上的实测为准。
