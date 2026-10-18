"""Independent Volcano speech adapters: AUC HTTP, streaming ASR and v3 TTS.

Wire formats follow the user-supplied Java reference. These speech services use
their own resource credentials, separate from Doubao realtime conversation.
No API secrets or provider response bodies are written to logs or exceptions.
Audio boundaries are explicit: ASR streaming takes mono PCM16 little-endian;
TTS streaming yields mono PCM16, and synthesize_wav adds a browser-playable header.
"""
from __future__ import annotations

import asyncio
import base64
from dataclasses import dataclass, field
import gzip
import io
import json
import os
import ssl
import struct
from typing import AsyncIterable, AsyncIterator, Mapping
from urllib.parse import urlsplit
from uuid import uuid4
import wave

import httpx
import truststore
from websockets.asyncio.client import connect

from .http import HttpClientPool


class SpeechProviderError(RuntimeError):
    """Sanitized failure suitable for API status responses."""


@dataclass(frozen=True)
class VolcSpeechConfig:
    asr_app_key: str = field(default="", repr=False)
    asr_access_key: str = field(default="", repr=False)
    asr_api_key: str = field(default="", repr=False)
    asr_url: str = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    asr_resource_id: str = "volc.seedasr.sauc.duration"
    asr_flash_url: str = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"
    asr_flash_resource_id: str = "volc.bigasr.auc_turbo"
    asr_model: str = "bigmodel"
    tts_app_key: str = field(default="", repr=False)
    tts_access_key: str = field(default="", repr=False)
    tts_url: str = "wss://openspeech.bytedance.com/api/v3/tts/bidirection"
    # mars_bigtts is a 1.0 voice; pairing it with seed-tts-2.0 fails with 55000000.
    tts_resource_id: str = "seed-tts-1.0"
    tts_voice: str = "zh_female_jitangmeimei_mars_bigtts"
    input_rate: int = 16000
    output_rate: int = 24000
    timeout: float = 40.0
    proxy: str | None = None
    # Shared HTTP client pool (flash recognize); rides on the config so every
    # provider built from it reuses one connection pool per app instance.
    http_pool: "HttpClientPool" = field(default_factory=HttpClientPool, repr=False, compare=False)

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "VolcSpeechConfig":
        """Read environment already loaded by the app; never load files implicitly."""
        values = os.environ if env is None else env
        names = {
            "asr_app_key": "SPEECH_VOLC_APP_KEY", "asr_access_key": "SPEECH_VOLC_ACCESS_KEY",
            "asr_api_key": "SPEECH_VOLC_API_KEY", "asr_url": "SPEECH_VOLC_WS_URL",
            "asr_resource_id": "SPEECH_VOLC_RESOURCE_ID", "asr_model": "SPEECH_VOLC_MODEL_NAME",
            "asr_flash_url": "SPEECH_VOLC_FLASH_URL",
            "asr_flash_resource_id": "SPEECH_VOLC_FLASH_RESOURCE_ID",
            "tts_app_key": "TTS_VOLC_APP_KEY", "tts_access_key": "TTS_VOLC_ACCESS_KEY",
            "tts_url": "TTS_VOLC_WS_URL", "tts_resource_id": "TTS_VOLC_RESOURCE_ID",
            "tts_voice": "TTS_VOLC_VOICE_TYPE", "proxy": "PROVIDER_PROXY",
        }
        return cls(**{key: values[name].strip() for key, name in names.items() if values.get(name, "").strip()})

    @property
    def asr_ready(self) -> bool:
        return bool((self.asr_app_key and self.asr_access_key) or self.asr_api_key)

    @property
    def tts_ready(self) -> bool:
        return bool(self.tts_app_key and self.tts_access_key and self.tts_voice)


@dataclass(frozen=True)
class AsrResult:
    text: str
    final: bool = False
    utterances: tuple[dict, ...] = ()
    sequence: int = 0


@dataclass(frozen=True)
class TtsPacket:
    event: int
    session_id: str
    payload: bytes
    is_audio: bool = False


def _json_bytes(value: dict) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def _take(data: bytes, cursor: int, length: int) -> tuple[bytes, int]:
    if length < 0 or cursor + length > len(data):
        raise SpeechProviderError("speech protocol: truncated frame")
    return data[cursor:cursor + length], cursor + length


def _integer(data: bytes, cursor: int) -> tuple[int, int]:
    value, cursor = _take(data, cursor, 4)
    return struct.unpack(">i", value)[0], cursor


def _blob(data: bytes, cursor: int) -> tuple[bytes, int]:
    length, cursor = _integer(data, cursor)
    return _take(data, cursor, length)


def _header(data: bytes) -> tuple[int, int, int, int, int]:
    if not isinstance(data, bytes) or len(data) < 4 or data[0] >> 4 != 1:
        raise SpeechProviderError("speech protocol: invalid header")
    cursor = (data[0] & 15) * 4
    if cursor < 4 or cursor > len(data):
        raise SpeechProviderError("speech protocol: invalid header size")
    return data[1] >> 4, data[1] & 15, data[2] >> 4, data[2] & 15, cursor


def _decode(payload: bytes, compression: int) -> bytes:
    if compression == 0:
        return payload
    if compression != 1:
        raise SpeechProviderError("speech protocol: unsupported compression")
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(payload)) as reader:
            decoded = reader.read(4 * 1024 * 1024 + 1)
        if len(decoded) > 4 * 1024 * 1024:
            raise SpeechProviderError("speech protocol: decompressed payload too large")
        return decoded
    except (OSError, EOFError) as exc:
        raise SpeechProviderError("speech protocol: corrupt gzip") from exc


def build_asr_start(config: VolcSpeechConfig, uid: str = "aidol", language: str = "zh-CN", speakers: bool = False) -> bytes:
    body = {"user": {"uid": uid}, "audio": {"format": "pcm", "codec": "raw",
        "rate": config.input_rate, "bits": 16, "channel": 1, "language": language},
        "request": {"model_name": config.asr_model, "enable_itn": True,
            "enable_punc": True, "show_utterances": True, "result_type": "full"}}
    if urlsplit(config.asr_url).path.rstrip("/").endswith("/bigmodel_async"):
        # The optimized streaming endpoint needs second-pass ASR to emit
        # definite utterances while the microphone remains open. Without this,
        # partial text may remain uncommitted until the final audio packet.
        # Official protocol: LargemodelstreamingautomaticspeechrecognitionAPI,
        # request.enable_nonstream and end_window_size. vad_segment_duration
        # only splits semantic text and does NOT trigger definite=true.
        body["request"].update({"enable_nonstream": True, "end_window_size": 800})
    if speakers:
        # Speaker clustering (ASR 2.0): definite utterances carry additions.speaker_id ("0", "1", ...),
        # stable within one upstream session only. Verified 2026-10-17 with synthetic two-voice audio.
        body["request"].update({"enable_speaker_info": True, "ssd_version": "200"})
    payload = gzip.compress(_json_bytes(body))
    return b"\x11\x10\x11\x00" + struct.pack(">I", len(payload)) + payload


def build_asr_audio(pcm: bytes, *, final: bool = False) -> bytes:
    if len(pcm) % 2:
        raise ValueError("PCM16 audio must contain complete 16-bit samples")
    payload = gzip.compress(pcm)
    return bytes((0x11, 0x22 if final else 0x20, 0x01, 0)) + struct.pack(">I", len(payload)) + payload


def parse_asr_packet(data: bytes) -> AsrResult:
    kind, flags, serialization, compression, cursor = _header(data)
    if kind == 15:
        code, cursor = _integer(data, cursor)
        raise SpeechProviderError(f"ASR provider error code={code}")
    if kind != 9 or serialization != 1:
        raise SpeechProviderError("ASR protocol: unsupported response")
    sequence = 0
    if flags & 1:
        sequence, cursor = _integer(data, cursor)
    elif len(data) >= cursor + 8:
        # The supplied provider reference includes sequence even with flags=0.
        # Accept that variant only when the following length exactly matches.
        candidate_size = struct.unpack_from(">i", data, cursor + 4)[0]
        if candidate_size == len(data) - cursor - 8:
            sequence, cursor = _integer(data, cursor)
    payload, cursor = _blob(data, cursor)
    if cursor != len(data):
        raise SpeechProviderError("ASR protocol: trailing frame bytes")
    try:
        root = json.loads(_decode(payload, compression))
        if root.get("code", 0) not in (0, 1000, 20000000):
            raise SpeechProviderError("ASR provider rejected audio")
        result = root.get("result", {})
        utterances = tuple(item for item in result.get("utterances", []) if isinstance(item, dict))
        text = result.get("text") or "".join(str(item.get("text", "")) for item in utterances)
        return AsrResult(str(text), bool(flags & 2) or sequence < 0, utterances, sequence)
    except (ValueError, TypeError, AttributeError) as exc:
        raise SpeechProviderError("ASR protocol: invalid JSON response") from exc


def build_tts_frame(event: int, payload: dict | None = None, session_id: str | None = None) -> bytes:
    body = _json_bytes(payload or {})
    frame = b"\x11\x14\x10\x00" + struct.pack(">I", event)
    if session_id is not None:
        session = session_id.encode("utf-8")
        frame += struct.pack(">I", len(session)) + session
    return frame + struct.pack(">I", len(body)) + body


def parse_tts_packet(data: bytes) -> TtsPacket:
    kind, flags, serialization, compression, cursor = _header(data)
    if kind == 15:
        code, cursor = _integer(data, cursor)
        payload, cursor = _blob(data, cursor)
        # Match a known provider diagnosis but expose only our fixed wording.
        # Never forward arbitrary provider text (it may echo credentials).
        description = _decode(payload, compression)
        if b"resource ID is mismatched with speaker" in description:
            raise SpeechProviderError(f"TTS provider error code={code}: resource and voice do not match; mars_bigtts requires seed-tts-1.0")
        raise SpeechProviderError(f"TTS provider error code={code}")
    if kind not in (9, 11) or not flags & 4:
        raise SpeechProviderError("TTS protocol: expected v3 event response")
    event, cursor = _integer(data, cursor)
    identity = ""
    # Connection responses contain a connection ID; session responses contain
    # a session ID. Some gateways omit the ID for connection events.
    first_size, _ = _integer(data, cursor)
    if event >= 100 or first_size != len(data) - cursor - 4:
        raw_id, cursor = _blob(data, cursor)
        try:
            identity = raw_id.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise SpeechProviderError("TTS protocol: invalid session identifier") from exc
    payload, cursor = _blob(data, cursor)
    if cursor != len(data):
        raise SpeechProviderError("TTS protocol: trailing frame bytes")
    if event in (51, 153):
        raise SpeechProviderError(f"TTS provider rejected event={event}")
    return TtsPacket(event, identity, _decode(payload, compression), kind == 11 or event == 352)


def pcm_to_wav(pcm: bytes, sample_rate: int = 24000) -> bytes:
    if not pcm or len(pcm) % 2:
        raise SpeechProviderError("TTS returned empty or incomplete PCM16")
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(pcm)
    return output.getvalue()


class VolcSpeechProvider:
    def __init__(self, config: VolcSpeechConfig):
        self.config = config
        self._tls = None

    @staticmethod
    def _connection_error(service: str, error: Exception) -> SpeechProviderError:
        response = getattr(error, "response", None)
        status = getattr(response, "status_code", None)
        body = getattr(response, "body", b"")
        if status == 403 and isinstance(body, (bytes, bytearray)) and b"requested resource not granted" in body:
            return SpeechProviderError(f"{service} resource is not granted (HTTP 403); enable the configured speech resource")
        if status in (401, 403):
            return SpeechProviderError(f"{service} authentication or resource permission denied (HTTP {status})")
        return SpeechProviderError(f"{service} connection failed")

    def _headers(self, *, tts: bool = False, flash: bool = False) -> dict[str, str]:
        cfg = self.config
        if tts:
            if not cfg.tts_ready:
                raise SpeechProviderError("TTS credentials are not configured")
            result = {"X-Api-App-Key": cfg.tts_app_key, "X-Api-Access-Key": cfg.tts_access_key,
                      "X-Api-Resource-Id": cfg.tts_resource_id}
        else:
            if not cfg.asr_ready:
                raise SpeechProviderError("ASR credentials are not configured")
            result = {"X-Api-Resource-Id": cfg.asr_flash_resource_id if flash else cfg.asr_resource_id}
            if cfg.asr_app_key and cfg.asr_access_key:
                result.update({"X-Api-App-Key": cfg.asr_app_key, "X-Api-Access-Key": cfg.asr_access_key})
            else:
                result["X-Api-Key"] = cfg.asr_api_key
        result["X-Api-Request-Id" if flash else "X-Api-Connect-Id"] = str(uuid4())
        if flash:
            result["X-Api-Sequence"] = "-1"
        return result

    async def recognize(self, audio: bytes, *, format: str = "wav", codec: str = "raw", uid: str = "aidol") -> AsrResult:
        """AUC flash transcription of a complete file; requires auc_turbo access."""
        if not audio or len(audio) > 20 * 1024 * 1024:
            raise ValueError("audio must contain 1 byte to 20 MiB")
        if format not in {"wav", "mp3", "ogg", "pcm"} or codec not in {"raw", "mp3", "opus"}:
            raise ValueError("unsupported audio container or codec")
        cfg = self.config
        body = {"user": {"uid": uid}, "audio": {"data": base64.b64encode(audio).decode(),
            "format": format, "codec": codec, "rate": cfg.input_rate, "bits": 16, "channel": 1},
            "request": {"model_name": cfg.asr_model, "enable_itn": True, "enable_punc": True, "show_utterances": True}}
        headers = self._headers(flash=True)
        if self._tls is None:
            self._tls = truststore.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        client = self.config.http_pool.client("volc_speech", timeout=cfg.timeout,
                proxy=cfg.proxy, verify=self._tls)
        try:
            response = await client.post(cfg.asr_flash_url, headers=headers, json=body,
                timeout=cfg.timeout)
            if response.status_code != 200 or response.headers.get("X-Api-Status-Code") != "20000000":
                raise SpeechProviderError(f"ASR flash rejected request (HTTP {response.status_code})")
            result = response.json().get("result", {})
            return AsrResult(str(result.get("text", "")), True, tuple(result.get("utterances", [])))
        except httpx.HTTPError as exc:
            raise SpeechProviderError("ASR flash connection failed") from exc
        except (ValueError, AttributeError, TypeError) as exc:
            raise SpeechProviderError("ASR flash returned invalid JSON") from exc

    async def stream_asr(self, chunks: AsyncIterable[bytes], *, uid: str = "aidol", language: str = "zh-CN",
                         speakers: bool = False) -> AsyncIterator[AsrResult]:
        """Transcribe PCM16 chunks concurrently with capture; final result closes stream.

        End the input iterator to flush ASR. Cancelling or closing this generator
        cancels the sender and closes the upstream socket. Calls are isolated.
        """
        cfg = self.config
        headers = self._headers()
        try:
            async with connect(cfg.asr_url, additional_headers=headers, proxy=cfg.proxy,
                    ssl=truststore.SSLContext(ssl.PROTOCOL_TLS_CLIENT), open_timeout=12,
                    close_timeout=3, max_size=4 * 1024 * 1024) as ws:
                await ws.send(build_asr_start(cfg, uid, language, speakers))

                async def send_audio():
                    async for chunk in chunks:
                        if len(chunk) > 320000:
                            raise ValueError("ASR chunks must be at most 10 seconds at 16 kHz")
                        if chunk:
                            await ws.send(build_asr_audio(chunk))
                    await ws.send(build_asr_audio(b"", final=True))

                sender = asyncio.create_task(send_audio())
                receiver = None
                try:
                    while True:
                        receiver = asyncio.create_task(ws.recv())
                        pending = {receiver} if sender.done() else {sender, receiver}
                        done, _ = await asyncio.wait(pending, timeout=cfg.timeout, return_when=asyncio.FIRST_COMPLETED)
                        if not done:
                            raise SpeechProviderError("ASR response timed out")
                        if sender in done or sender.done():
                            sender.result()
                        packet = await asyncio.wait_for(receiver, cfg.timeout)
                        result = parse_asr_packet(packet)
                        yield result
                        if result.final:
                            break
                finally:
                    sender.cancel()
                    if receiver is not None:
                        receiver.cancel()
                    await asyncio.gather(sender, *([receiver] if receiver else []), return_exceptions=True)
        except (SpeechProviderError, ValueError, asyncio.CancelledError):
            raise
        except Exception as exc:
            raise self._connection_error("ASR streaming", exc) from exc

    async def stream_tts(self, text: str, *, voice: str | None = None, uid: str = "aidol") -> AsyncIterator[bytes]:
        """Synthesize one turn using v3 bidirectional WS; yield PCM16 chunks."""
        if not text.strip() or len(text) > 5000:
            raise ValueError("TTS text must contain 1 to 5000 characters")
        cfg = self.config
        headers = self._headers(tts=True)
        session_id = str(uuid4())

        def payload(event: int) -> dict:
            params = {"speaker": voice or cfg.tts_voice,
                      "audio_params": {"format": "pcm", "sample_rate": cfg.output_rate}}
            if event == 200:
                params.update({"text": text, "text_type": "plain"})
            return {"event": event, "namespace": "BidirectionalTTS", "reqid": str(uuid4()),
                    "user": {"uid": uid}, "req_params": params}

        try:
            async with asyncio.timeout(cfg.timeout):
                async with connect(cfg.tts_url, additional_headers=headers, proxy=cfg.proxy,
                        ssl=truststore.SSLContext(ssl.PROTOCOL_TLS_CLIENT), open_timeout=12,
                        close_timeout=3, max_size=4 * 1024 * 1024) as ws:
                    await ws.send(build_tts_frame(1))
                    packet = parse_tts_packet(await ws.recv())
                    if packet.event != 50:
                        raise SpeechProviderError("TTS connection was not acknowledged")
                    await ws.send(build_tts_frame(100, payload(100), session_id))
                    packet = parse_tts_packet(await ws.recv())
                    if packet.event != 150 or packet.session_id != session_id:
                        raise SpeechProviderError("TTS session was not acknowledged")
                    await ws.send(build_tts_frame(200, payload(200), session_id))
                    await ws.send(build_tts_frame(102, session_id=session_id))
                    size = 0
                    while True:
                        packet = parse_tts_packet(await ws.recv())
                        if packet.session_id and packet.session_id != session_id:
                            raise SpeechProviderError("TTS response session mismatch")
                        if packet.is_audio:
                            size += len(packet.payload)
                            if size > 30 * 1024 * 1024:
                                raise SpeechProviderError("TTS audio exceeds 30 MiB limit")
                            yield packet.payload
                        if packet.event == 152:
                            if size == 0:
                                raise SpeechProviderError("TTS completed without audio")
                            await ws.send(build_tts_frame(2))
                            break
                        if packet.event in (52, 151):
                            raise SpeechProviderError("TTS session ended before completion")
        except (SpeechProviderError, ValueError, asyncio.CancelledError):
            raise
        except Exception as exc:
            raise self._connection_error("TTS streaming", exc) from exc

    async def synthesize_wav(self, text: str, *, voice: str | None = None, uid: str = "aidol") -> bytes:
        chunks = [chunk async for chunk in self.stream_tts(text, voice=voice, uid=uid)]
        return pcm_to_wav(b"".join(chunks), self.config.output_rate)
