"""端到端验证会话回收：建会话 -> 结束 -> 保留期 -> TTL 到期摘除。

在独立端口起一个测试实例，不影响用户正在跑的服务。
"""
import json
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import server  # noqa: E402

fails = []


def check(name, cond, extra=""):
    if not cond:
        fails.append(name)
    print("  [%s] %s%s" % ("PASS" if cond else "FAIL", name, ("   " + extra) if extra else ""))


srv = server.QuietThreadingHTTPServer(("127.0.0.1", 8791), server.Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()
server.STATE.sessions.clear()

print("=== 1) 建 5 个会话（假 key，握手必然失败）===")


def create():
    req = urllib.request.Request(
        "http://127.0.0.1:8791/api/session",
        data=json.dumps({
            "api_key": "sk-reap-test",
            "model": "qwen3.8-omni-flash-realtime",
            "voice": "Tina",
        }).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read())


ids = [create()["session_id"] for _ in range(5)]
check("5 个会话都写进了注册表", len(server.STATE.sessions) == 5, "sessions=%d" % len(server.STATE.sessions))

print()
print("=== 2) 等它们跑完（上游 401）===")
for _ in range(80):
    if all(s.finished_at is not None for s in server.STATE.sessions.values()):
        break
    time.sleep(0.5)

done = sum(1 for s in server.STATE.sessions.values() if s.finished_at is not None)
check("全部会话都标记了结束时刻", done == 5, "%d/5" % done)
check("结束后仍在注册表里（保留期未到）", len(server.STATE.sessions) == 5, "sessions=%d" % len(server.STATE.sessions))

print()
print("=== 3) 保留期内不该被摘除 ===")
check("保留期内 reap 返回空", server.STATE.reap() == [], "TTL=%ds" % server.DemoState.SESSION_TTL_SECONDS)

print()
print("=== 4) TTL 到期后应被摘除 ===")
server.DemoState.SESSION_TTL_SECONDS = 0
removed = server.STATE.reap()
check("到期会话被回收", len(removed) == 5, "回收 %d 个" % len(removed))
check("注册表已清空", len(server.STATE.sessions) == 0, "sessions=%d" % len(server.STATE.sessions))

print()
print("=== 5) 回收后旧 session_id 查不到（接口应 404）===")
code = None
try:
    urllib.request.urlopen("http://127.0.0.1:8791/api/events?session_id=" + ids[0], timeout=5)
except urllib.error.HTTPError as exc:
    code = exc.code
except Exception as exc:  # noqa: BLE001
    code = "ERR:" + str(exc)
check("已回收的会话查不到", code == 404, "HTTP %s" % code)

print()
print("=== 6) 回收后服务仍能建新会话 ===")
new_id = create()["session_id"]
check("能继续建会话", bool(new_id), "新 session=%s" % new_id[:8])
check("注册表只留新会话", len(server.STATE.sessions) == 1, "sessions=%d" % len(server.STATE.sessions))

srv.shutdown()
print()
print("==> " + ("全部通过" if not fails else "有 %d 项未通过: %s" % (len(fails), fails)))
# 和其余测试统一：有失败就非零退出，供 run_all.py 与 CI 判定。
sys.exit(1 if fails else 0)
