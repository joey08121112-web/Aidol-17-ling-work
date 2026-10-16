# 离线回归测试

在项目根目录安装开发依赖并运行：

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m pytest tests -q
node --test tests/audio-worklet.test.mjs tests/audio-session.test.mjs
```

测试使用临时 SQLite，配置中的密钥、访问口令全部置空；需要认证响应的测试只用明确标注的假 Key。地图和文本模型响应来自 `httpx.MockTransport`，语音服务来自内存 fake provider，不会消耗模型额度。

| 文件 | 保护的行为 |
| --- | --- |
| `test_api.py` | API 鉴权、跨站拦截、参数校验、记忆 CRUD、导出清除、草稿不能伪装为已发送 |
| `test_agent_memory.py` | 手动模式优先、场景切换防抖、时区/行程决策、并发转写去重、记忆规则降级、到站提醒限频 |
| `test_maps.py` | 无配置不虚构 ETA、GPS 转 GCJ-02 后规划路线、服务错误不暴露 Key |
| `test_audio_lifecycle.py` | 主备模型切换、会话关闭、异常后计数恢复、最后一批转写排空、工作模式静音、PCM 帧校验 |
| `test_storage_events.py` | 重开数据库持久化、清除保留设置、参数化检索、草稿单次操作、慢客户端队列背压 |
| `test_speech_api.py` | 独立 ASR/TTS 接口、WAV 格式校验、flash/stream 路径、错误脱敏 |
| `test_cascade.py` | 300ms 前滚/800ms 静音/15s 切句、ASR→Agent→TTS、工作静音、新发言打断、队列背压 |
| `test_streaming_cascade.py` | 连续 PCM 上传与实时 partial、definite 句去重、累计末段补录、ASR/Agent/TTS 并行、背压和取消 |
| `audio-worklet.test.mjs` | 48k/44.1k 跨帧重采样时长与相位、PCM16 边界和字节序、RMS 能量 |
| `audio-session.test.mjs` | 取消权限等待、快速重连、旧回调隔离、TTS 独立耳机输出与打断、上传背压 |

这些测试证明本地业务链路及协议边界行为，不能代替真实耳机兼容性、厂商鉴权、端到端延迟、真实路线和音质验收。接入厂商 SDK 后，新增独立适配器契约测试，并保留当前离线测试可在无密钥环境执行。
