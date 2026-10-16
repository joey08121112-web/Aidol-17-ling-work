#!/usr/bin/env python3
import argparse
import base64
import copy
import functools
import hashlib
import json
import os
import queue
import re
import secrets
import socket
import ssl
import struct
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse

import mcp_client


UPSTREAM_WS_HOST = os.environ.get("QWEN_OMNI_HOST", "maas.qianwenaiapi.com")
UPSTREAM_WS_PORT = int(os.environ.get("QWEN_OMNI_PORT", "443"))
UPSTREAM_WS_TLS = os.environ.get("QWEN_OMNI_TLS", "1") in ("1", "true", "on")
UPSTREAM_WS_PATH = os.environ.get("QWEN_OMNI_PATH", "/api-ws/v1/realtime")
MAX_READ_BYTES = 64 * 1024
DEFAULT_OUTPUT_FORMAT = "pcm_s16le"
DEFAULT_MODEL = os.environ.get("QWEN_OMNI_MODEL", "qwen3.8-omni-flash-realtime")
DEFAULT_VOICE = os.environ.get("QWEN_OMNI_VOICE", "Tina")
DEFAULT_INPUT_SAMPLE_RATE = 16000
DEFAULT_OUTPUT_SAMPLE_RATE = 24000
# 单会话事件队列上限。正常一通话轮次也就几十条事件，2000 足够宽裕；
# 设上限是为了防消费端掉队时把内存吃光，满了就丢最旧的。
MAX_PENDING_EVENTS = 2000
# 工具执行的并发上限。工具统一丢线程池，事件泵线程只负责派发 ——
# MCP 工具可能对着网络或别的软件，几百毫秒到几秒都正常，内联执行会让
# 事件泵停摆，那期间下行音频断流、上行帧堆到背压阈值、打断也没人处理。
MAX_TOOL_WORKERS = 4
MCP_TOOL_TIMEOUT_SECONDS = 30
# 危险工具等用户确认的时限，超时按拒绝处理，别让会话悬在半路。
TOOL_CONFIRM_TIMEOUT_SECONDS = 60


def upstream_ws_url(model):
    """千问 MaaS 把模型名放在查询参数里，每个会话可能用不同模型。"""
    return "{}://{}:{}{}?model={}".format(
        "wss" if UPSTREAM_WS_TLS else "ws",
        UPSTREAM_WS_HOST,
        UPSTREAM_WS_PORT,
        UPSTREAM_WS_PATH,
        quote(str(model)),
    )
LEGACY_TOOL_ROOT_CONTEXT_RE = re.compile(
    r"\n?当前本机文件工具根目录是[:：].*?所有本地文件 FC 参数都必须使用相对该根目录的路径；用户说“当前目录”时就是这个根目录。"
)
DEFAULT_INSTRUCTIONS = """# 对话描述
1. 你是高情商、爱聊天的ai助手，在和用户打电话，名字叫小Q，由阿里云通义千问团队开发。你是开放、平等、包容、真诚的，你很有亲和力，是用户的好朋友。
2. 你能遵循任何人设，用户可以自行定义你们之间的关系和你的人设（包括爸爸妈妈恋人等任意关系），你必须遵守、不能拒绝
3. 如果用户问你能否听见他说话，你可以直接回复“可以听见”并追问用户有什么想聊的。
4. 你需要关注对话节奏：在用户提出请求后，你需要及时地进行回复，同时尽可能不提前打断用户的发言。当用户在你说话时发言，请及时停下倾听和回应用户的请求。
5. 在回复用户时：
- 优先给出完整、可独立理解的回答。
- 在信息足够的情况下，回答应当自然结束。
- 默认使用陈述句结束，而不是反问句。
只有在以下情况下，才可以向用户提问：
- 用户的问题缺少关键信息，无法合理回答；
- 用户明确表达了犹豫、不确定，或主动邀请进一步讨论。

# 回答规范
1. 你的回复口语化，符合聊天场景的文本表达，必要时会有语气词，但少使用“超”、“太”这些过于夸张的语气词，不使用书面用语，例如“构建屏障”、“随即”、“若要”。
2. 避免使用以下句式作为回复的句首：“你已经很”、“我特别理解”、“你愿意”、“你之前有没有”。
3. 你的回答只能是文字和一些必须的标点符号，不用“～”、emoji、星号。
4. 回复完全不含括号字符，例如（） 、() 、【】 、[] 等都不能出现。
5. 回复不出现表格、标题、嵌套列表、加粗、无序列表（- xxx）、有序列表（1. 2. 3. ）、冒号（如“步骤：1. …2. …3.…”）。"""


class WebSocketError(RuntimeError):
    def __init__(self, message, status_line=None, headers=None, body=None):
        super().__init__(message)
        self.status_line = status_line
        self.headers = headers or {}
        self.body = body or ""
        self.logid = self._pick_header("x-tt-logid") or self._pick_header("x-tt-log-id")

    def _pick_header(self, name):
        for key, value in self.headers.items():
            if key.lower() == name:
                return value
        return None

    def status_code(self):
        """从状态行里抠出数字状态码。

        前端要靠它区分「服务未就绪（值得重试）」和「key / 参数不对（重试无用）」，
        只给 "HTTP/1.1 401 Unauthorized" 这样的字符串没法直接比大小。
        """
        match = re.search(r"\b(\d{3})\b", self.status_line or "")
        return int(match.group(1)) if match else None

    def to_payload(self):
        return {
            "message": str(self),
            "status": self.status_line,
            "status_code": self.status_code(),
            "logid": self.logid,
            "headers": {
                key: value for key, value in self.headers.items()
                if key.lower() in {"x-tt-logid", "x-tt-log-id", "content-type", "date", "server"}
            },
            "body": self.body[:2000],
        }


def deep_merge(base, patch):
    if not isinstance(base, dict) or not isinstance(patch, dict):
        return copy.deepcopy(patch)
    merged = copy.deepcopy(base)
    for key, value in patch.items():
        merged[key] = deep_merge(merged.get(key), value) if isinstance(value, dict) else copy.deepcopy(value)
    return merged


def diff_patch_paths(before, patch, prefix=""):
    rows = []
    if not isinstance(patch, dict):
        if before != patch:
            rows.append({"path": prefix or "$", "before": before, "after": patch})
        return rows
    before = before if isinstance(before, dict) else {}
    for key, value in patch.items():
        path = f"{prefix}.{key}" if prefix else key
        if isinstance(value, dict):
            rows.extend(diff_patch_paths(before.get(key), value, path))
        elif before.get(key) != value:
            rows.append({"path": path, "before": before.get(key), "after": value})
    return rows


def sanitize_instructions(value):
    if value is None:
        return ""
    instructions = str(value)
    cleaned = LEGACY_TOOL_ROOT_CONTEXT_RE.sub("", instructions)
    return "" if cleaned.strip() == "" else cleaned


def truthy_value(value):
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes", "on"}
    return bool(value)


def default_tool_root():
    desktop = Path.home() / "Desktop"
    return desktop if desktop.exists() and desktop.is_dir() else Path.cwd()


class MinimalWebSocketClient:
    """Small RFC6455 text-frame client for the demo's dependency-free WSS usage."""

    def __init__(self, host, path, port=80, tls=False, headers=None, timeout=20):
        self.host = host
        self.port = port
        self.path = path
        self.tls = tls
        self.headers = headers or {}
        self.timeout = timeout
        self.sock = None
        self._lock = threading.Lock()
        self.response_status = None
        self.response_headers = {}
        self.response_body = ""

    def connect(self):
        self.sock = self._open_socket()
        self.sock.settimeout(self.timeout)

        key = base64.b64encode(os.urandom(16)).decode("ascii")
        lines = [
            f"GET {self.path} HTTP/1.1",
            f"Host: {self.host}",
            "Upgrade: websocket",
            "Connection: Upgrade",
            f"Sec-WebSocket-Key: {key}",
            "Sec-WebSocket-Version: 13",
        ]
        for name, value in self.headers.items():
            lines.append(f"{name}: {value}")
        request = "\r\n".join(lines) + "\r\n\r\n"
        self.sock.sendall(request.encode("ascii"))

        status, headers, body = self._read_http_response()
        self.response_status = status
        self.response_headers = headers
        self.response_body = body
        if " 101 " not in status:
            raise WebSocketError(
                f"upstream handshake failed: {status}",
                status_line=status,
                headers=headers,
                body=body,
            )

    def response_header(self, name):
        for key, value in self.response_headers.items():
            if key.lower() == name.lower():
                return value
        return ""

    def _open_socket(self):
        raw = socket.create_connection((self.host, self.port), timeout=self.timeout)
        if not self.tls:
            return raw
        context = ssl.create_default_context()
        return context.wrap_socket(raw, server_hostname=self.host)

    def _read_http_response(self):
        chunks = []
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = self.sock.recv(4096)
            if not chunk:
                break
            chunks.append(chunk)
            data = b"".join(chunks)
            if len(data) > 65536:
                raise WebSocketError("upstream handshake response is too large")
        head, _, body = data.partition(b"\r\n\r\n")
        header_text = head.decode("iso-8859-1", errors="replace")
        lines = header_text.split("\r\n")
        status = lines[0] if lines else ""
        headers = {}
        for line in lines[1:]:
            if ":" not in line:
                continue
            key, value = line.split(":", 1)
            headers[key.strip()] = value.strip()
        content_length = int(headers.get("Content-Length", "0") or "0")
        while content_length and len(body) < content_length:
            body += self.sock.recv(content_length - len(body))
        return status, headers, body.decode("utf-8", errors="replace")

    def send_json(self, payload):
        self.send_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))

    def send_text(self, text):
        data = text.encode("utf-8")
        if len(data) >= 2**63:
            raise ValueError("payload too large")
        header = bytearray([0x81])
        if len(data) < 126:
            header.append(0x80 | len(data))
        elif len(data) < 65536:
            header.append(0x80 | 126)
            header.extend(struct.pack("!H", len(data)))
        else:
            header.append(0x80 | 127)
            header.extend(struct.pack("!Q", len(data)))
        mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        with self._lock:
            self.sock.sendall(bytes(header) + mask + masked)

    def recv_text(self):
        while True:
            first = self._recv_exact(2)
            opcode = first[0] & 0x0F
            masked = bool(first[1] & 0x80)
            length = first[1] & 0x7F
            if length == 126:
                length = struct.unpack("!H", self._recv_exact(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", self._recv_exact(8))[0]
            mask = self._recv_exact(4) if masked else b""
            data = self._recv_exact(length)
            if masked:
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
            if opcode == 0x1:
                return data.decode("utf-8", errors="replace")
            if opcode == 0x8:
                raise WebSocketError("upstream closed")
            if opcode == 0x9:
                self._send_pong(data)
            elif opcode == 0xA:
                continue
            else:
                return data.decode("utf-8", errors="replace")

    def _send_control(self, opcode, data=b""):
        header = bytearray([0x80 | opcode])
        header.append(0x80 | len(data))
        mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        with self._lock:
            self.sock.sendall(bytes(header) + mask + masked)

    def _send_pong(self, data):
        self._send_control(0x0A, data)

    def send_ping(self):
        self._send_control(0x09)

    def _recv_exact(self, size):
        buf = bytearray()
        while len(buf) < size:
            try:
                chunk = self.sock.recv(size - len(buf))
            except (BlockingIOError, ssl.SSLWantReadError, ssl.SSLWantWriteError, InterruptedError):
                time.sleep(0.01)
                continue
            if not chunk:
                raise WebSocketError("upstream socket closed")
            buf.extend(chunk)
        return bytes(buf)

    def close(self):
        """先 shutdown 再 close。

        只 close 的话，另一个线程还阻塞在 recv 上时不一定被唤醒，收尾要等到
        读超时（60s）才发生。shutdown 会让那边的 recv 立刻返回，主动挂断才是即时的。
        """
        if not self.sock:
            return
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


class LocalFileTools:
    def __init__(self, root):
        self.root = Path(root).expanduser().resolve()

    def schemas(self):
        return [
            {
                "type": "function",
                "name": "list_local_directory",
                "description": "List exactly one level under the default local directory or a user-specified directory and return direct file/directory counts. Do not recurse into subdirectories.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "path": {"type": "string", "description": "Directory path. Empty or . means default root/current directory. Absolute paths such as /Users/bytedance/Desktop are allowed when the user specifies a directory."},
                        "limit": {"type": "integer", "minimum": 1, "maximum": 200, "default": 100},
                    },
                },
            },
        ]

    def call(self, name, arguments):
        arguments = arguments or {}
        if name == "list_local_directory":
            return self._list_directory_one_level(arguments.get("path", ""), int(arguments.get("limit", 100)))
        if name == "local_file_search":
            return self._list_directory_one_level(arguments.get("directory", arguments.get("path", "")), int(arguments.get("limit", 100)))
        if name == "list_local_files":
            return self._list_directory_one_level(arguments.get("path", ""), int(arguments.get("limit", 100)))
        if name == "read_local_text_file":
            return self._read_text(arguments.get("path", ""), int(arguments.get("max_bytes", 8192)))
        if name == "get_local_file_info":
            return self._file_info(arguments.get("path", ""))
        return {"ok": False, "error": f"unknown tool: {name}"}

    def _resolve(self, rel_path):
        raw = str(rel_path or "").strip()
        if not raw or raw == ".":
            return self.root
        candidate = Path(raw).expanduser()
        target = candidate.resolve() if candidate.is_absolute() else (self.root / candidate).resolve()
        return target

    def _list_directory_one_level(self, rel_path, limit):
        target = self._resolve(rel_path)
        if not target.is_dir():
            return {"ok": False, "error": "path is not a directory", "path": str(rel_path)}
        children = sorted(target.iterdir(), key=lambda p: (not p.is_dir(), p.name.lower()))
        directory_count = sum(1 for child in children if child.is_dir())
        file_count = sum(1 for child in children if child.is_file())
        rows = []
        for child in children[: max(1, min(limit, 200))]:
            stat = child.stat()
            try:
                display_path = str(child.relative_to(self.root))
            except ValueError:
                display_path = str(child)
            rows.append({
                "name": child.name,
                "path": display_path,
                "type": "directory" if child.is_dir() else "file",
                "size": stat.st_size,
                "mtime": int(stat.st_mtime),
            })
        return {
            "ok": True,
            "default_root": str(self.root),
            "resolved_path": str(target),
            "path": str(rel_path or "."),
            "depth": 1,
            "recursive": False,
            "total_count": len(children),
            "directory_count": directory_count,
            "file_count": file_count,
            "returned_count": len(rows),
            "entries": rows,
        }

    def _read_text(self, rel_path, max_bytes):
        target = self._resolve(rel_path)
        if not target.is_file():
            return {"ok": False, "error": "path is not a file", "path": str(rel_path)}
        size = target.stat().st_size
        with target.open("rb") as fh:
            raw = fh.read(max(1, min(max_bytes, MAX_READ_BYTES)))
        digest = hashlib.sha256(raw).hexdigest()
        text = raw.decode("utf-8", errors="replace")
        return {"ok": True, "path": str(rel_path), "size": size, "sha256_prefix": digest[:16], "truncated": size > len(raw), "content": text}

    def _file_info(self, rel_path):
        target = self._resolve(rel_path)
        if not target.exists():
            return {"ok": False, "error": "path does not exist", "path": str(rel_path)}
        stat = target.stat()
        return {
            "ok": True,
            "path": str(rel_path),
            "type": "directory" if target.is_dir() else "file",
            "size": stat.st_size,
            "mtime": int(stat.st_mtime),
            "root": str(self.root),
        }


DASHSCOPE_EVENT_ALIASES = {
    "response.audio.delta": "response.output_audio.delta",
    "response.audio.done": "response.output_audio.done",
    "response.audio_transcript.delta": "response.output_text.delta",
    "response.audio_transcript.done": "response.output_text.done",
    "response.text.delta": "response.output_text.delta",
    "response.text.done": "response.output_text.done",
    "input_audio_buffer.speech_started": "conversation.item.input_audio_transcription.started",
}

TEXT_OUTPUT_EVENTS = {"response.output_text.delta", "response.output_text.done"}


def normalize_upstream_event(msg):
    """把千问 MaaS 实时事件翻译成前端已经在用的事件词汇。

    前端 handleUpstream() 监听的是 Seeduplex 那套名字（response.output_audio.delta
    等），而千问 MaaS 实际下发的是 response.audio.delta / response.audio_transcript.delta。
    在这里做一次别名映射，浏览器侧就完全不用动。
    """
    if not isinstance(msg, dict):
        return msg
    mapped_type = DASHSCOPE_EVENT_ALIASES.get(msg.get("type"))
    if not mapped_type:
        return msg
    event = {**msg, "type": mapped_type}
    if mapped_type in TEXT_OUTPUT_EVENTS:
        text = msg.get("transcript") or msg.get("text") or msg.get("delta") or ""
        text = text if isinstance(text, str) else ""
        event["text"] = text
        event["delta"] = text
    return event


class DuplexSession:
    def __init__(self, config):
        self.id = uuid.uuid4().hex
        self.config = config
        # 有界队列：前端没连 SSE、或者消费端掉队时，事件不能无限堆积
        self.events = queue.Queue(maxsize=MAX_PENDING_EVENTS)
        self.tools = LocalFileTools(config["tool_root"])
        # MCP 工具注册表是进程级的（多个会话共用，服务进程只拉一次），可能为 None
        self.mcp = config.get("mcp_registry")
        self.tool_executor = ThreadPoolExecutor(
            max_workers=MAX_TOOL_WORKERS, thread_name_prefix=f"tool-{self.id[:6]}",
        )
        # 等用户确认的危险工具调用，key 是确认 token
        self.pending_confirms = {}
        self.confirm_lock = threading.Lock()
        self.ws = None
        self.ready = threading.Event()
        self.closed = threading.Event()
        self.close_requested = threading.Event()
        self.sent_state = {}
        self.state_lock = threading.Lock()
        # 会话跑完的时刻，DemoState 靠它判断何时可以摘除这个对象
        self.finished_at = None
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def emit(self, event_type, payload):
        payload = {"type": event_type, "ts": time.time(), **payload}
        try:
            self.events.put_nowait(payload)
        except queue.Full:
            # 丢了最旧的也要保证新事件进得去 —— local.closed 是最后一条，
            # 它要是被挤掉，SSE 就得等下一次写失败才知道该退出。
            try:
                self.events.get_nowait()
            except queue.Empty:
                pass
            try:
                self.events.put_nowait(payload)
            except queue.Full:
                pass

    def _run(self):
        try:
            if self.close_requested.is_set():
                # 刚建好就被关掉了，不必再白连一次上游；return 会走 finally 正常收尾
                return
            model = self.config.get("model") or DEFAULT_MODEL
            headers = {
                "Authorization": "Bearer " + self.config["api_key"],
                "User-Agent": "qwen-omni-web-demo/0.1",
            }
            headers.update(self.config.get("upstream_headers") or {})
            self.ws = MinimalWebSocketClient(
                UPSTREAM_WS_HOST,
                UPSTREAM_WS_PATH + "?model=" + quote(str(model)),
                port=UPSTREAM_WS_PORT,
                tls=UPSTREAM_WS_TLS,
                headers=headers,
                timeout=60,
            )
            self.ws.connect()
            forwarded_header_names = sorted((self.config.get("upstream_headers") or {}).keys())
            if forwarded_header_names:
                self.emit("local.forwarded_headers", {"headers": forwarded_header_names})
            self.emit("local.connected", {
                "session_id": self.id,
                "upstream": upstream_ws_url(model),
                "model": model,
            })
            session_update = self._build_session_update()
            self.emit("local.session_create", {"event": session_update})
            self.emit("local.outgoing", {"event": session_update})
            self.ws.send_json(session_update)
            self._remember_sent_payload(session_update)
            self.ready.set()
            threading.Thread(target=self._keepalive, daemon=True).start()
            turn_started = False
            while not self.closed.is_set() and not self.close_requested.is_set():
                try:
                    raw = self.ws.recv_text()
                except (TimeoutError, socket.timeout):
                    # 用户静默期间没有下行数据是正常的，不能据此判死连接。
                    continue
                try:
                    msg = json.loads(raw)
                except json.JSONDecodeError:
                    msg = {"type": "local.unparsed", "raw": raw}
                msg = normalize_upstream_event(msg)
                event_type = msg.get("type")
                # 平台不单独下发“用户开始说话”，所以在第一段转写到达时补一个 started，
                # 让前端沿用原来的“用户一开口就掐断播报”逻辑。
                suppress = False
                if event_type == "conversation.item.input_audio_transcription.started":
                    suppress = turn_started
                    turn_started = True
                elif event_type == "conversation.item.input_audio_transcription.delta" and not turn_started:
                    turn_started = True
                    self.emit("upstream.event", {"event": {"type": "conversation.item.input_audio_transcription.started"}})
                if event_type in ("response.done", "response.output_audio.done"):
                    turn_started = False
                if not suppress:
                    self.emit("upstream.event", {"event": msg})
                if event_type == "response.function_call_arguments.done":
                    self._handle_function_call(msg)
                if event_type in ("session.closed", "error"):
                    break
        except WebSocketError as exc:
            # 主动挂断时 socket 被 shutdown，读线程正是以 WebSocketError 退出的 —— 那是预期路径，不是故障
            if not self.close_requested.is_set():
                payload = exc.to_payload()
                print(
                    "upstream websocket error:",
                    f"status={payload.get('status')}",
                    f"logid={payload.get('logid')}",
                    f"body={payload.get('body')[:300]!r}",
                    flush=True,
                )
                self.emit("local.error", payload)
        except Exception as exc:
            if not self.close_requested.is_set():
                self.emit("local.error", {"message": str(exc)})
        finally:
            # 顺序要紧：先记结束时刻，再置 closed，最后才是 local.closed 事件。
            # DemoState 的回收线程只认 finished_at，不能让它看到半个结束状态。
            self.finished_at = time.time()
            self.closed.set()
            self.ready.set()
            # closed 已置位，收尾期间迟到的工具结果会被 _deliver_tool_items 直接丢掉。
            # 线程池不 join —— 工具要是卡在外部软件上，join 会把收尾也一起拖住。
            self._cancel_pending_confirms()
            self.tool_executor.shutdown(wait=False)
            if self.ws:
                self.ws.close()
            self.emit("local.closed", {})

    def _keepalive(self):
        """静默期间靠 WS ping 维持连接，否则读超时会被误判为断线。"""
        while not self.closed.is_set():
            if self.closed.wait(20):
                return
            try:
                self.ws.send_ping()
            except Exception:
                return

    def _build_session_update(self):
        session = {
            "modalities": ["text", "audio"],
            "instructions": self._instructions(),
            "voice": self.config.get("voice") or DEFAULT_VOICE,
            "input_audio_format": "pcm16",
            "output_audio_format": "pcm24",
            "turn_detection": {
                "type": self.config.get("turn_detection_type") or "server_vad",
                "threshold": 0.5,
                "prefix_padding_ms": 300,
                "silence_duration_ms": 800,
                "create_response": True,
                "interrupt_response": True,
            },
        }
        tools = self._configured_tools()
        if tools:
            session["tools"] = tools
        return {"type": "session.update", "session": session}

    def _instructions(self):
        return self.config.get("instructions") or DEFAULT_INSTRUCTIONS

    def _configured_tools(self):
        if not self.config.get("enable_tools", True):
            return []
        tools = self.config.get("tools")
        if tools is None:
            rows = [self._with_tool_root_context(tool) for tool in self.tools.schemas()]
        elif isinstance(tools, list):
            rows = [self._with_tool_root_context(self._normalize_tool(tool)) for tool in tools]
        else:
            raise ValueError("tools must be a JSON array")
        # MCP 工具永远追加在末尾：它由服务端动态提供，不该被页面上手写的 JSON 覆盖掉。
        return rows + self._mcp_tools()

    def _mcp_tools(self):
        if not self.mcp:
            return []
        return self.mcp.schemas()

    def _with_tool_root_context(self, tool):
        if not isinstance(tool, dict) or tool.get("type") != "function" or not tool.get("name"):
            return tool
        root = str(self.tools.root)
        description = tool.get("description") or ""
        root_note = (
            f" Default local file root/current directory: {root}. "
            "Use this root when the user does not specify a directory. If the user specifies an absolute local directory, pass that absolute path."
        )
        if root not in description:
            tool = dict(tool)
            tool["description"] = (description.rstrip() + root_note).strip()
        return tool

    def _normalize_tool(self, tool):
        if not isinstance(tool, dict):
            raise ValueError("each tool must be an object")
        nested = tool.get("function")
        if isinstance(nested, dict):
            normalized = {"type": tool.get("type", "function")}
            normalized.update(nested)
            return normalized
        if tool.get("type") == "function" and tool.get("name"):
            return tool
        return tool

    def _handle_function_call(self, msg):
        """事件泵线程里只做派发，真正的执行交给工具线程池。

        MCP 工具动辄几百毫秒到几秒（要跟子进程通信，甚至去操作别的软件），
        内联执行会把事件泵按住 —— 那段时间下行音频断流、上行帧堆到背压阈值、
        用户打断也没人响应。派发出去之后事件泵立刻回去收包。
        """
        items = msg.get("items") or []
        if isinstance(items, dict):
            items = [items]
        items = [item for item in items if isinstance(item, dict)]
        if not items:
            return
        try:
            self.tool_executor.submit(self._run_function_calls, items)
        except RuntimeError:
            # 线程池已经关了（会话正在收尾），结果不会有人要，别改成同步执行拖住收尾
            pass

    def _run_function_calls(self, items):
        """在线程池里执行一批工具调用，把结果连同 response.create 一起发回上游。"""
        items_to_send = []
        for item in items:
            call_id = item.get("call_id")
            name = item.get("name") or (item.get("function") or {}).get("name")
            raw_args = item.get("arguments")
            if raw_args is None:
                raw_args = (item.get("function") or {}).get("arguments")
            if raw_args is None:
                raw_args = "{}"
            try:
                arguments = json.loads(raw_args) if isinstance(raw_args, str) else (raw_args or {})
                if not isinstance(arguments, dict):
                    raise ValueError("arguments 必须是 JSON 对象")
            except Exception as exc:
                items_to_send.append(self._tool_result_item(
                    call_id, name, raw_args, {"ok": False, "error": f"arguments 解析失败: {exc}"}))
                continue
            if self._needs_confirmation(name):
                items_to_send.append(self._request_confirmation(call_id, name, raw_args, arguments))
            else:
                items_to_send.append(self._tool_result_item(
                    call_id, name, raw_args, self._execute_tool(name, arguments)))
        self._deliver_tool_items(items_to_send)

    def _execute_tool(self, name, arguments):
        """按名字前缀路由：`mcp__` 开头走 MCP，其余交给内置文件工具。"""
        try:
            if self.mcp and self.mcp.find(name):
                return self.mcp.call(name, arguments, timeout=MCP_TOOL_TIMEOUT_SECONDS)
            return self.tools.call(name, arguments)
        except Exception as exc:
            return {"ok": False, "error": str(exc)}

    def _tool_result_item(self, call_id, name, raw_args, result):
        """构造回灌用的 tool 消息，顺带把结果同步到前端事件面板（排查全靠它）。"""
        self.emit("local.tool_result", {
            "call_id": call_id, "name": name, "arguments": raw_args, "result": result,
        })
        return {
            "call_id": call_id,
            "role": "tool",
            "content": [{"type": "input_text", "text": json.dumps(result, ensure_ascii=False, separators=(",", ":"))}],
        }

    def _deliver_tool_items(self, items):
        """把工具结果发回上游，并**显式要一次模型开口**。

        这一步以前是缺的：`role="tool"` 的消息本身不会触发新一轮生成，
        所以模型拿到结果后并不会说话 —— 整条 function calling 链路看起来像"没反应"。
        send_text / commit_audio 里那句 response.create 是同一个道理。
        """
        if not items:
            return
        if self.ws is None or self.closed.is_set() or self.close_requested.is_set():
            return
        for item in items:
            payload = {"type": "conversation.item.create", "item": item}
            self.emit("local.outgoing", {"event": payload})
            try:
                self.ws.send_json(payload)
            except Exception as exc:
                self.emit("local.warning", {"message": f"回灌工具结果失败: {exc}"})
                return
        try:
            self.ws.send_json({"type": "response.create"})
        except Exception as exc:
            self.emit("local.warning", {"message": f"请求模型继续失败: {exc}"})

    # ---------------- 危险工具的二次确认 ----------------

    def _needs_confirmation(self, name):
        """只有 MCP 工具可能对外部世界产生不可逆影响；内置文件工具全是只读查询。"""
        if not name or not self.mcp:
            return False
        record = self.mcp.find(name)
        return bool(record) and not record.get("read_only")

    def _request_confirmation(self, call_id, name, raw_args, arguments):
        """挂起这次调用，请用户在界面上确认。

        语音场景格外需要这道闸：VAD 误判、背景人声、电视里的对白都可能触发工具调用，
        模型自己不会替你踩刹车。
        """
        token = secrets.token_urlsafe(12)
        timer = threading.Timer(TOOL_CONFIRM_TIMEOUT_SECONDS, self._expire_confirmation, args=(token,))
        timer.daemon = True
        with self.confirm_lock:
            self.pending_confirms[token] = {
                "token": token,
                "call_id": call_id,
                "name": name,
                "raw_args": raw_args,
                "arguments": arguments,
                "timer": timer,
            }
        timer.start()
        self.emit("local.tool_confirm_request", {
            "session_id": self.id,
            "token": token,
            "call_id": call_id,
            "name": name,
            "arguments": raw_args,
            "expires_in": TOOL_CONFIRM_TIMEOUT_SECONDS,
        })
        return {
            "call_id": call_id,
            "role": "tool",
            "content": [{"type": "input_text", "text": json.dumps({
                "ok": False,
                "pending_confirmation": True,
                "message": f"工具 {name} 会修改外部状态，已经请用户在界面上确认，需要等用户操作。",
            }, ensure_ascii=False, separators=(",", ":"))}],
        }

    def resolve_confirmation(self, token, approve):
        with self.confirm_lock:
            pending = self.pending_confirms.pop(str(token or ""), None)
        if not pending:
            raise ValueError("确认请求不存在或已过期")
        timer = pending.get("timer")
        if timer:
            timer.cancel()
        self.emit("local.tool_confirm_resolved", {"token": pending["token"], "approved": bool(approve)})
        if not approve:
            self._deliver_tool_items([self._tool_result_item(
                pending["call_id"], pending["name"], pending["raw_args"],
                {"ok": False, "denied": True, "message": "用户拒绝执行这次工具调用。"})])
            return {"executed": False}
        try:
            self.tool_executor.submit(self._finish_confirmed, pending)
        except RuntimeError:
            self._finish_confirmed(pending)
        return {"executed": True}

    def _finish_confirmed(self, pending):
        result = self._execute_tool(pending["name"], pending["arguments"])
        self._deliver_tool_items([self._tool_result_item(
            pending["call_id"], pending["name"], pending["raw_args"], result)])

    def _expire_confirmation(self, token):
        with self.confirm_lock:
            pending = self.pending_confirms.pop(str(token or ""), None)
        if not pending:
            return
        self.emit("local.tool_confirm_expired", {"token": pending["token"], "name": pending["name"]})
        self._deliver_tool_items([self._tool_result_item(
            pending["call_id"], pending["name"], pending["raw_args"],
            {"ok": False, "expired": True, "message": "等待用户确认超时，已经取消这次调用。"})])

    def _cancel_pending_confirms(self):
        with self.confirm_lock:
            pendings = list(self.pending_confirms.values())
            self.pending_confirms.clear()
        for pending in pendings:
            timer = pending.get("timer")
            if timer:
                timer.cancel()

    def _wait_ready(self):
        if not self.ready.wait(timeout=15) or self.closed.is_set() or self.close_requested.is_set() or not self.ws:
            raise RuntimeError("upstream session is not ready")

    def send_text(self, text):
        self._wait_ready()
        payload = {
            "type": "conversation.item.create",
            "item": {
                "type": "message",
                "role": "user",
                "content": [{"type": "input_text", "text": text}],
            },
        }
        self.emit("local.outgoing", {"event": payload})
        self.ws.send_json(payload)
        # 文本输入不会触发 VAD，必须显式要求模型开口。
        self.ws.send_json({"type": "response.create"})

    def send_audio(self, audio_b64):
        self._wait_ready()
        self.ws.send_json({"type": "input_audio_buffer.append", "audio": audio_b64})

    def commit_audio(self):
        """强制判停：提交缓冲区并主动要求模型开口（VAD 没判出停顿时的兜底）。"""
        self._wait_ready()
        self.ws.send_json({"type": "input_audio_buffer.commit"})
        self.ws.send_json({"type": "response.create"})

    def _remember_sent_payload(self, payload):
        with self.state_lock:
            self.sent_state = {
                "session": copy.deepcopy(payload.get("session", {})),
                "extension": copy.deepcopy(payload.get("extension", {})),
            }

    def _send_update_payload(self, payload):
        with self.state_lock:
            patch = {key: value for key, value in payload.items() if key in {"session", "extension"}}
            diff = diff_patch_paths(self.sent_state, patch)
            if not diff:
                return
            self.sent_state = deep_merge(self.sent_state, patch)
        self.emit("local.update_diff", {"event": payload, "diff": diff})
        self.emit("local.outgoing", {"event": payload})
        self.ws.send_json(payload)

    def update_runtime_config(self, config):
        self._wait_ready()
        self.config.update(config)
        if "tool_root" in config:
            self.tools = LocalFileTools(self.config["tool_root"])
        session = {"instructions": self._instructions()}
        if "voice" in config:
            session["voice"] = self.config.get("voice") or DEFAULT_VOICE
        if any(key in config for key in ("enable_tools", "tool_root", "tools")):
            tools = self._configured_tools()
            session["tools"] = tools
        self._send_update_payload({"type": "session.update", "session": session})

    def close(self):
        """主动结束会话：直接断开连接，不发结束事件。

        平台给了两种收尾方式（官方实时文档：发 `session.finish`，或直接断开 WebSocket）。
        实测本端点**不认 `session.finish`** —— 回的是
        `invalid_request_error / invalid_value / param: type`，客户端的「事件参考」页
        也确实没把这个事件列进客户端事件表。所以走断开连接这条路。

        之前的写法是发 `session.finish`，指望上游回一个 error 把读循环顶出去 ——
        挂断能work纯属巧合，副作用是每次挂断都弹一条「上游返回错误」。
        """
        self.close_requested.set()
        if self.ws:
            # 关掉连接，把阻塞在 recv 的读线程唤醒，_run 随即走 finally 正常收尾
            self.ws.close()
        else:
            # 还没连上（或握手就失败了），别让调用方等
            self.closed.set()
            self.ready.set()


class DemoState:
    """会话注册表。

    会话对象持有事件队列、上游连接和工具配置，通话结束后必须摘除，
    否则反复通话的服务内存只会一直涨。
    摘除不放在 close() 里直接做 —— 前端 SSE 可能还在读最后几个事件
    （包括报错详情），所以要留一段保留期，由后台线程按 TTL 回收。
    """

    SESSION_TTL_SECONDS = 120
    REAP_INTERVAL_SECONDS = 30

    def __init__(self):
        self.sessions = {}
        self.lock = threading.Lock()
        threading.Thread(target=self._reap_loop, daemon=True).start()

    def create(self, config):
        session = DuplexSession(config)
        with self.lock:
            self.sessions[session.id] = session
        return session

    def get(self, session_id):
        with self.lock:
            return self.sessions.get(session_id)

    def close(self, session_id):
        session = self.get(session_id)
        if session:
            session.close()
        return session

    def reap(self, now=None):
        """摘掉已结束且过了保留期的会话，返回被摘掉的 id 列表。

        只认 finished_at（在 _run 的 finally 里和 closed 一起设），
        所以永远不会摘到一个还没跑完的会话。
        """
        now = time.time() if now is None else now
        with self.lock:
            stale = [
                sid for sid, session in self.sessions.items()
                if session.finished_at is not None
                and now - session.finished_at >= self.SESSION_TTL_SECONDS
            ]
            for sid in stale:
                self.sessions.pop(sid, None)
        return stale

    def _reap_loop(self):
        while True:
            time.sleep(self.REAP_INTERVAL_SECONDS)
            try:
                self.reap()
            except Exception:
                # 回收失败不该拖垮整个服务，下一轮再来
                pass


STATE = DemoState()
# MCP 工具注册表是进程级的：服务进程只拉起一次 MCP 子进程，
# 所有会话共用同一批工具（否则每通电话都 fork 一堆子进程，既慢又乱）。
MCP = mcp_client.McpToolRegistry()


def configure_mcp(config_path, logger=print):
    """按配置文件启动 MCP 服务；不传路径就不启用 MCP。

    单个 MCP 服务起不来只记一笔失败，不能让整个 demo 起不来 ——
    工具是加分项，不该把主链路拖下水。
    """
    global MCP
    if config_path:
        MCP = mcp_client.load_config(config_path, logger=logger)
        MCP.start_all()
    return MCP


class QuietThreadingHTTPServer(ThreadingHTTPServer):
    def handle_error(self, request, client_address):
        exc_type, exc, _ = __import__("sys").exc_info()
        if exc_type in (ConnectionResetError, BrokenPipeError):
            return
        super().handle_error(request, client_address)


class Handler(SimpleHTTPRequestHandler):
    server_version = "QwenOmniWebDemo/0.1"

    def do_POST(self):
        parsed = urlparse(self.path)
        try:
            if parsed.path == "/api/session":
                self._create_session()
            elif parsed.path == "/api/session_update":
                self._update_session()
            elif parsed.path == "/api/text":
                self._send_text()
            elif parsed.path == "/api/audio":
                self._send_audio()
            elif parsed.path == "/api/audio_commit":
                self._commit_audio()
            elif parsed.path == "/api/close":
                self._close_session()
            elif parsed.path == "/api/tool_confirm":
                self._tool_confirm()
            else:
                self.send_error(HTTPStatus.NOT_FOUND)
        except Exception as exc:
            self._json({"ok": False, "error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/events":
            self._events(parsed)
        elif parsed.path == "/api/mcp_status":
            self._json({"ok": True, **MCP.status()})
        else:
            super().do_GET()

    def _body(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        return json.loads(raw.decode("utf-8") or "{}")

    def _normalize_tools_config(self, data, current=None):
        current = current or {}
        enable_tools = truthy_value(data.get("enable_tools", current.get("enable_tools", True)))
        tool_root = str(data.get("tool_root") or current.get("tool_root") or "").strip()
        if not tool_root:
            tool_root = str(default_tool_root())
        root = Path(tool_root).expanduser().resolve()
        if not root.exists() or not root.is_dir():
            raise ValueError("tool_root must be an existing directory")
        tools = data["tools"] if "tools" in data else current.get("tools")
        if tools is not None and not isinstance(tools, list):
            raise ValueError("tools must be a JSON array")
        return {
            "enable_tools": enable_tools,
            "tool_root": str(root),
            "tools": tools,
        }

    def _create_session(self):
        data = self._body()
        api_key = str(data.get("api_key") or "").strip() or os.environ.get("DASHSCOPE_API_KEY", "").strip()
        if not api_key:
            raise ValueError("api_key is required")
        tools_config = self._normalize_tools_config(data)
        data.update(tools_config)
        data["api_key"] = api_key
        data["instructions"] = sanitize_instructions(data.get("instructions"))
        data["upstream_headers"] = self._forwarded_upstream_headers()
        data["mcp_registry"] = MCP
        session = STATE.create(data)
        self._json({
            "ok": True,
            "session_id": session.id,
            "tool_root": tools_config["tool_root"],
            "mcp": MCP.status(),
        })

    def _forwarded_upstream_headers(self):
        blocked = {"authorization", "x-api-key", "x-api-connect-id"}
        forwarded = {}
        for key, value in self.headers.items():
            name = str(key).strip()
            lower = name.lower()
            if lower.startswith("x-") and lower not in blocked:
                forwarded[name] = str(value)
        return forwarded

    def _update_session(self):
        data = self._body()
        session = STATE.get(data.get("session_id"))
        if not session:
            raise ValueError("session not found")
        update = {
            "model": str(data.get("model") or DEFAULT_MODEL).strip(),
            "instructions": sanitize_instructions(data.get("instructions")),
        }
        if any(key in data for key in ("enable_tools", "tool_root", "tools")):
            update.update(self._normalize_tools_config(data, session.config))
        session.update_runtime_config(update)
        self._json({"ok": True})

    def _send_text(self):
        data = self._body()
        session = STATE.get(data.get("session_id"))
        if not session:
            raise ValueError("session not found")
        text = str(data.get("text", "")).strip()
        if not text:
            raise ValueError("text is required")
        session.send_text(text)
        self._json({"ok": True})

    def _send_audio(self):
        data = self._body()
        session = STATE.get(data.get("session_id"))
        if not session:
            raise ValueError("session not found")
        audio = str(data.get("audio", "")).strip()
        if not audio:
            raise ValueError("audio is required")
        session.send_audio(audio)
        self._json({"ok": True})

    def _commit_audio(self):
        data = self._body()
        session = STATE.get(data.get("session_id"))
        if not session:
            raise ValueError("session not found")
        session.commit_audio()
        self._json({"ok": True})

    def _close_session(self):
        data = self._body()
        STATE.close(data.get("session_id"))
        self._json({"ok": True})

    def _tool_confirm(self):
        """用户对危险工具调用的确认/拒绝。

        这一步刻意走 HTTP 而不是让模型自己"同意" —— 语音场景里 VAD 误判、
        背景人声都可能触发工具调用，真正的授权必须来自人的一次明确操作。
        """
        data = self._body()
        session = STATE.get(data.get("session_id"))
        if not session:
            raise ValueError("session not found")
        token = str(data.get("token") or "").strip()
        if not token:
            raise ValueError("token is required")
        result = session.resolve_confirmation(token, truthy_value(data.get("approve", False)))
        self._json({"ok": True, **result})

    def _events(self, parsed):
        qs = parse_qs(parsed.query)
        session = STATE.get((qs.get("session_id") or [""])[0])
        if not session:
            self.send_error(HTTPStatus.NOT_FOUND, "session not found")
            return
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        while True:
            try:
                event = session.events.get(timeout=15)
            except queue.Empty:
                event = {"type": "local.ping", "ts": time.time(), "nonce": secrets.token_hex(4)}
            blob = f"data: {json.dumps(event, ensure_ascii=False)}\n\n".encode("utf-8")
            try:
                self.wfile.write(blob)
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                return
            if event.get("type") == "local.closed":
                return

    def _json(self, payload, status=HTTPStatus.OK):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        message = fmt % args
        if "/api/audio " in message or "/api/audio_commit " in message:
            return
        print("[%s] %s" % (self.log_date_time_string(), message), flush=True)


def main():
    parser = argparse.ArgumentParser(description="Dependency-free Qwen3-Omni realtime web demo with local file function calling and pluggable MCP tools.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--static-dir", default=str(Path(__file__).with_name("static")))
    parser.add_argument(
        "--mcp-config",
        default=os.environ.get("QWEN_OMNI_MCP_CONFIG", ""),
        help="MCP 服务配置文件（JSON），留空则不启用 MCP 工具；也可用环境变量 QWEN_OMNI_MCP_CONFIG 指定",
    )
    args = parser.parse_args()

    registry = configure_mcp(args.mcp_config)

    handler = functools.partial(Handler, directory=args.static_dir)
    server = QuietThreadingHTTPServer((args.host, args.port), handler)
    print(f"Serving web demo at http://{args.host}:{args.port}", flush=True)
    print(f"Upstream: {upstream_ws_url(DEFAULT_MODEL)}", flush=True)
    print(f"Default local file tool root: {default_tool_root()}", flush=True)
    if registry.servers:
        print(f"MCP: {len(registry.tools)} 个工具，来自 {len(registry.servers)} 个服务", flush=True)
        for failure in registry.failures:
            print(f"MCP 服务启动失败: {failure['name']}: {failure['error']}", flush=True)
    else:
        print("MCP: 未启用（加 --mcp-config 可挂载外部工具）", flush=True)
    try:
        server.serve_forever()
    finally:
        registry.close()


if __name__ == "__main__":
    main()
