"""Provider protocol adapters based on the two supplied competition demos.

No vendor SDK or filesystem tools leak into the agent layer. All TLS validation
remains enabled. A new connection is used when switching providers.
"""
import asyncio
import base64
import json
import ssl
import time
from urllib.parse import urlencode
from uuid import uuid4

import truststore
from websockets.asyncio.client import connect


ALIASES = {
    "response.audio.delta": "response.output_audio.delta",
    "response.audio.done": "response.output_audio.done",
    "response.audio_transcript.delta": "response.output_audio_transcript.delta",
    "response.audio_transcript.done": "response.output_audio_transcript.done",
    "input_audio_buffer.speech_started": "conversation.item.input_audio_transcription.started",
}


class RealtimeProvider:
    def __init__(self, name, config):
        self.name, self.config = name, config
        self.ws = None
        self.backlog = []
        self.started = time.monotonic()
        self.first_audio = True

    async def open(self, instructions):
        cfg = self.config
        if self.name == "doubao":
            url = cfg.doubao_url
            headers = {"X-Api-Key": cfg.doubao_key, "X-Api-Connect-Id": uuid4().hex}
            payload = {"type": "session.create", "session": {
                "model": cfg.doubao_model, "instructions": instructions,
                "audio": {"input": {"format": {"type": "pcm", "sample_rate": 16000}},
                          "output": {"format": {"type": "pcm_s16le", "sample_rate": 24000}, "voice": cfg.doubao_voice}},
                "tools": []}, "extension": {"extra": {"enable_proactive_speak": False}}}
        else:
            url = cfg.qwen_url + ("&" if "?" in cfg.qwen_url else "?") + urlencode({"model": cfg.qwen_model})
            headers = {"Authorization": "Bearer " + cfg.qwen_key}
            payload = {"type": "session.update", "session": {
                "modalities": ["text", "audio"], "instructions": instructions, "voice": cfg.qwen_voice,
                "input_audio_format": "pcm16", "output_audio_format": "pcm24",
                "turn_detection": {"type": "server_vad", "threshold": 0.5, "prefix_padding_ms": 300,
                                   "silence_duration_ms": 800, "create_response": True, "interrupt_response": True}}}
        context = truststore.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        try:
            self.ws = await connect(url, additional_headers=headers, ssl=context,
                proxy=cfg.proxy, open_timeout=12, close_timeout=3, ping_interval=20,
                max_size=2 * 1024 * 1024, max_queue=32)
            await self.send(payload)
            # Authentication alone does not prove the requested model/session exists.
            async with asyncio.timeout(12):
                while True:
                    event = json.loads(await self.ws.recv())
                    if event.get("type") == "error":
                        raise RuntimeError("provider rejected session configuration")
                    self.backlog.append(event)
                    if event.get("type") in {"session.created", "session.updated"}:
                        if self.name == "doubao" or event.get("type") == "session.updated":
                            break
        except BaseException:
            await self.close()
            raise

    async def send(self, payload):
        await self.ws.send(json.dumps({"event_id": uuid4().hex, **payload}, ensure_ascii=False))

    async def audio(self, pcm):
        await self.send({"type": "input_audio_buffer.append", "audio": base64.b64encode(pcm).decode()})

    async def text(self, text):
        if self.name == "doubao":
            await self.send({"type": "speech_text_buffer.commit", "text": text})
        else:
            await self.send({"type": "conversation.item.create", "item": {
                "type": "message", "role": "user", "content": [{"type": "input_text", "text": text}]}})
            await self.send({"type": "response.create"})

    async def update(self, instructions):
        await self.send({"type": "session.update", "session": {"instructions": instructions}})

    async def events(self):
        while True:
            msg = self.backlog.pop(0) if self.backlog else json.loads(await self.ws.recv())
            kind = ALIASES.get(msg.get("type"), msg.get("type", "unknown"))
            if kind == "error":
                raise RuntimeError("upstream session error")
            if kind == "session.closed":
                return
            text = msg.get("transcript") or msg.get("text") or msg.get("delta") or ""
            if kind == "response.output_audio.delta":
                encoded = msg.get("delta") or msg.get("audio") or ""
                yield {"type": "audio", "pcm": base64.b64decode(encoded, validate=True)}
                if self.first_audio:
                    self.first_audio = False
                    yield {"type": "metric", "name": "session_to_first_audio_ms", "value": round((time.monotonic() - self.started) * 1000)}
            elif kind in {"conversation.item.input_audio_transcription.completed", "conversation.item.input_audio_transcription.done"}:
                yield {"type": "transcript", "text": text, "event_id": msg.get("item_id") or msg.get("event_id") or uuid4().hex}
            elif kind == "conversation.item.input_audio_transcription.delta":
                yield {"type": "transcript.delta", "text": text}
            elif kind == "conversation.item.input_audio_transcription.started":
                yield {"type": "interrupt"}
            elif kind in {"response.output_audio_transcript.delta", "response.output_text.delta", "response.text.delta"}:
                yield {"type": "assistant.delta", "text": text}
            elif kind in {"response.done", "response.output_audio.done"}:
                yield {"type": "response.done"}

    async def close(self):
        if self.ws is not None:
            await self.ws.close()
            self.ws = None


def candidates(config, preference):
    available = {"doubao": bool(config.doubao_key), "qwen": bool(config.qwen_key)}
    if preference == "demo":
        return []
    order = ["doubao", "qwen"] if preference == "auto" else [preference]
    return [name for name in order if available.get(name)]
