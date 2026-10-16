"""Opt-in live smoke test. Creates a billable provider session, no microphone capture.

Usage: python -m scripts.probe_realtime --provider qwen [--text-test]
Credentials are read from .env; never printed, even on authentication failures.
"""
import argparse
import asyncio
import json
from app.config import Config
from app.providers.realtime import RealtimeProvider


async def probe(name, text_test):
    config = Config()
    provider = RealtimeProvider(name, config)
    report = {"provider": name, "session": False, "audio_bytes": 0, "text_events": 0}
    try:
        await provider.open("你是一个语音测试助手。只用中文回复：你好，我是小聆。")
        report["session"] = True
        if text_test:
            await provider.text("你好，请说一句简短的问候。")
            async with asyncio.timeout(30):
                async for event in provider.events():
                    if event["type"] == "audio":
                        report["audio_bytes"] += len(event["pcm"])
                    elif event["type"] == "assistant.delta":
                        report["text_events"] += 1
                    elif event["type"] == "response.done":
                        break
    except Exception as error:
        report["error_type"] = type(error).__name__
        response = getattr(error, "response", None)
        if response is not None:
            report["http_status"] = response.status_code
    finally:
        await provider.close()
    print(json.dumps(report, ensure_ascii=False))
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--provider", choices=["doubao", "qwen"], required=True)
    parser.add_argument("--text-test", action="store_true")
    args = parser.parse_args()
    asyncio.run(probe(args.provider, args.text_test))
