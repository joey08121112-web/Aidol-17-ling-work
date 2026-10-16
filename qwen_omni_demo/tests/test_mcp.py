#!/usr/bin/env python3
"""mcp_client 的端到端测试：跑真进程、说真协议，不打 mock。"""

import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
FAKE = os.path.join(HERE, "fake_mcp_server.py")
sys.path.insert(0, PROJECT)

import mcp_client  # noqa: E402

PYTHON = sys.executable

PASS = 0
FAIL = 0


def check(label, condition, detail=""):
    global PASS, FAIL
    if condition:
        PASS += 1
        print(f"  PASS  {label}")
    else:
        FAIL += 1
        print(f"  FAIL  {label}  {detail}")


def make_registry(extra_args=None, timeout=10):
    registry = mcp_client.McpToolRegistry(logger=lambda *_: None)
    registry.add({
        "name": "fake",
        "command": PYTHON,
        "args": [FAKE, "--name", "fake-demo"] + list(extra_args or []),
        "timeout": timeout,
    })
    return registry


print("== 1. 握手与工具发现 ==")
registry = make_registry()
registry.start_all()
check("没有启动失败", registry.failures == [], registry.failures)
check("服务端信息被读到", registry.servers["fake"].server_info.get("name") == "fake-demo",
      registry.servers["fake"].server_info)
check("发现 4 个工具", len(registry.tools) == 4, len(registry.tools))

prefixed = sorted(record["prefixed"] for record in registry.tools)
check("工具名带服务前缀", prefixed == [
    "mcp__fake__boom", "mcp__fake__read_note",
    "mcp__fake__slow_echo", "mcp__fake__write_note",
], prefixed)

print("== 2. 只读判定（决定是否需要二次确认）==")
check("readOnlyHint=true 判为只读", registry.find("mcp__fake__read_note")["read_only"] is True)
check("无 annotations 判为可写", registry.find("mcp__fake__write_note")["read_only"] is False)
check("slow_echo 判为只读", registry.find("mcp__fake__slow_echo")["read_only"] is True)

print("== 3. schema 转换 ==")
schemas = {row["name"]: row for row in registry.schemas()}
check("schema 数量一致", len(schemas) == 4, len(schemas))
sample = schemas.get("mcp__fake__read_note") or {}
check("type 为 function", sample.get("type") == "function", sample.get("type"))
check("parameters 是对象 schema", isinstance(sample.get("parameters"), dict)
      and sample["parameters"].get("type") == "object")
check("描述里标注来源", "MCP 服务 fake" in sample.get("description", ""))
check("只读工具描述说不需确认", "只读操作" in sample.get("description", ""))
check("可写工具描述说要确认", "需要用户确认" in schemas.get("mcp__fake__write_note", {}).get("description", ""))

print("== 4. 工具调用 ==")
out = registry.call("mcp__fake__read_note", {"path": "a.txt"})
check("只读调用成功", out.get("ok") is True, out)
check("文本内容回传正确", "hello from fake mcp" in out.get("text", ""), out)
check("回传来源信息", out.get("server") == "fake" and out.get("tool") == "read_note", out)

out = registry.call("mcp__fake__write_note", {"path": "b.txt", "content": "hi"})
check("可写调用成功", out.get("ok") is True, out)
check("写入内容回传正确", "wrote" in out.get("text", ""), out)

out = registry.call("mcp__fake__boom", {})
check("服务端报错转成 ok=False", out.get("ok") is False, out)
check("错误文本回传", "intentional failure" in out.get("text", ""), out)

out = registry.call("mcp__fake__does_not_exist", {})
check("未知工具名返回 ok=False", out.get("ok") is False and "unknown mcp tool" in out.get("error", ""), out)

print("== 5. 超时（关键：不能让慢工具拖死调用方）==")
started = time.time()
out = registry.call("mcp__fake__slow_echo", {"seconds": 3}, timeout=1)
elapsed = time.time() - started
check("超时返回 ok=False", out.get("ok") is False, out)
check("超时文案可读", "超时" in out.get("error", ""), out.get("error"))
check(f"1 秒左右就返回（实际 {elapsed:.2f}s）", elapsed < 2.5, elapsed)

print("== 6. 连接仍可用（超时没把整条连接搞坏）==")
out = registry.call("mcp__fake__read_note", {"path": "c.txt"})
check("超时后仍能正常调用", out.get("ok") is True, out)

print("== 7. 关闭 ==")
registry.close()
check("close 幂等", (registry.close() or True))

print("== 8. 握手失败要能被容忍 ==")
bad = make_registry(extra_args=["--fail-handshake"])
bad.start_all()
check("握手失败被记录", len(bad.failures) == 1, bad.failures)
check("失败原因指向 initialize", "initialize" in bad.failures[0]["error"], bad.failures)
check("失败时工具为空", bad.tools == [], bad.tools)
out = bad.call("mcp__fake__read_note", {})
check("失败后调用返回 ok=False", out.get("ok") is False, out)
bad.close()

print("== 9. 命令不存在也不能崩 ==")
missing = mcp_client.McpToolRegistry(logger=lambda *_: None)
missing.add({"name": "ghost", "command": "definitely-not-a-real-binary-xyz"})
missing.start_all()
check("命令不存在被记录为失败", len(missing.failures) == 1, missing.failures)
missing.close()

print("== 10. 工具名长度与去重 ==")


class _Stub:
    name = "s"


stub = _Stub()
long_registry = mcp_client.McpToolRegistry(logger=lambda *_: None)
long_registry._register(stub, {"name": "x" * 200, "description": "", "inputSchema": {}})
long_registry._register(stub, {"name": "x" * 200, "description": "", "inputSchema": {}})
names = [record["prefixed"] for record in long_registry.tools]
check("超长工具名被截断到 64 以内", all(len(n) <= 64 for n in names), [len(n) for n in names])
check("截断后仍然唯一", len(set(names)) == 2, names)

print(f"\n结果：{PASS} PASS / {FAIL} FAIL")
sys.exit(1 if FAIL else 0)
