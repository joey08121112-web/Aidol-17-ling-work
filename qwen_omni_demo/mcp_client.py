#!/usr/bin/env python3
"""零依赖 MCP（Model Context Protocol）客户端。

只实现本 demo 需要的最小子集，全部基于 Python 标准库：

    传输层    stdio，换行分隔的 JSON-RPC 2.0
    生命周期  initialize -> notifications/initialized -> tools/list | tools/call

为什么不引第三方包：本工程有「后端零依赖」的硬约束（连 WebSocket 都是手写的
RFC6455 客户端），MCP 客户端同理手写。这一层不 import server.py，可以单独测。

一个 MCP 服务就是一个子进程：我们通过它的 stdin 发请求，从 stdout 读回复，
stderr 单独抽干（不抽的话管道写满会把它卡死）。
"""

import json
import os
import queue
import re
import shutil
import subprocess
import threading

DEFAULT_TIMEOUT = 30.0
PROTOCOL_VERSION = "2024-11-05"
CLIENT_INFO = {"name": "qwen-omni-web-demo", "version": "0.1"}

# 工具名统一加前缀，避免不同 MCP 服务之间撞名，也让反向路由不用查表猜测。
TOOL_PREFIX = "mcp__"
# function calling 的工具名通常限制在 64 字符以内，超了就截断并另记别名。
MAX_TOOL_NAME = 64

_INVALID_NAME_CHARS = re.compile(r"[^A-Za-z0-9_-]")


class McpError(RuntimeError):
    pass


def sanitize_name_part(value):
    cleaned = _INVALID_NAME_CHARS.sub("_", str(value or "").strip())
    return cleaned or "unknown"


def content_to_text(result):
    """把 MCP `tools/call` 的返回压成一段纯文本，交给模型阅读。

    MCP 返回的是 content 块数组（text / image / resource…）。语音场景下模型
    只能念文本，所以除了文本块一律退化成 JSON 字符串，不做特殊渲染。
    """
    if not isinstance(result, dict):
        return json.dumps(result, ensure_ascii=False)
    parts = []
    for block in result.get("content") or []:
        if not isinstance(block, dict):
            parts.append(str(block))
            continue
        block_type = block.get("type")
        if block_type == "text":
            parts.append(str(block.get("text") or ""))
        elif block_type == "resource":
            resource = block.get("resource") or {}
            parts.append(str(resource.get("text") or resource.get("uri") or ""))
        else:
            parts.append(json.dumps(block, ensure_ascii=False))
    text = "\n".join(part for part in parts if part)
    if not text and result.get("structuredContent") is not None:
        text = json.dumps(result["structuredContent"], ensure_ascii=False)
    return text


class McpStdioServer:
    """单个 MCP 服务子进程的连接。

    请求/响应靠 id 配对：每条带 id 的请求挂一个容量为 1 的队列，
    读线程收到同 id 的回复就塞回去，发请求的线程在队列上等。
    """

    def __init__(self, name, command, args=None, env=None, cwd=None, timeout=DEFAULT_TIMEOUT, logger=print):
        self.name = name
        self.command = command
        self.args = [str(item) for item in (args or [])]
        self.env = dict(env or {})
        self.cwd = cwd
        self.timeout = float(timeout or DEFAULT_TIMEOUT)
        self.logger = logger

        self.proc = None
        self.server_info = {}
        self.failed_reason = None

        self._pending = {}
        self._pending_lock = threading.Lock()
        self._write_lock = threading.Lock()
        self._next_id = 0
        self._id_lock = threading.Lock()
        self._reader = None
        self._stderr_reader = None

    # ---------------- 生命周期 ----------------

    def start(self):
        self.proc = subprocess.Popen(
            self._build_command(),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            cwd=self.cwd,
            env=self._build_env(),
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        self._reader = threading.Thread(
            target=self._read_stdout, args=(self.proc.stdout,),
            daemon=True, name=f"mcp-out-{self.name}",
        )
        self._reader.start()
        self._stderr_reader = threading.Thread(
            target=self._drain_stream, args=(self.proc.stderr, "stderr"),
            daemon=True, name=f"mcp-err-{self.name}",
        )
        self._stderr_reader.start()
        self._handshake()
        return self

    def _build_command(self):
        executable = shutil.which(self.command) or self.command
        # Windows 上 .cmd / .bat 不能直接交给 CreateProcess，得经 cmd.exe 转一手。
        if os.name == "nt" and executable.lower().endswith((".cmd", ".bat")):
            comspec = os.environ.get("COMSPEC") or "cmd.exe"
            return [comspec, "/d", "/c", executable] + self.args
        return [executable] + self.args

    def _build_env(self):
        env = dict(os.environ)
        env.update({str(key): str(value) for key, value in self.env.items()})
        # MCP 服务通常要求把日志写到 stderr，stdout 只放协议消息。
        env.setdefault("PYTHONUNBUFFERED", "1")
        return env

    def _handshake(self):
        result = self._request("initialize", {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": CLIENT_INFO,
        })
        self.server_info = (result or {}).get("serverInfo") or {}
        self._notify("notifications/initialized")

    # ---------------- 收发 ----------------

    def _write(self, payload):
        if not self.proc or self.proc.stdin is None:
            raise McpError(f"MCP 服务 {self.name} 未运行")
        line = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        with self._write_lock:
            try:
                self.proc.stdin.write(line + "\n")
                self.proc.stdin.flush()
            except (BrokenPipeError, ValueError, OSError) as exc:
                raise McpError(f"MCP 服务 {self.name} 的 stdin 已关闭: {exc}") from exc

    def _notify(self, method, params=None):
        payload = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            payload["params"] = params
        self._write(payload)

    def _request(self, method, params, timeout=None):
        if self.failed_reason:
            raise McpError(self.failed_reason)
        if not self.proc or self.proc.poll() is not None:
            raise McpError(f"MCP 服务 {self.name} 进程已退出")
        box = queue.Queue(maxsize=1)
        with self._id_lock:
            self._next_id += 1
            request_id = self._next_id
        with self._pending_lock:
            self._pending[request_id] = box
        try:
            self._write({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}})
            try:
                message = box.get(timeout=timeout or self.timeout)
            except queue.Empty:
                raise McpError(f"MCP {self.name} 的 {method} 超时（{timeout or self.timeout}s）")
        finally:
            with self._pending_lock:
                self._pending.pop(request_id, None)
        if isinstance(message, Exception):
            raise McpError(f"MCP {self.name} {method} 失败: {message}")
        if message.get("error"):
            error = message["error"] or {}
            raise McpError(f"MCP {self.name} {method} 失败: {error.get('message') or error}")
        return message.get("result")

    def _read_stdout(self, stream):
        try:
            for line in stream:
                line = line.strip()
                if not line:
                    continue
                try:
                    message = json.loads(line)
                except json.JSONDecodeError:
                    # 有些实现会把日志混到 stdout，跳过即可，别让整条连接挂掉。
                    self.logger(f"[mcp:{self.name}] 忽略非 JSON 输出: {line[:200]!r}")
                    continue
                if not isinstance(message, dict):
                    continue
                request_id = message.get("id")
                if request_id is None:
                    continue
                with self._pending_lock:
                    box = self._pending.pop(request_id, None)
                if box is not None:
                    try:
                        box.put_nowait(message)
                    except queue.Full:
                        pass
        except Exception as exc:
            self.logger(f"[mcp:{self.name}] 读线程异常: {exc}")
        finally:
            self._fail_pending(f"MCP 服务 {self.name} 的输出流已关闭")

    def _drain_stream(self, stream, label):
        try:
            for line in stream:
                line = line.rstrip()
                if line:
                    self.logger(f"[mcp:{self.name} {label}] {line[:500]}")
        except Exception:
            pass

    def _fail_pending(self, reason):
        self.failed_reason = reason
        with self._pending_lock:
            boxes = list(self._pending.values())
            self._pending.clear()
        for box in boxes:
            try:
                box.put_nowait(McpError(reason))
            except queue.Full:
                pass

    # ---------------- 协议方法 ----------------

    def list_tools(self):
        result = self._request("tools/list", {})
        tools = (result or {}).get("tools") or []
        return [tool for tool in tools if isinstance(tool, dict) and tool.get("name")]

    def call_tool(self, name, arguments, timeout=None):
        return self._request("tools/call", {"name": name, "arguments": arguments or {}}, timeout=timeout)

    def close(self):
        proc, self.proc = self.proc, None
        if not proc:
            return
        try:
            if proc.stdin:
                proc.stdin.close()
        except Exception:
            pass
        try:
            proc.terminate()
        except Exception:
            pass
        try:
            proc.wait(timeout=3)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass
        self._fail_pending(f"MCP 服务 {self.name} 已关闭")


class McpToolRegistry:
    """把若干个 MCP 服务聚合成一层可插拔的工具。

    上层只需要拿 `schemas()` 去拼 session.update，再把模型点名调用的工具名丢回
    `call()`，完全不用关心背后是哪个服务、通过什么方式启动的。
    """

    def __init__(self, logger=print):
        self.logger = logger
        self.servers = {}
        self.tools = []
        self.failures = []
        self._by_prefixed = {}
        self._started = False

    # ---------------- 装配 ----------------

    def add(self, config):
        name = sanitize_name_part(config.get("name")) if config.get("name") else f"server{len(self.servers) + 1}"
        server = McpStdioServer(
            name=name,
            command=config["command"],
            args=config.get("args"),
            env=config.get("env"),
            cwd=config.get("cwd"),
            timeout=config.get("timeout") or DEFAULT_TIMEOUT,
            logger=self.logger,
        )
        server.auto_approve = bool(config.get("auto_approve"))
        server.read_only_tools = {str(item) for item in (config.get("read_only_tools") or [])}
        server.write_tools = {str(item) for item in (config.get("write_tools") or [])}
        self.servers[name] = server
        return server

    def start_all(self):
        """启动所有已登记的服务并拉取工具清单；单个失败不影响其余。"""
        self._started = True
        self.tools = []
        self.failures = []
        self._by_prefixed = {}
        for name, server in self.servers.items():
            try:
                server.start()
                listed = server.list_tools()
            except Exception as exc:
                self.failures.append({"name": name, "error": str(exc)})
                self.logger(f"[mcp:{name}] 启动失败: {exc}")
                server.close()
                continue
            for tool in listed:
                self._register(server, tool)
            self.logger(
                f"[mcp:{name}] 已就绪，工具 {len(listed)} 个，"
                f"服务端: {(server.server_info or {}).get('name') or 'unknown'}"
            )
        return self.tools

    def _register(self, server, tool):
        original = str(tool["name"])
        record = {
            "server": server,
            "server_name": server.name,
            "tool": original,
            "read_only": self._read_only(server, original, tool),
            "description": str(tool.get("description") or "").strip(),
            "parameters": tool.get("inputSchema") if isinstance(tool.get("inputSchema"), dict) else {"type": "object", "properties": {}},
        }
        record["prefixed"] = self._unique_name(f"{TOOL_PREFIX}{server.name}__{sanitize_name_part(original)}")
        self.tools.append(record)
        self._by_prefixed[record["prefixed"]] = record

    def _unique_name(self, base):
        """给工具名找一个唯一且不超长上限的名字。

        截断时必须把后缀的长度预留出来 —— 否则 `base_2` 截断后又变回 base，
        去重循环会永远转下去（踩过）。
        """
        candidate = base[:MAX_TOOL_NAME]
        suffix = 1
        while candidate in self._by_prefixed:
            suffix += 1
            tail = f"_{suffix}"
            candidate = base[:MAX_TOOL_NAME - len(tail)] + tail
        return candidate

    def _read_only(self, server, original, tool):
        """判定一个工具是否只读（只读才允许免确认直接执行）。

        优先级：显式白名单/黑名单 > MCP 的 readOnlyHint > 服务级 auto_approve > 默认要确认。
        默认必须确认是刻意的 —— 拿不准的工具宁可多问用户一句。
        """
        write_tools = getattr(server, "write_tools", None) or set()
        read_only_tools = getattr(server, "read_only_tools", None) or set()
        if original in write_tools:
            return False
        if original in read_only_tools:
            return True
        annotations = tool.get("annotations")
        if isinstance(annotations, dict) and annotations.get("readOnlyHint") is True:
            return True
        return bool(getattr(server, "auto_approve", False))

    # ---------------- 对外接口 ----------------

    def schemas(self):
        """转成 function calling 的 tools 数组。"""
        rows = []
        for record in self.tools:
            description = record["description"] or f"由 MCP 服务 {record['server_name']} 提供的工具。"
            note = f"（来源：MCP 服务 {record['server_name']}；" + (
                "只读操作，可直接调用）" if record["read_only"] else "会修改外部状态，执行前需要用户确认）"
            )
            rows.append({
                "type": "function",
                "name": record["prefixed"],
                "description": f"{description} {note}",
                "parameters": record["parameters"],
            })
        return rows

    def find(self, prefixed_name):
        return self._by_prefixed.get(str(prefixed_name or ""))

    def call(self, prefixed_name, arguments, timeout=None):
        """执行一个 MCP 工具，返回值统一成 `{"ok": bool, ...}`。

        内置工具（LocalFileTools）已经是这个形状，这里保持一致，
        这样上层 `_handle_function_call` 不用分两套分支处理结果。
        """
        record = self.find(prefixed_name)
        if not record:
            return {"ok": False, "error": f"unknown mcp tool: {prefixed_name}"}
        try:
            raw = record["server"].call_tool(record["tool"], arguments, timeout=timeout)
        except McpError as exc:
            return {"ok": False, "error": str(exc), "server": record["server_name"], "tool": record["tool"]}
        except Exception as exc:
            return {"ok": False, "error": str(exc), "server": record["server_name"], "tool": record["tool"]}
        text = content_to_text(raw)
        is_error = bool(isinstance(raw, dict) and raw.get("isError"))
        return {
            "ok": not is_error,
            "server": record["server_name"],
            "tool": record["tool"],
            "text": text,
        }

    def status(self):
        return {
            "servers": [
                {
                    "name": name,
                    "ready": server.proc is not None,
                    "tools": len([t for t in self.tools if t["server_name"] == name]),
                    "server_info": server.server_info,
                }
                for name, server in self.servers.items()
            ],
            "failures": list(self.failures),
        }

    def close(self):
        for server in self.servers.values():
            try:
                server.close()
            except Exception:
                pass


def load_config(path, logger=print):
    """从 JSON 文件读 MCP 服务配置。

    格式：
        {"servers": [{"name": "fs", "command": "npx",
                      "args": ["-y", "@modelcontextprotocol/server-filesystem", "."],
                      "auto_approve": false,
                      "read_only_tools": ["read_file", "list_directory"],
                      "write_tools": ["write_file"]}]}
    """
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    if isinstance(data, list):
        servers = data
    elif isinstance(data, dict):
        servers = data.get("servers") or []
    else:
        raise ValueError("MCP 配置必须是对象或数组")
    registry = McpToolRegistry(logger=logger)
    for entry in servers:
        if not isinstance(entry, dict) or not entry.get("command"):
            logger(f"[mcp] 跳过非法配置项: {entry!r}")
            continue
        registry.add(entry)
    return registry
