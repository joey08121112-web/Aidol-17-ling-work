# 离线回归测试

在项目根目录安装开发依赖并运行：

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m pytest tests -q
```

测试使用临时 SQLite，配置中的密钥、访问口令全部置空；需要认证响应的测试只用明确标注的假 Key。地图和文本模型响应来自 `httpx.MockTransport`，语音服务来自内存 fake provider，不会消耗模型额度。

| 文件 | 保护的行为 |
| --- | --- |
| `test_api.py` | 托管 API 参数校验、旧 Web/SSE/音频入口关闭、导入应用不创建单用户数据库 |
| `test_mobile_api.py` | 真实账户认证、用户数据隔离、语音与文本回合幂等、限流、注销、地图与草稿 |
| `test_android_backend.py` / `test_android_usb.py` | 本地安卓后端启动、USB 连接、旧环境变量不能切回单用户服务 |
| `test_agent_memory.py` | 手动模式优先、场景切换防抖、时区/行程决策、并发转写去重、记忆规则降级、到站提醒限频 |
| `test_maps.py` | 无配置不虚构 ETA、GPS 转 GCJ-02 后规划路线、服务错误不暴露 Key |
| `test_storage_events.py` | 重开数据库持久化、清除保留设置、参数化检索、草稿单次操作、慢客户端队列背压 |
| `test_speech_api.py` / `test_volc_speech.py` | 安卓 PCM 上传边界、最终转写与记忆、合成 WAV、供应商协议和错误脱敏 |
| `test_product_features_api.py` | 会议输入过滤、纪要复用、任务完成状态、导出清除、回忆输入校验 |
| `test_memory_source_api.py` / `test_memory_management.py` | 记忆纠正、收藏、原文溯源、存储迁移和删除 |
| `test_insights.py` / `test_recall.py` | 会议提取、检索回答和降级行为 |

这些测试证明本地业务链路及协议边界行为，不能代替真实耳机兼容性、厂商鉴权、端到端延迟、真实路线和音质验收。接入厂商 SDK 后，新增独立适配器契约测试，并保留当前离线测试可在无密钥环境执行。
