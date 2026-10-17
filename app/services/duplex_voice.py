"""An authenticated, persistent call using the account's one existing orchestrator."""
import asyncio
from contextlib import aclosing
import json
import logging
import re
from uuid import uuid4

from fastapi import HTTPException, WebSocketDisconnect

from ..providers.volc_speech import SpeechProviderError
from .qwen_voice import realtime_instructions


def interruption_request(text):
    """A response-only interrupt phrase, never a wake gate for ordinary questions."""
    match = re.search(r'小[\s，,。.!！?？、]*万', text)
    return None if match is None else text[match.end():].lstrip(' ，,。.!！?？、：:')


async def duplex_call(socket, runtime, native, speech, request_id, charge):
    send_lock = asyncio.Lock()
    pending = asyncio.Queue(maxsize=4)
    stopped = asyncio.Event()
    active = None
    voice = None
    turn = 0
    first = True
    speech_epoch = 0
    epochs = {}
    last_result = {}
    input_bytes = 0
    output_turn = None
    gated = {}
    authorized = set()
    diagnostics = logging.getLogger("uvicorn.error.aidol.mobile")

    async def send(value):
        async with send_lock, asyncio.timeout(5):
            if isinstance(value, bytes):
                await socket.send_bytes(value)
            else:
                await socket.send_json(value)

    async def interrupt():
        nonlocal voice, output_turn
        output_turn = None
        if active is not None:
            active.set()
        # Signal the phone before awaiting any upstream cancellation or database work.
        await send({'type': 'input.speech_start'})
        await send({'type': 'reply.interrupted'})
        await native.cancel()
        if voice is not None:
            voice.cancel()

    async def microphone():
        nonlocal input_bytes, speech_epoch, output_turn
        while True:
            message = await socket.receive()
            if message['type'] == 'websocket.disconnect':
                raise WebSocketDisconnect()
            pcm = message.get('bytes')
            if pcm is not None:
                if not pcm or len(pcm) % 2 or len(pcm) > 32000:
                    raise ValueError('实时录音帧无效')
                input_bytes += len(pcm)
                await native.append(pcm)
            else:
                raw = message.get('text', '')
                if not raw or len(raw) > 40000:
                    raise ValueError('实时会话控制帧无效')
                command = json.loads(raw)
                if not isinstance(command, dict):
                    raise ValueError('实时会话控制帧无效')
                if command == {'type': 'end'}:
                    stopped.set()
                    return
                if set(command) == {'type', 'turn'} and command.get('type') == 'playback.done':
                    if type(command['turn']) is not int:
                        raise ValueError('播放确认无效')
                    if command['turn'] == output_turn:
                        output_turn = None
                    continue
                if command == {'type': 'interrupt'}:
                    await interrupt()
                elif set(command) == {'type', 'text'} and command.get('type') == 'text':
                    text = command['text']
                    if not isinstance(text, str) or not text.strip() or len(text) > 6000 or pending.full():
                        raise ValueError('文字消息无效或仍在处理中')
                    speech_epoch += 1
                    await interrupt()
                    await native.text(text)
                    pending.put_nowait((text, speech_epoch, 'text'))
                else:
                    raise ValueError('实时会话控制帧无效')

    async def input_events():
        nonlocal speech_epoch
        while True:
            event = await native.take(native.inputs, None)
            kind = event['type']
            item = event.get('item_id')
            if kind == 'input_audio_buffer.speech_started':
                if len(epochs) > 64:
                    epochs.clear()
                    gated.clear()
                    authorized.clear()
                # Protect an accepted question before the first output too.
                # Background VAD starts must not repeatedly cancel model startup
                # or invalidate a queued, already-transcribed question.
                gated[item] = (output_turn is not None
                    or active is not None and not active.is_set() or not pending.empty())
                if not gated[item]:
                    await send({'type': 'input.speech_start'})
                epochs[item] = speech_epoch
            elif kind == 'input_audio_buffer.speech_stopped':
                if not gated.get(item) or item in authorized:
                    await send({'type': 'input.speech_end'})
            elif kind in {'conversation.item.input_audio_transcription.delta', 'conversation.item.input_audio_transcription.text'}:
                stable, tail = event.get('text', ''), event.get('stash', '')
                if not isinstance(stable, str) or not isinstance(tail, str):
                    raise ValueError('实时转写格式无效')
                text = stable + tail
                if len(text) > 6000:
                    raise ValueError('实时转写格式无效')
                if gated.get(item):
                    if interruption_request(text) is None:
                        continue
                    if item not in authorized:
                        authorized.add(item)
                        speech_epoch += 1
                        epochs[item] = speech_epoch
                        await interrupt()
                await send({'type': 'transcript.partial', 'text': text})
            elif kind == 'conversation.item.input_audio_transcription.completed':
                text = event.get('transcript', event.get('text', ''))
                if not isinstance(text, str) or len(text) > 6000:
                    raise ValueError('实时转写格式无效')
                if gated.pop(item, False):
                    request = interruption_request(text)
                    if request is None and item not in authorized:
                        continue
                    if item not in authorized:
                        speech_epoch += 1
                        epochs[item] = speech_epoch
                        await interrupt()
                    authorized.discard(item)
                    text = request if request is not None else text
                if text.strip():
                    if pending.full():
                        raise SpeechProviderError('实时发言处理积压，请结束后重新接通')
                    pending.put_nowait((text, epochs.get(event.get('item_id'), speech_epoch), 'asr'))
            elif kind == 'conversation.item.input_audio_transcription.failed':
                raise SpeechProviderError('实时转写失败，请重新接通')

    async def turns():
        nonlocal active, voice, turn, first, last_result
        while True:
            text, epoch, source = await pending.get()
            if not charge(not first):
                raise HTTPException(429, '今日体验额度已用完，请明天再试')
            first = False
            turn += 1
            current = turn
            stop = asyncio.Event()
            if epoch < speech_epoch:
                stop.set()
            active = stop
            child_id = uuid4().hex
            runtime.store.mobile_turn(child_id, 'duplex:' + request_id)
            announced = False
            answer = ''
            audio_bytes = 0
            mic_at_reply = None
            started = asyncio.get_running_loop().time()

            async def start():
                nonlocal announced, started, output_turn
                if not announced and not stop.is_set():
                    announced = True
                    output_turn = current
                    started = asyncio.get_running_loop().time()
                    await send({'type': 'reply.start', 'sample_rate': 24000, 'turn': current})

            async def audio(pcm):
                nonlocal audio_bytes, mic_at_reply
                # Keep the wire close to playback time, so speech-start control never
                # sits behind many seconds of an eagerly generated audio response.
                delay = audio_bytes / 48000 - (asyncio.get_running_loop().time() - started) - .15
                if delay > 0:
                    await asyncio.sleep(delay)
                if not stop.is_set():
                    if mic_at_reply is None:
                        mic_at_reply = input_bytes
                    await send(pcm)
                    audio_bytes += len(pcm)

            async def generate(instructions, toolkit):
                nonlocal voice, answer, output_turn
                async def render():
                    nonlocal answer
                    async with aclosing(native.reply(realtime_instructions(instructions), toolkit.schemas(), toolkit.call)) as events:
                        async for event in events:
                            if stop.is_set():
                                return
                            await start()
                            if 'text' in event:
                                answer = event['text']
                                await send({'type': 'reply.text', 'text': answer, 'turn': current})
                            else:
                                await audio(event['audio'])
                failed = False
                if not stop.is_set():
                    if source == 'asr':
                        await native.text(text)
                    voice = asyncio.create_task(render())
                    try:
                        await voice
                    except asyncio.CancelledError:
                        if not stop.is_set():
                            raise
                    except (SpeechProviderError, TimeoutError):
                        failed = True
                        if output_turn == current:
                            output_turn = None
                        await send({'type': 'reply.error', 'detail': '本轮语音未完成，可以继续说或结束通话'})
                    finally:
                        voice = None
                return {'text': answer, 'complete': not failed and not stop.is_set(),
                        'interrupted': stop.is_set(), 'error': failed}

            await send({'type': 'thinking', 'turn': current})
            try:
                result = await runtime.turn(text, source, 'continuous_call', response_provider=generate)
                if not announced:
                    await native.cancel()
                if result.get('speak') and result.get('response') and not announced and not stop.is_set():
                    # Deterministic memory operations keep their exact receipt.
                    if speech.config.tts_ready:
                        async def receipt():
                            await start()
                            await send({'type': 'reply.text', 'text': result['response'], 'turn': current})
                            for offset in range(0, len(result['response']), 1500):
                                async with aclosing(speech.stream_tts(result['response'][offset:offset + 1500])) as frames:
                                    async for pcm in frames:
                                        for pos in range(0, len(pcm), 9600):
                                            if stop.is_set():
                                                return
                                            await audio(pcm[pos:pos + 9600])
                        voice = asyncio.create_task(receipt())
                        try:
                            await voice
                        except asyncio.CancelledError:
                            if not stop.is_set():
                                raise
                        finally:
                            voice = None
                    else:
                        result['speak'] = False
                        result['notice'] = '记忆操作已处理，请查看文字回执'
                if announced and not stop.is_set():
                    await send({'type': 'reply.done', 'turn': current})
                if stop.is_set():
                    result['voice_interrupted'] = True
                result['request_id'] = child_id
                runtime.store.finish_mobile_turn(child_id, result)
                diagnostics.info('mobile request_id=%s stage=duplex_reply status=%s audio_bytes=%d microphone_bytes_during_reply=%d',
                    child_id, 'interrupted' if stop.is_set() else 'done', audio_bytes, input_bytes - (mic_at_reply if mic_at_reply is not None else input_bytes))
                last_result = result
                await send({'type': 'result', 'result': result, 'turn': current})
                if result.get('interaction') == 'mode_switch':
                    return
                # Apply current account preferences to future automatic responses.
                if not stop.is_set():
                    await native.update(instructions=realtime_instructions(runtime.agent.instructions(directed=True)))
            finally:
                runtime.store.fail_mobile_turn(child_id)
                active = None

    tasks = [asyncio.create_task(microphone()), asyncio.create_task(input_events()), asyncio.create_task(turns())]
    try:
        await send({'type': 'ready', 'sample_rate': 16000, 'engine': 'qwen', 'duplex': True,
                    'interrupt_keyword': '小万'})
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            await task
        return last_result
    finally:
        stopped.set()
        if active is not None:
            active.set()
        for task in tasks + ([voice] if voice is not None else []):
            task.cancel()
        await asyncio.gather(*tasks, *([voice] if voice is not None else []), return_exceptions=True)
