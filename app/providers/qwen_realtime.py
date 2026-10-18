"""Qwen Omni audio-in/audio-out WebSocket protocol; no account/business logic.

The default wire dialect matches the supplied MaaS demo (pcm24 = 24 kHz
PCM16 output). Configuration and bearer credentials belong only on the backend.
"""
import asyncio
import base64
from contextlib import asynccontextmanager, AsyncExitStack
from dataclasses import dataclass, field
import json
import logging
import os
import re
import ssl
from urllib.parse import urlencode, urlsplit

import truststore
from websockets.asyncio.client import connect
from websockets.exceptions import WebSocketException

from .volc_speech import AsrResult, SpeechProviderError


@dataclass
class QwenRealtimeConfig:
    api_key: str = field(default="", repr=False)
    url: str = "wss://maas.qianwenaiapi.com/api-ws/v1/realtime"
    model: str = "qwen3.8-omni-flash-realtime"
    voice: str = "Tina"
    transcription_model: str = "qwen3-asr-flash-realtime"
    output_format: str = "pcm24"
    asr_vad_threshold: float = 0.65
    proxy: str | None = None

    @classmethod
    def from_env(cls):
        names = {"api_key": "API_KEY", "url": "WS_URL", "model": "MODEL", "voice": "VOICE",
                 "transcription_model": "TRANSCRIPTION_MODEL", "output_format": "OUTPUT_FORMAT"}
        values = {name: os.environ['QWEN_OMNI_' + suffix].strip() for name, suffix in names.items()
                  if 'QWEN_OMNI_' + suffix in os.environ}
        return cls(**values, asr_vad_threshold=float(os.getenv('QWEN_OMNI_ASR_VAD_THRESHOLD', '0.65')),
                   proxy=os.getenv('UPSTREAM_PROXY') or None)

    @property
    def ready(self):
        parsed = urlsplit(self.url)
        return bool(self.api_key and self.model and self.voice and self.transcription_model
                    and parsed.scheme == 'wss' and parsed.hostname and not parsed.username
                    and not parsed.password and not parsed.query and not parsed.fragment
                    and self.output_format in {'pcm24', 'pcm16'}
                    and 0 < self.asr_vad_threshold < 1)


class QwenRealtimeProvider:
    def __init__(self, config):
        self.config = config

    @asynccontextmanager
    async def session(self, instructions, history=(), schemas=()):
        cfg = self.config
        if not cfg.ready:
            raise SpeechProviderError('Qwen realtime is not configured')
        try:
            async with connect(cfg.url + '?' + urlencode({'model': cfg.model}),
                    additional_headers={'Authorization': 'Bearer ' + cfg.api_key}, proxy=cfg.proxy,
                    ssl=truststore.SSLContext(ssl.PROTOCOL_TLS_CLIENT), open_timeout=12,
                    close_timeout=3, max_size=2 * 1024 * 1024, max_queue=16) as ws:
                session = QwenRealtimeSession(ws, cfg)
                await session.update(instructions=instructions, modalities=['text', 'audio'],
                    voice=cfg.voice, input_audio_format='pcm16', output_audio_format=cfg.output_format,
                    turn_detection=None, input_audio_transcription={'model': cfg.transcription_model},
                    tools=[{'type': 'function', **s['function']} for s in schemas])
                await session.seed_history(history)
                yield session
        except (WebSocketException, OSError) as exc:
            # Never expose the provider response, URL query or bearer header.
            raise SpeechProviderError('Qwen realtime connection failed') from exc


    @asynccontextmanager
    async def duplex_session(self, instructions, history=(), schemas=()):
        if not self.config.ready:
            raise SpeechProviderError('Qwen realtime is not configured')
        # Independent transports initialize together; the stack also closes a
        # successful sibling when the other connection fails.
        async with AsyncExitStack() as stack:
            base, asr = await asyncio.gather(
                stack.enter_async_context(self.session(instructions, history, schemas)),
                stack.enter_async_context(self.transcription_session()), return_exceptions=True)
            for result in (base, asr):
                if isinstance(result, BaseException):
                    raise result
            session = QwenDuplexSession(base.ws, base.config, asr)
            try:
                await session.start()
                yield session
            finally:
                await session.close_reader()

    @asynccontextmanager
    async def transcription_session(self):
        cfg = self.config
        try:
            async with connect(cfg.url + '?' + urlencode({'model': cfg.transcription_model}),
                    additional_headers={'Authorization': 'Bearer ' + cfg.api_key}, proxy=cfg.proxy,
                    ssl=truststore.SSLContext(ssl.PROTOCOL_TLS_CLIENT), open_timeout=12,
                    close_timeout=3, max_size=2 * 1024 * 1024, max_queue=16) as ws:
                session = QwenRealtimeSession(ws, cfg)
                await session.update(modalities=['text'], input_audio_format='pcm', sample_rate=16000,
                    instructions='只做语音转录，不要生成回复。channel=aidol_call_transcription',
                    input_audio_transcription={'language': 'zh'},
                    turn_detection={'type': 'server_vad', 'threshold': cfg.asr_vad_threshold,
                                    'prefix_padding_ms': 300, 'silence_duration_ms': 600})
                yield session
        except (WebSocketException, OSError) as exc:
            raise SpeechProviderError('Qwen transcription connection failed') from exc


class QwenRealtimeSession:
    def __init__(self, ws, config):
        self.ws, self.config = ws, config
        self.active = False
        self.endpoint_callback = None
        self.cloud_stopped = False

    async def seed_history(self, history):
        for row in history[-12:]:
            role = row.get('role')
            if role not in {'user', 'assistant'} or not isinstance(row.get('content'), str):
                continue
            await self.send('conversation.item.create', item={'type': 'message', 'role': role,
                'content': [{'type': 'input_text' if role == 'user' else 'text',
                             'text': row['content'][:2000]}]})

    async def send(self, kind, **values):
        async with asyncio.timeout(10):
            await self.ws.send(json.dumps({'type': kind, **values}, ensure_ascii=False))

    async def receive(self, timeout=30):
        try:
            value = json.loads(await asyncio.wait_for(self.ws.recv(), timeout))
            if not isinstance(value, dict) or not isinstance(value.get('type'), str):
                raise ValueError()
            if value['type'] == 'error':
                code = str((value.get('error') or {}).get('code', 'unknown'))
                self.last_error_code = code if re.fullmatch(r'[a-zA-Z0-9_.-]{1,80}', code) else 'unknown'
                message = str((value.get('error') or {}).get('message', '')).lower()
                self.last_error_categories = ','.join(word for word in ('rate', 'quota', 'limit', 'audio', 'buffer', 'session', 'timeout', 'internal', 'input', 'pcm', 'duration', 'silence', 'invalid', 'unsupported') if word in message)
                raise SpeechProviderError('Qwen realtime rejected the request')
            return value
        except (ValueError, TypeError) as exc:
            raise SpeechProviderError('Qwen realtime returned an invalid event') from exc

    async def update(self, **settings):
        await self.send('session.update', session=settings)
        async with asyncio.timeout(15):
            while (await self.receive())['type'] != 'session.updated':
                pass

    async def enable_endpoint(self, callback):
        """Cloud VAD decides the boundary; account orchestration still owns response.create."""
        await self.update(turn_detection={'type': 'server_vad', 'threshold': 0.5,
            'prefix_padding_ms': 300, 'silence_duration_ms': 600,
            'create_response': False, 'interrupt_response': False})
        self.endpoint_callback = callback

    async def stream_asr(self, chunks):
        """Upload original audio into Omni; transcription is for local persistence.

        No response is generated until the explicit client end and auth recheck.
        The same upstream conversation retains the audio for native generation.
        """
        committed = asyncio.Event()

        async def upload():
            async for chunk in chunks:
                await self.send('input_audio_buffer.append', audio=base64.b64encode(chunk).decode('ascii'))
            if not self.cloud_stopped:
                await self.send('input_audio_buffer.commit')
            committed.set()

        sender = asyncio.create_task(upload())
        receiver = None
        partial = ''
        try:
            while True:
                receiver = asyncio.create_task(self.receive(timeout=75))
                done, _ = await asyncio.wait({sender, receiver} if not sender.done() else {receiver},
                                            return_when=asyncio.FIRST_COMPLETED)
                if sender in done or sender.done():
                    sender.result()
                event = await receiver
                kind = event['type']
                if kind == 'input_audio_buffer.speech_stopped' and self.endpoint_callback is not None:
                    if not self.cloud_stopped:
                        self.cloud_stopped = True
                        await self.endpoint_callback()
                if kind == 'input_audio_buffer.committed' and self.endpoint_callback is not None:
                    self.cloud_stopped = True
                if kind == 'conversation.item.input_audio_transcription.delta':
                    stable, pending = event.get('text', ''), event.get('stash', '')
                    if not isinstance(stable, str) or not isinstance(pending, str) or len(stable) + len(pending) > 6000:
                        raise SpeechProviderError('Qwen transcription is invalid')
                    current = stable + pending
                    if current and current != partial:
                        partial = current
                        yield AsrResult(current, final=False)
                if kind == 'conversation.item.input_audio_transcription.failed':
                    raise SpeechProviderError('Qwen transcription failed')
                if kind == 'conversation.item.input_audio_transcription.completed':
                    if self.endpoint_callback is not None and self.cloud_stopped:
                        # Wait for phone SDK drain/end acknowledgement before business execution.
                        await sender
                    if not committed.is_set():
                        raise SpeechProviderError('Qwen transcription arrived before commit')
                    text = event.get('transcript', '')
                    if not isinstance(text, str) or len(text) > 6000:
                        raise SpeechProviderError('Qwen transcription is invalid')
                    yield AsrResult(text, final=True)
                    return
                if kind.startswith('response.'):
                    raise SpeechProviderError('Qwen generated before explicit response request')
        finally:
            sender.cancel()
            if receiver is not None:
                receiver.cancel()
            await asyncio.gather(sender, *([receiver] if receiver else []), return_exceptions=True)

    async def cancel(self):
        if self.active:
            await self.send('response.cancel')

    async def create_response(self):
        await self.send('response.create')

    async def reply(self, instructions, schemas, call_tool):
        """Yield text snapshots/audio from original audio, with bounded tool rounds."""
        if not getattr(self, 'duplex_auto', False):
            await self.update(turn_detection=None, instructions=instructions,
                tools=[{'type': 'function', **s['function']} for s in schemas])
        calls_used, audio_size = 0, 0
        answer = ''
        for round_number in range(4):
            if not (getattr(self, 'duplex_auto', False) and round_number == 0):
                await self.create_response()
            self.active = True
            calls = {}
            round_text = ''
            try:
                while True:
                    event = await self.receive()
                    kind = event['type']
                    if kind in {'response.audio.delta', 'response.output_audio.delta'}:
                        try:
                            pcm = base64.b64decode(event['delta'], validate=True)
                        except (ValueError, KeyError, TypeError) as exc:
                            raise SpeechProviderError('Qwen returned invalid audio') from exc
                        audio_size += len(pcm)
                        if not pcm or len(pcm) % 2 or audio_size > 6 * 1024 * 1024:
                            raise SpeechProviderError('Qwen audio limit exceeded')
                        for offset in range(0, len(pcm), 24000):
                            yield {'audio': pcm[offset:offset + 24000]}
                    elif kind in {'response.audio_transcript.delta', 'response.text.delta', 'response.output_text.delta'}:
                        delta = event.get('delta', '')
                        if not isinstance(delta, str):
                            raise SpeechProviderError('Qwen returned invalid text')
                        round_text += delta
                        if len(answer) + len(round_text) > 12000:
                            raise SpeechProviderError('Qwen text limit exceeded')
                        yield {'text': answer + round_text}
                    elif kind in {'response.audio_transcript.done', 'response.text.done', 'response.output_text.done'}:
                        final = event.get('transcript', event.get('text', round_text))
                        if not isinstance(final, str) or len(answer) + len(final) > 12000:
                            raise SpeechProviderError('Qwen returned invalid text')
                        round_text = final
                        yield {'text': answer + round_text}
                    elif kind == 'response.function_call_arguments.done':
                        items = event.get('items', [event])
                        if isinstance(items, dict):
                            items = [items]
                        if not isinstance(items, list) or len(items) > 6:
                            raise SpeechProviderError('Qwen tool calls are invalid')
                        for item in items:
                            if not isinstance(item, dict):
                                raise SpeechProviderError('Qwen tool call is invalid')
                            calls[item.get('call_id')] = {**item, '_tool_message': 'items' in event}
                        if len(calls) > 6:
                            raise SpeechProviderError('Qwen tool limit exceeded')
                    elif kind == 'response.done':
                        response = event.get('response', {})
                        if not isinstance(response, dict) or not isinstance(response.get('output', []), list):
                            raise SpeechProviderError('Qwen response is invalid')
                        if response.get('status', 'completed') != 'completed':
                            raise SpeechProviderError('Qwen response was not completed')
                        for item in response.get('output', []):
                            if not isinstance(item, dict):
                                raise SpeechProviderError('Qwen response item is invalid')
                            if item.get('type') == 'function_call':
                                calls.setdefault(item.get('call_id'), item)
                        break
            finally:
                self.active = False
            answer += round_text
            if not calls:
                if not answer.strip() or not audio_size:
                    raise SpeechProviderError('Qwen response had no text or audio')
                return
            if round_number == 3 or calls_used + len(calls) > 6:
                raise SpeechProviderError('Qwen tool limit exceeded')
            for call_id, item in calls.items():
                if not isinstance(call_id, str) or not call_id or len(call_id) > 200:
                    raise SpeechProviderError('Qwen tool call id is invalid')
                function = item.get('function') or item
                if not isinstance(function, dict):
                    raise SpeechProviderError('Qwen tool call is invalid')
                arguments = function.get('arguments', '{}')
                if not isinstance(arguments, (str, dict)) or len(json.dumps(arguments)) > 8000:
                    raise SpeechProviderError('Qwen tool arguments are invalid')
                try:
                    arguments = json.loads(arguments) if isinstance(arguments, str) else arguments
                except ValueError as exc:
                    raise SpeechProviderError('Qwen tool arguments are invalid') from exc
                calls_used += 1
                name = function.get('name', '')
                if not isinstance(name, str) or not isinstance(arguments, dict):
                    raise SpeechProviderError('Qwen tool arguments are invalid')
                result = await call_tool(name, arguments)
                # Older demo batches use role=tool; the live MaaS endpoint now
                # emits standard flat calls and requires function_call_output.
                # Detect the event dialect, never infer it from audio format.
                output = json.dumps(result, ensure_ascii=False)
                tool_item = ({'call_id': call_id, 'role': 'tool',
                              'content': [{'type': 'input_text', 'text': output}]}
                             if item.get('_tool_message') else
                             {'type': 'function_call_output', 'call_id': call_id, 'output': output})
                await self.send('conversation.item.create', item=tool_item)


class QwenDuplexSession(QwenRealtimeSession):
    """Separate ASR/output transports; one application orchestrator accepts turns."""
    duplex_auto = True

    def __init__(self, ws, config, asr=None):
        super().__init__(ws, config)
        self.asr = asr
        self.asr_reader = None
        self.inputs = asyncio.Queue(maxsize=64)
        self.responses = asyncio.Queue(maxsize=256)
        self.updates = asyncio.Queue(maxsize=4)
        self.settled = asyncio.Event()
        self.settled.set()
        self.reader = None
        self.failure = None
        self.response_id = None
        self.cancelled_ids = set()
        self.response_buffered = 0
        self.response_created = asyncio.Event()
        self.update_lock = asyncio.Lock()

    async def start(self):
        self.reader = asyncio.create_task(self._read())
        await self.update(turn_detection=None)
        if self.asr is not None:
            self.asr_reader = asyncio.create_task(self._read_asr())

    async def _read_asr(self):
        try:
            while True:
                event = await self.asr.receive(timeout=None)
                if event['type'].startswith(('input_audio_buffer.', 'conversation.item.input_audio_transcription.')):
                    await self.inputs.put(event)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            logging.getLogger('uvicorn.error.aidol.mobile').warning('qwen_asr failure=%s code=%s category=%s',
                type(error).__name__, getattr(self.asr, 'last_error_code', 'none'), getattr(self.asr, 'last_error_categories', 'none'))
            self.failure = SpeechProviderError('通义实时识别连接已中断')
            if not self.inputs.full(): self.inputs.put_nowait(None)

    async def _read(self):
        try:
            while True:
                event = await super().receive(timeout=None)
                kind = event['type']
                if kind == 'session.updated':
                    await self.updates.put(event)
                elif kind.startswith('response.'):
                    response_id = event.get('response_id') or event.get('response', {}).get('id')
                    if response_id and response_id in self.cancelled_ids:
                        continue
                    if kind == 'response.created':
                        self.response_id = response_id
                        self.active = True
                        self.response_created.set()
                    # Bind every queued event to the turn that produced it, so
                    # the consumer can drop a previous turn's late audio after
                    # that turn timed out. Events without any id keep the turn
                    # current at read time; if the endpoint emits none at all,
                    # nothing is ever dropped (behaviour unchanged).
                    event['_aidol_response_id'] = response_id or self.response_id
                    if event['_aidol_response_id'] and event['_aidol_response_id'] in self.cancelled_ids:
                        continue
                    if kind == 'response.done':
                        self.active = False
                        self.settled.set()
                    size = len(json.dumps(event, ensure_ascii=False))
                    if self.responses.full() or self.response_buffered + size > 8 * 1024 * 1024:
                        raise SpeechProviderError("实时回复缓存积压")
                    event["_aidol_buffer_size"] = size
                    self.response_buffered += size
                    self.responses.put_nowait(event)
                elif kind.startswith(('input_audio_buffer.', 'conversation.item.input_audio_transcription.')):
                    await self.inputs.put(event)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            logging.getLogger('uvicorn.error.aidol.mobile').warning(
                'qwen_reader failure=%s code=%s', type(error).__name__, getattr(self, 'last_error_code', 'none'))
            self.failure = SpeechProviderError('通义实时连接已中断')
            self.settled.set()
            for queue in (self.inputs, self.responses, self.updates):
                if not queue.full():
                    queue.put_nowait(None)

    async def take(self, queue, timeout=100):
        if self.failure:
            raise self.failure
        value = await asyncio.wait_for(queue.get(), timeout)
        if value is None:
            raise self.failure or SpeechProviderError('通义实时连接已中断')
        return value

    async def receive(self, timeout=30):
        while True:
            event = await self.take(self.responses, timeout)
            self.response_buffered = max(0, self.response_buffered - event.pop("_aidol_buffer_size", 0))
            bound = event.pop("_aidol_response_id", None)
            if bound is not None and self.response_id is not None and bound != self.response_id:
                # A previous turn timed out or was abandoned; its late events
                # must not leak audio into the reply being consumed now.
                continue
            return event

    async def update(self, **settings):
        async with self.update_lock:
            await self.send('session.update', session=settings)
            await self.take(self.updates, 15)

    async def create_response(self):
        self.response_created.clear()
        await self.send('response.create')
        await asyncio.wait_for(self.response_created.wait(), 12)

    async def reply(self, instructions, schemas, call_tool):
        await self.update(instructions=instructions,
            tools=[{'type': 'function', **s['function']} for s in schemas])
        await self.create_response()
        async for event in super().reply(instructions, schemas, call_tool):
            yield event

    async def cancel(self):
        if self.response_id:
            self.cancelled_ids.add(self.response_id)
            if len(self.cancelled_ids) > 64:
                self.cancelled_ids = {self.response_id}
        while not self.responses.empty():
            self.responses.get_nowait()
        self.response_buffered = 0
        await super().cancel()
        self.active = False

    async def text(self, text):
        await self.send('conversation.item.create', item={'type': 'message', 'role': 'user',
            'content': [{'type': 'input_text', 'text': text}]})

    async def append(self, pcm):
        if self.asr is None:
            raise SpeechProviderError('通义实时识别连接未建立')
        await self.asr.send('input_audio_buffer.append', audio=base64.b64encode(pcm).decode('ascii'))

    async def close_reader(self):
        if self.reader:
            self.reader.cancel()
            await asyncio.gather(self.reader, return_exceptions=True)
        if self.asr_reader:
            self.asr_reader.cancel()
            await asyncio.gather(self.asr_reader, return_exceptions=True)
