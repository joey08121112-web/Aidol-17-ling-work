"""工具调用链路端到端测试。

用最小 RFC6455 服务端冒充上游：这次它不只沉默，还能主动下发
`response.function_call_arguments.done`，从而真的触发后端的工具执行路径。
MCP 工具接的是 fake_mcp_server.py（真按 MCP 协议说话）。
"""

import base64
import hashlib
import json
import os
import queue
import socket
import struct
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
sys.path.insert(0, PROJECT)

import mcp_client  # noqa: E402
import server  # noqa: E402

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
FAKE_MCP = os.path.join(HERE, "fake_mcp_server.py")

results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (f"   [{detail}]" if detail else ""))


class FakeUpstream:
    """握手后保持连接，既能记录客户端上行帧，也能主动下发事件。"""

    def __init__(self):
        self.srv = socket.socket()
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(("127.0.0.1", 0))
        self.srv.listen(1)
        self.port = self.srv.getsockname()[1]
        self.frames = []
        self.frames_lock = threading.Lock()
        self.conn = None
        self.handshaked = threading.Event()
        threading.Thread(target=self._serve, daemon=True).start()

    def _serve(self):
        try:
            conn, _ = self.srv.accept()
        except OSError:
            return
        self.conn = conn
        conn.settimeout(20)
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
            accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
            conn.sendall((
                "HTTP/1.1 101 Switching Protocols\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
            ).encode())
            self.handshaked.set()
            while True:
                frame = self._read_frame(conn)
                if frame is None:
                    break
                op, payload = frame
                if op == 0x1:
                    with self.frames_lock:
                        self.frames.append(payload.decode("utf-8", "replace"))
                elif op == 0x8:
                    break
                elif op == 0x9:
                    pass  # ping：测试里不关心 pong
        except Exception:
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

    def send_json(self, payload):
        """以服务端身份下发一个文本帧（服务端帧不加掩码）。"""
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        header = bytearray([0x81])
        if len(data) < 126:
            header.append(len(data))
        elif len(data) < 65536:
            header.append(126)
            header.extend(struct.pack("!H", len(data)))
        else:
            header.append(127)
            header.extend(struct.pack("!Q", len(data)))
        conn = self.conn
        if not conn:
            raise RuntimeError("尚未握手")
        conn.sendall(bytes(header) + data)

    def sent(self, needle):
        with self.frames_lock:
            return [f for f in self.frames if needle in f]

    def clear(self):
        with self.frames_lock:
            self.frames.clear()


def make_registry():
    registry = mcp_client.McpToolRegistry(logger=lambda *_: None)
    registry.add({
        "name": "fake",
        "command": sys.executable,
        "args": [FAKE_MCP, "--name", "fake-demo"],
        "timeout": 10,
    })
    registry.start_all()
    return registry


def make_session(port, registry):
    server.UPSTREAM_WS_HOST = "127.0.0.1"
    server.UPSTREAM_WS_PORT = port
    server.UPSTREAM_WS_TLS = False
    server.UPSTREAM_WS_PATH = "/api-ws/v1/realtime"
    return server.DuplexSession({
        "api_key": "fake-key-for-local-test",
        "model": "qwen3.8-omni-flash-realtime",
        "voice": "Tina",
        "tool_root": PROJECT,
        "mcp_registry": registry,
    })


def wait_event(session, wanted, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            event = session.events.get(timeout=0.05)
        except queue.Empty:
            continue
        if event.get("type") in wanted:
            return event
    return None


def collect(session, seconds):
    out = []
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            out.append(session.events.get(timeout=0.05))
        except queue.Empty:
            pass
    return out


def call_tool(up, call_id, name, arguments):
    up.send_json({
        "type": "response.function_call_arguments.done",
        "items": [{"call_id": call_id, "name": name, "arguments": json.dumps(arguments, ensure_ascii=False)}],
    })


registry = make_registry()
print("=== 0) MCP 前置检查 ===")
check("MCP 服务已就绪", registry.failures == [], registry.failures)
check("发现 4 个工具", len(registry.tools) == 4, len(registry.tools))

print()
print("=== 1) MCP 工具 schema 真的进了 session.update ===")
up = FakeUpstream()
sess = make_session(up.port, registry)
check("上游握手成功", wait_event(sess, {"local.connected"}, 6) is not None)
sess.ready.wait(5)
time.sleep(0.4)
joined = " | ".join(up.frames)
check("session.update 里有 MCP 工具名", "mcp__fake__read_note" in joined, joined[:120])
check("session.update 里有内置文件工具", "list_local_directory" in joined)
check("可写工具描述里带确认提示", "需要用户确认" in joined)

print()
print("=== 2) 只读 MCP 工具：自动执行 + 回灌 + response.create（闭环关键）===")
up.clear()
before = len(sess.events.queue)
call_tool(up, "call_read_1", "mcp__fake__read_note", {"path": "note.txt"})
ev = wait_event(sess, {"local.tool_result"}, 8)
check("产生了 tool_result", ev is not None)
check("工具执行成功", (ev or {}).get("result", {}).get("ok") is True, (ev or {}).get("result"))
check("结果文本正确", "hello from fake mcp" in (ev or {}).get("result", {}).get("text", ""))
check("tool_result 保留了 arguments 字段（前端契约）", "arguments" in (ev or {}), list((ev or {}).keys()))
time.sleep(0.4)
check("向上游回灌了 role=tool 的消息", len(up.sent('"role":"tool"')) >= 1, up.sent('"role":"tool"'))
check("回灌之后显式要了一次 response.create", len(up.sent('"type":"response.create"')) >= 1,
      up.sent('"type":"response.create"'))
check("没有产生待确认请求", (ev or {}).get("type") != "local.tool_confirm_request")

print()
print("=== 3) 可写 MCP 工具：挂起等用户确认，绝不擅自执行 ===")
up.clear()
call_tool(up, "call_write_1", "mcp__fake__write_note", {"path": "b.txt", "content": "hi"})
ev = wait_event(sess, {"local.tool_confirm_request"}, 8)
check("产生了确认请求", ev is not None)
token = (ev or {}).get("token")
check("确认请求带 token", bool(token))
check("确认请求带 session_id", (ev or {}).get("session_id") == sess.id)
check("确认请求带工具名与参数", (ev or {}).get("name") == "mcp__fake__write_note"
      and "content" in str((ev or {}).get("arguments")), (ev or {}).get("arguments"))
leaked = [e for e in collect(sess, 0.6) if e.get("type") == "local.tool_result"]
check("挂起期间没有产生 tool_result（没偷偷执行）", leaked == [], leaked)
check("挂起期间没有回灌真正的工具结果",
      not any("wrote" in f for f in up.sent('"role":"tool"')), up.sent('"role":"tool"'))

print()
print("=== 4) 用户点确认：这时才执行 ===")
up.clear()
check("待确认表里有这条", token in sess.pending_confirms)
out = sess.resolve_confirmation(token, True)
check("resolve 返回 executed=True", out.get("executed") is True, out)
ev = wait_event(sess, {"local.tool_result"}, 8)
check("确认后产生 tool_result", ev is not None)
check("工具真的执行了", "wrote" in str((ev or {}).get("result", {})), (ev or {}).get("result"))
time.sleep(0.3)
check("确认后回灌上游", len(up.sent('"role":"tool"')) >= 1, up.sent('"role":"tool"'))
check("确认后同样要了 response.create", len(up.sent('"type":"response.create"')) >= 1)
check("待确认表已清空", token not in sess.pending_confirms)

print()
print("=== 5) 用户点拒绝：不执行，并如实告诉模型 ===")
up.clear()
call_tool(up, "call_write_2", "mcp__fake__write_note", {"path": "c.txt", "content": "no"})
ev = wait_event(sess, {"local.tool_confirm_request"}, 8)
token2 = (ev or {}).get("token")
out = sess.resolve_confirmation(token2, False)
check("resolve 返回 executed=False", out.get("executed") is False, out)
ev = wait_event(sess, {"local.tool_result"}, 8)
result = (ev or {}).get("result", {})
check("结果标记为 denied", result.get("denied") is True, result)
check("拒绝路径也有回灌", len(up.sent('"role":"tool"')) >= 1)
check("拒绝路径也要了 response.create", len(up.sent('"type":"response.create"')) >= 1)
check("拒绝后没有真的写入", not any("wrote" in f for f in up.sent('"role":"tool"')))

print()
print("=== 6) 等不到用户：超时自动取消 ===")
original_timeout = server.TOOL_CONFIRM_TIMEOUT_SECONDS
server.TOOL_CONFIRM_TIMEOUT_SECONDS = 1
try:
    call_tool(up, "call_write_3", "mcp__fake__write_note", {"path": "d.txt", "content": "x"})
    ev = wait_event(sess, {"local.tool_confirm_request"}, 8)
    token3 = (ev or {}).get("token")
    ev = wait_event(sess, {"local.tool_confirm_expired"}, 6)
    check("超时后产生 expired 事件", ev is not None)
    check("超时后待确认表清空", token3 not in sess.pending_confirms)
    ev = wait_event(sess, {"local.tool_result"}, 6)
    check("超时也回灌给模型（不让它干等）", (ev or {}).get("result", {}).get("expired") is True,
          (ev or {}).get("result"))
    try:
        sess.resolve_confirmation(token3, True)
        check("超时后再确认应当报错", False, "居然成功了")
    except ValueError as exc:
        check("超时后再确认会报错", "不存在" in str(exc) or "过期" in str(exc), exc)
finally:
    server.TOOL_CONFIRM_TIMEOUT_SECONDS = original_timeout

print()
print("=== 7) 内置文件工具不受影响，仍然免确认 ===")
up.clear()
call_tool(up, "call_builtin_1", "list_local_directory", {"path": ".", "limit": 5})
ev = wait_event(sess, {"local.tool_result"}, 8)
check("内置工具直接执行", (ev or {}).get("result", {}).get("ok") is True, (ev or {}).get("result"))
check("内置工具不需要确认", (ev or {}).get("type") == "local.tool_result")

print()
print("=== 8) 慢工具不阻塞事件泵（这是线程池改造的意义）===")
up.clear()
up.send_json({
    "type": "response.function_call_arguments.done",
    "items": [{"call_id": "call_slow", "name": "mcp__fake__slow_echo",
               "arguments": json.dumps({"seconds": 2})}],
})
time.sleep(0.3)  # 让慢工具先跑起来
started = time.time()
up.send_json({"type": "response.audio.delta", "delta": "AAAA"})
probe = wait_event(sess, {"upstream.event"}, 4)
latency = time.time() - started
check("慢工具执行期间事件泵仍在收包", probe is not None, f"延迟 {latency:.2f}s")
check(f"下行事件几乎无延迟（实际 {latency:.2f}s，若内联执行会是 2s）", latency < 0.8, latency)
ev = wait_event(sess, {"local.tool_result"}, 8)
check("慢工具最终也正常回灌", (ev or {}).get("result", {}).get("ok") is True, (ev or {}).get("result"))

print()
print("=== 9) 挂断时清理挂起的确认 ===")
up.clear()
call_tool(up, "call_write_4", "mcp__fake__write_note", {"path": "e.txt", "content": "y"})
ev = wait_event(sess, {"local.tool_confirm_request"}, 8)
token4 = (ev or {}).get("token")
check("确实挂了待确认", token4 in sess.pending_confirms)
sess.close()
closed = wait_event(sess, {"local.closed"}, 8)
check("挂断正常收尾", closed is not None)
check("挂断后待确认被清空", sess.pending_confirms == {}, sess.pending_confirms)
try:
    sess.resolve_confirmation(token4, True)
    check("挂断后再确认应当报错", False, "居然成功了")
except ValueError as exc:
    check("挂断后再确认会报错", True, exc)

registry.close()

print()
passed = sum(1 for _, ok, _ in results if ok)
print(f"===== {passed}/{len(results)} 通过 =====")
sys.exit(0 if passed == len(results) else 1)
