#!/usr/bin/env python3
"""一键跑完本地能离线验证的全部测试。

    python tests/run_all.py          # 后端四项：不需要浏览器、不需要 API Key
    python tests/run_all.py --front  # 追加前端回归，会自动拉起 headless Edge

后端四项全部自包含：自己起假上游、假 MCP 服务，用独立端口，不碰你的 8765。

前端回归另需一个已经跑起来的 demo 服务：

    python server.py                 # 另开一个终端

脚本会在 9222 上没有调试端口时自动拉起 headless Edge，跑完再收掉；
如果 9222 已经在线（比如你自己开的），就复用，不会去动它。
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
DEMO_URL = "http://127.0.0.1:8765/"
CDP_URL = "http://127.0.0.1:9222/json/version"
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

BACKEND = ["test_mcp.py", "test_tool_flow.py", "test_close_path.py", "test_reap.py"]
FRONT = [("verify_all.js", "1440x900 浅色 主线回归"),
         ("verify_tool_confirm.js", "1440x900 浅色 二次确认卡片")]

# 本地回环请求一律绕开代理：系统代理开着时会把 127.0.0.1 也劫走，表现是莫名其妙的 502。
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def alive(url, timeout=1.5):
    try:
        with OPENER.open(url, timeout=timeout) as resp:
            return resp.status == 200
    except (urllib.error.URLError, OSError):
        return False


def find_node():
    found = shutil.which("node")
    if found:
        return found
    # 回退到 WorkBuddy 自带的 node
    bundled = Path(r"C:\Users\Lenovo\.workbuddy\binaries\node\versions\22.22.2-6\node.exe")
    return str(bundled) if bundled.exists() else None


def run_backend():
    results = []
    for script in BACKEND:
        path = HERE / script
        if not path.exists():
            results.append((script, None, 0.0))
            continue
        print(f"\n{'=' * 62}\n  {script}\n{'=' * 62}", flush=True)
        started = time.time()
        try:
            code = subprocess.run([sys.executable, "-u", script], cwd=str(HERE)).returncode
        except KeyboardInterrupt:
            raise
        results.append((script, code, time.time() - started))
    return results


def ensure_edge():
    """返回 (进程对象或 None, 临时 profile 或 None)。已在线的 9222 不归我们管。"""
    if alive(CDP_URL):
        print("  · 9222 已在线，复用现有 headless 浏览器")
        return None, None
    if not Path(EDGE).exists():
        raise RuntimeError(f"找不到 Edge：{EDGE}")
    profile = tempfile.mkdtemp(prefix="qwen_edge_")
    proc = subprocess.Popen(
        [EDGE, "--headless=new", "--disable-gpu", "--remote-debugging-port=9222",
         f"--user-data-dir={profile}", "about:blank"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    for _ in range(40):
        if alive(CDP_URL):
            print("  · 已拉起 headless Edge（9222）")
            return proc, profile
        if proc.poll() is not None:
            break
        time.sleep(0.5)
    proc.terminate()
    shutil.rmtree(profile, ignore_errors=True)
    raise RuntimeError("headless Edge 未能在 20 秒内就绪")


def run_front():
    if not alive(DEMO_URL):
        print(f"\n跳过前端回归：{DEMO_URL} 没有服务在跑。")
        print("先另开一个终端执行：  python server.py")
        return []

    node = find_node()
    if not node:
        print("\n跳过前端回归：PATH 里找不到 node。")
        return []

    print(f"\n{'=' * 62}\n  前端回归（node {subprocess.run([node, '--version'], capture_output=True, text=True).stdout.strip()}）\n{'=' * 62}")
    proc, profile = ensure_edge()
    results = []
    try:
        for script, label in FRONT:
            print(f"\n--- {script}  ({label}) ---", flush=True)
            started = time.time()
            done = subprocess.run(
                [node, "eval.mjs", DEMO_URL, "1440", "900", "light", script],
                cwd=str(HERE), capture_output=True, text=True, encoding="utf-8", errors="replace",
            )
            print(done.stdout or "(无输出)")
            if done.stderr:
                print(done.stderr)
            ok = "==> 全部通过" in (done.stdout or "")
            results.append((script, 0 if ok else 1, time.time() - started))
    finally:
        if proc:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
        if profile:
            shutil.rmtree(profile, ignore_errors=True)
    return results


def main():
    parser = argparse.ArgumentParser(description="跑完本项目的离线测试套件。")
    parser.add_argument("--front", action="store_true", help="追加前端回归（需要 demo 服务已启动）")
    args = parser.parse_args()

    started = time.time()
    results = run_backend()
    if args.front:
        results += run_front()

    print(f"\n{'=' * 62}\n  汇总\n{'=' * 62}")
    failed = 0
    for name, code, elapsed in results:
        if code is None:
            verdict = "跳过（文件不存在）"
        elif code == 0:
            verdict = "通过"
        else:
            verdict = f"失败（退出码 {code}）"
            failed += 1
        print(f"  {name:<26} {verdict:<20} {elapsed:5.1f}s")
    print(f"\n总计 {len(results)} 个套件，{failed} 个失败，用时 {time.time() - started:.1f}s")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
