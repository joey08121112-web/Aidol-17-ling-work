"""验证「主动挂断 = 直接断开连接」这条路。

用最小 RFC6455 服务端冒充上游，保持沉默（不发任何帧），
这样 _run 会真的阻塞在 recv 上 —— 只有 socket 被唤醒才会退出。
"""
import base64
import hashlib
import os
import queue
import socket
import struct
import sys
import threading
import time

# 项目根目录由脚本位置推导，从 tests/ 往上退一层 —— 换机器、挪目录都能跑。
PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, PROJECT)
import server  # noqa: E402

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (f"   [{detail}]" if detail else ""))


class FakeUpstream:
    """握手后保持沉默，并记录客户端发来的文本帧。"""

    def __init__(self):
        self.srv = socket.socket()
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(("127.0.0.1", 0))
        self.srv.listen(1)
        self.port = self.srv.getsockname()[1]
        self.frames = []
        self.handshaked = threading.Event()
        threading.Thread(target=self._serve, daemon=True).start()

    def _serve(self):
        try:
            conn, _ = self.srv.accept()
        except OSError:
            return
        conn.settimeout(15)
        try:
            data = b""
            while b"\r\n\r\n" not in data:
                chunk = conn.recv(4096)
                if not chunk:
                    return
                data += chunk
            key = ""
            for line in data.decode("latin1").split("\r\n"):
                if line.lower().startswith("sec-websocket-key:"):
                    key = line.split(":", 1)[1].strip()
            accept = base64.b64encode(
                hashlib.sha1((key + GUID).encode()).digest()
            ).decode()
            conn.sendall(
                (
                    "HTTP/1.1 101 Switching Protocols\r\n"
                    "Upgrade: websocket\r\n"
                    "Connection: Upgrade\r\n"
                    f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
                ).encode()
            )
            self.handshaked.set()
            while True:
                frame = self._read_frame(conn)
                if frame is None:
                    break
                op, payload = frame
                if op == 0x1:
                    self.frames.append(payload.decode("utf-8", "replace"))
                elif op == 0x8:
                    break
        except Exception:
            pass
        finally:
            try:
                conn.close()
            except OSError:
                pass

    @staticmethod
    def _read_frame(conn):
        def exact(n):
            buf = b""
            while len(buf) < n:
                chunk = conn.recv(n - len(buf))
                if not chunk:
                    return None
                buf += chunk
            return buf

        head = exact(2)
        if head is None:
            return None
        op = head[0] & 0x0F
        masked = bool(head[1] & 0x80)
        length = head[1] & 0x7F
        if length == 126:
            length = struct.unpack("!H", exact(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", exact(8))[0]
        mask = exact(4) if masked else b""
        payload = exact(length) if length else b""
        if payload is None:
            return None
        if masked:
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        return op, payload


def drain_until(session, wanted, timeout):
    deadline = time.time() + timeout
    seen = []
    while time.time() < deadline:
        try:
            event = session.events.get(timeout=0.1)
        except queue.Empty:
            continue
        seen.append(event)
        if event.get("type") in wanted:
            return event, seen
    return None, seen


def make_session(port):
    server.UPSTREAM_WS_HOST = "127.0.0.1"
    server.UPSTREAM_WS_PORT = port
    server.UPSTREAM_WS_TLS = False
    server.UPSTREAM_WS_PATH = "/api-ws/v1/realtime"
    return server.DuplexSession({
        "api_key": "fake-key-for-local-test",
        "model": "qwen3.8-omni-flash-realtime",
        "voice": "Tina",
        "tool_root": PROJECT,
    })


print("=== A) 正常挂断：即时收尾、不报错、不发 session.finish ===")
up = FakeUpstream()
sess = make_session(up.port)

ev, seen = drain_until(sess, {"local.connected"}, 6)
check("上游握手成功并进入 connected", ev is not None and up.handshaked.is_set())

# 真实的挂断发生在通话进行中，不是握手后瞬间 —— 等 session.update 真正发出去再关，
# 否则关得太早会把 send_json 掐掉，测出来的就不是「挂断」而是「建连竞态」。
sess.ready.wait(5)
time.sleep(0.3)
check("确实发出了 session.update", any("session.update" in f for f in up.frames),
      f"共 {len(up.frames)} 帧")

t0 = time.perf_counter()
sess.close()
closed_ev, seen2 = drain_until(sess, {"local.closed"}, 8)
elapsed = time.perf_counter() - t0
check("挂断后 3 秒内收到 local.closed（读线程被唤醒，而非等 60s 超时）",
      closed_ev is not None and elapsed < 3.0, f"{elapsed:.2f}s")

time.sleep(0.3)  # 给假上游一点时间读完帧
joined = " | ".join(up.frames)
check("全程没有发 session.finish", "session.finish" not in joined)

all_events = seen + seen2
check("主动挂断不产生 local.error",
      not any(e.get("type") == "local.error" for e in all_events),
      ", ".join(e.get("type", "?") for e in all_events))

check("close_requested / closed / ready / finished_at 全部落位",
      sess.close_requested.is_set() and sess.closed.is_set()
      and sess.ready.is_set() and sess.finished_at is not None)

try:
    sess.close()
    sess.close()
    check("重复 close() 幂等，不抛异常", True)
except Exception as exc:  # noqa: BLE001
    check("重复 close() 幂等，不抛异常", False, repr(exc))

print()
print("=== B) 建好就立刻挂断（竞态：还没连上就关）===")
up2 = FakeUpstream()
sess2 = make_session(up2.port)
sess2.close()
closed_ev2, seen3 = drain_until(sess2, {"local.closed"}, 6)
check("立刻挂断也能在 6 秒内收尾", closed_ev2 is not None)
check("这条路径同样不报错",
      not any(e.get("type") == "local.error" for e in seen3),
      ", ".join(e.get("type", "?") for e in seen3))

print()
print("=== C) 会话可回收：close 后 finished_at 让 DemoState 能摘除它 ===")
state = server.DemoState()
state.sessions.clear()
state.sessions["x"] = sess2
removed = state.reap(sess2.finished_at + server.DemoState.SESSION_TTL_SECONDS + 1)
check("TTL 到期后被回收", removed == ["x"], f"removed={removed}")

print()
print("=== D) 唤醒数据来源：确认挂断瞬间上游一个字节都没发 ===")
check("假上游全程沉默（0 帧下发），收尾不依赖「等到 error 事件」",
      sess.closed.is_set(), f"客户端上行 {len(up.frames)} 帧 / 下行 0 帧")

print()
passed = sum(1 for _, ok, _ in results if ok)
print(f"===== {passed}/{len(results)} 通过 =====")
sys.exit(0 if passed == len(results) else 1)
