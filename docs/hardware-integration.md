# Viaim 耳机接入

## 当前 Android Rise 接入（M1，实物待验）

官方 SDK 6.7 已固定引入 `android-app/`。目标 viaim Rise VI-A10 / VI-A10-B；本轮只接耳机短录音，不接盒子、按键、闪录、ANC 或后台唤醒。官方 Word、demo ZIP 和AAR校验/换机规则见 [M0/M1交接](android-m0-m1-handoff.md)。设备接入只在 Android 实现；官网不承担录音或硬件连接。

`hardware/HeadsetController` 是 SDK 单一所有者，使用Application Context，用户明确连接已配对目标，等待初始化与 `onConnectReady` 后才查询属性/录音。配对名称只发现候选；通信就绪还核对 SDK 类型6510与连接地址。SDK生命周期跨语音轮次，状态区分未连接、初始化、连接中、传输连接、通信就绪、断开中和错误。观察者解绑与generation隔离，设备切换重新绑定属性；未上报/0xFF电量、未知佩戴与固件保持未知，位置0不代表盒盖关闭。当前开发使用 USB 安卓真机，旧模拟器源不能启动会话。

`VendorCaptureSource` 经可注入 `FusionDevice` 使用真实 `startFusionRecord` / `stopFusionRecord`，仅收channel=2的已解码PCM；channel=1扬声器、255结束标记不送ASR。官方为16kHz/mono/PCM16，按小端契约处理；字节序、速率、真实来源仍须实测。跨回调半样本暂存至下一块，结束仍不完整则失败。回调复制缓冲进入有界队列，满时失败，不默认写原始音频文件。

实际 SDK 开麦命令通过 `FusionDevice.Listener.startIfCurrent(Runnable)` 在来源代次锁内执行；取消与排队启动原子互斥，不能先查一个布尔值后再无保护开麦。状态回调的就绪通知与首 PCM 保持顺序，取消后的旧回调不能开启新一轮。

正常停止等待 `idle` 并排空后回调 `ended`，超时/超长/队列溢出不上传。取消立即禁收与丢弃，设备清理完成才能重开。统一采集占用防止SDK与系统AudioRecord/SCO并行。`CaptureTurn` 预留3秒停止预算，默认57秒发停止，含收尾最高60秒/1920000字节，不截断伪装成功。SDK自身停止确认2.5秒，开始最多等待12秒。

输出走 Android `ReplyPlayer`，先静音核验实际路由再播放。Rise要求已就绪目标地址等于 `AudioDeviceInfo.getAddress()`；任意蓝牙、相似名称、SDK已连接不足以放行。系统不给地址或Android8缺该API时明确拒绝Rise播放，文字结果保留；系统蓝牙兼容模式需用户显式选择。采集停止后才上传/播放，工作遵守 `speak=false`，无自动插话。焦点/通话/断连/离开前台停止本轮，恢复不自动开麦。

用户追加的模式/开始/结束提示使用五段本地固定语音，见 [操作语音](android-audio-prompts.md)。播放前通过 `PlaybackLease` 等待采集清理确认（最多4秒），占有同一互斥租约至播放结束；因此录音取消后立刻切模式，也不能与旧 SDK 采集重叠。每段使用独立播放器，提示失败不会使后续业务回复被静默跳过。开始提示失败不开麦；结束提示失败保留已排空录音，显式取消仍中止后续提交。工作模式允许操作提示，但业务回答继续按 `speak=false` 静音。

设备页展示真实上报属性，debug诊断有SDK就绪、首PCM、字节数及阶段；不输出完整SN/MAC/电话号码/音频/Key。session和request ID关联后端ASR/业务/TTS日志。假FusionDevice只用于测试，不在App冒充实物。

验收顺序：USB登录文字 → 系统配对/通信就绪 → 麦克风来源对照 → 结束尾句 → 识别/记忆来源 → 同目标耳机听音 → 工作静音 → 取消/超时/断连/来电/快启停/锁屏。逐手机与固件填 [acceptance](acceptance.md)，历史供应商成功不代替本轮实物。

## 系统蓝牙兼容来源

普通蓝牙模式使用 Android 系统 AudioRecord/SCO 采集，必须核对实际输入是耳机，失败不静默回落手机麦克风。录音完成先释放 SCO，再等待蓝牙媒体输出恢复，由 `ReplyPlayer` 核对输出并播放。使用 Rise SDK 失败时需要用户明确切换兼容来源，不能自动切换。

系统蓝牙可用不等于厂商 SDK 的 ANC、按键、闪录或盒子功能可用；蓝牙通话音频的音质、多人远场拾音和设备切换需要按手机/耳机组合记录。

## 后续硬件扩展

M2 的采集/播放并发需真机验证回声和插话；M3 锁屏会话需 Android 前台服务和明确停止入口；M4 按键、闪录、盒子等只按已确认协议与固件能力接入。存在 SDK 枚举不等于设备会上报，不把未知电量、佩戴或固件写成默认成功。发现具体问题时携带脱敏阶段日志和复现步骤向厂商核对，范围见 [产品规划](android-rise-product-plan.md)。
