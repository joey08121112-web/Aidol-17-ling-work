#!/usr/bin/env python3
"""测试用的最小 MCP 服务（stdio 传输）。

它真的按 MCP 协议说话 —— 换行分隔的 JSON-RPC 2.0，所以能真正验证
mcp_client 的握手、tools/list、tools/call 和超时路径，而不是对着 mock 自欺欺人。

用法：python fake_mcp_server.py [--name NAME]
"""

import argparse
import json
import sys
import time

TOOLS = [
    {
        "name": "read_note",
        "description": "读取一条便签的内容",
        "inputSchema": {
            "type": "object",
            "properties": {"path": {"type": "string", "description": "便签名"}},
            "required": ["path"],
        },
        "annotations": {"readOnlyHint": True},
    },
    {
        "name": "write_note",
        "description": "写入一条便签",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "便签名"},
                "content": {"type": "string", "description": "内容"},
            },
            "required": ["path", "content"],
        },
    },
    {
        "name": "slow_echo",
        "description": "延迟若干秒后返回",
        "inputSchema": {
            "type": "object",
            "properties": {"seconds": {"type": "number"}},
        },
        "annotations": {"readOnlyHint": True},
    },
    {
        "name": "boom",
        "description": "故意失败",
        "inputSchema": {"type": "object", "properties": {}},
        "annotations": {"readOnlyHint": True},
    },
]


def send(payload):
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def call_tool(name, arguments):
    arguments = arguments or {}
    if name == "read_note":
        return {"ok": True, "text": f"note[{arguments.get('path')}] = hello from fake mcp"}
    if name == "write_note":
        return {"ok": True, "text": f"wrote {arguments.get('content')!r} to {arguments.get('path')!r}"}
    if name == "slow_echo":
        time.sleep(float(arguments.get("seconds") or 1))
        return {"ok": True, "text": "slow done"}
    if name == "boom":
        return {"ok": False, "error": "intentional failure"}
    return {"ok": False, "error": f"unknown tool {name}"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--name", default="fake")
    parser.add_argument("--fail-handshake", action="store_true")
    args = parser.parse_args()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue

        method = message.get("method")
        request_id = message.get("id")

        # 通知没有 id，不需要回
        if request_id is None:
            continue

        if args.fail_handshake and method == "initialize":
            send({"jsonrpc": "2.0", "id": request_id,
                  "error": {"code": -32600, "message": "handshake refused by test flag"}})
            continue

        if method == "initialize":
            send({"jsonrpc": "2.0", "id": request_id, "result": {
                "protocolVersion": message.get("params", {}).get("protocolVersion", "2024-11-05"),
                "capabilities": {"tools": {}},
                "serverInfo": {"name": args.name, "version": "0.1"},
            }})
        elif method == "tools/list":
            send({"jsonrpc": "2.0", "id": request_id, "result": {"tools": TOOLS}})
        elif method == "tools/call":
            params = message.get("params") or {}
            outcome = call_tool(params.get("name"), params.get("arguments"))
            if outcome.get("ok"):
                content = [{"type": "text", "text": outcome["text"]}]
                send({"jsonrpc": "2.0", "id": request_id,
                      "result": {"content": content, "isError": False}})
            else:
                send({"jsonrpc": "2.0", "id": request_id,
                      "result": {"content": [{"type": "text", "text": outcome["error"]}], "isError": True}})
        else:
            send({"jsonrpc": "2.0", "id": request_id,
                  "error": {"code": -32601, "message": f"method not found: {method}"}})


if __name__ == "__main__":
    main()
