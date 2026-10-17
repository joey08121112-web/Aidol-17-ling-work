"""Qwen Audio 3.1 Flash HTTP ASR; PCM stays in memory, diarization is segment-local."""
import base64
from dataclasses import dataclass, field
import io
import os
from urllib.parse import urlsplit
import wave

import httpx

from .volc_speech import AsrResult, SpeechProviderError


@dataclass
class QwenWorkAsrConfig:
    api_key: str = field(default='', repr=False)
    url: str = 'https://maas.qianwenaiapi.com/api/v1/services/aigc/multimodal-generation/generation'
    model: str = 'qwen-audio-3.1-asr-flash'
    proxy: str | None = None

    @classmethod
    def from_env(cls):
        url = (os.getenv('QWEN_WORK_ASR_URL') or cls.url).strip()
        key = (os.getenv('QWEN_WORK_ASR_API_KEY') or '').strip()
        # Reuse the existing credential only for the documented same-service origin.
        omni = urlsplit(os.getenv('QWEN_OMNI_WS_URL') or 'wss://maas.qianwenaiapi.com/api-ws/v1/realtime')
        target = urlsplit(url)
        if not key and target.scheme == 'https' and target.netloc == 'maas.qianwenaiapi.com':
            if omni.scheme == 'wss' and omni.netloc == 'maas.qianwenaiapi.com':
                key = (os.getenv('QWEN_OMNI_API_KEY') or '').strip()
        return cls(api_key=key,
                   url=url,
                   proxy=os.getenv('UPSTREAM_PROXY') or None)

    @property
    def ready(self):
        url = urlsplit(self.url)
        return bool(self.api_key and url.scheme == 'https' and url.hostname
                    and not url.username and not url.password and not url.query and not url.fragment)


class QwenWorkAsrProvider:
    def __init__(self, config):
        self.config = config

    async def stream_asr(self, chunks):
        if not self.config.ready:
            raise SpeechProviderError('Qwen work ASR is not configured')
        pcm = bytearray()
        async for chunk in chunks:
            pcm.extend(chunk)
            if len(pcm) > 16000 * 2 * 60:
                raise SpeechProviderError('Work ASR segment too long')
        if not pcm or len(pcm) % 2:
            raise SpeechProviderError('Invalid PCM segment')
        buffer = io.BytesIO()
        with wave.open(buffer, 'wb') as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(16000)
            wav.writeframes(pcm)
        payload = {'model': self.config.model, 'input': {'messages': [{'role': 'user', 'content': [
            {'type': 'input_audio', 'input_audio': {'data': 'data:audio/wav;base64,' +
                base64.b64encode(buffer.getvalue()).decode('ascii')}}]}]},
            'parameters': {'format': 'wav', 'sample_rate': '16000', 'speaker_diarization_enabled': True}}
        try:
            async with httpx.AsyncClient(timeout=55, proxy=self.config.proxy) as client:
                response = await client.post(self.config.url, json=payload, headers={
                    'Authorization': 'Bearer ' + self.config.api_key, 'X-DashScope-SSE': 'disable'})
                # This service uses HTTP 400 for a successful no-speech observation.
                if response.status_code == 400:
                    error = response.json()
                    if error.get('code') == 'CLIENT_ERROR' and error.get('message') == 'ASR_RESPONSE_HAVE_NO_WORDS':
                        yield AsrResult('', final=True)
                        return
                response.raise_for_status()
                output = response.json()['output']
            text = output['text']
            if not isinstance(text, str):
                raise ValueError('Invalid transcription')
            sentences = output.get('sentences', [])
            if not isinstance(sentences, list):
                raise ValueError('Invalid sentences')
            # Keep the provider's original text untouched. Speaker evidence is separate.
            utterances = []
            for sentence in sentences:
                if not isinstance(sentence, dict) or not isinstance(sentence.get('text'), str):
                    raise ValueError('Invalid sentence')
                utterances.append({key: sentence.get(key) for key in
                                   ('text', 'speaker_id', 'begin_time', 'end_time')})
            yield AsrResult(text, final=True, utterances=tuple(utterances))
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
            raise SpeechProviderError('Qwen work ASR failed') from exc
