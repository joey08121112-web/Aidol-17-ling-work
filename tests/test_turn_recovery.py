"""Recovery distinguishes safe ASR retry from possibly committed business work."""
import asyncio
from concurrent.futures import ThreadPoolExecutor
from threading import Event
from uuid import uuid4

from app.providers.volc_speech import AsrResult, SpeechProviderError
from test_mobile_api import mobile, register


def test_asr_failure_is_queryable_and_same_id_can_retry(mobile, monkeypatch):
    _, headers = register(mobile)
    request_id = uuid4().hex
    pcm = b'\0\0' * 1600
    url = '/v1/voice?request_id=' + request_id + '&interaction_source=direct_question'
    pcm_headers = {**headers, 'Content-Type': 'audio/pcm'}

    async def fail(chunks):
        raise SpeechProviderError('secret upstream detail')
        yield

    monkeypatch.setattr(mobile.app.state.speech, 'stream_asr', fail)
    assert mobile.post(url, headers=pcm_headers, content=pcm).status_code == 502
    status = mobile.get('/v1/turns/' + request_id, headers=headers).json()
    assert status['status'] == 'failed' and status['stage'] == 'asr' and status['retryable']
    assert 'secret' not in str(status)
    # Changed audio/source must not reuse the reservation.
    assert mobile.post(url, headers=pcm_headers, content=b'\1\0' * 1600).status_code == 409
    assert mobile.post(url.replace('direct_question', 'ambient_capture'), headers=pcm_headers, content=pcm).status_code == 409

    async def success(chunks):
        yield AsrResult('记住，周四提交星光方案', final=True)

    monkeypatch.setattr(mobile.app.state.speech, 'stream_asr', success)
    first = mobile.post(url, headers=pcm_headers, content=pcm)
    assert first.status_code == 200 and first.json()['interaction'] == 'memory_remember'
    assert mobile.post(url, headers=pcm_headers, content=pcm).json() == first.json()
    status = mobile.get('/v1/turns/' + request_id, headers=headers).json()
    assert status['status'] == 'completed' and not status['retryable']
    assert len(mobile.get('/v1/state', headers=headers).json()['memories']) == 1
    _, other = register(mobile, 'other')
    assert mobile.get('/v1/turns/' + request_id, headers=other).status_code == 404
    assert mobile.get('/v1/turns/' + request_id).status_code == 401


def test_status_read_does_not_wait_for_active_business_lock(mobile, monkeypatch):
    account, headers = register(mobile)
    mobile.get('/v1/state', headers=headers)
    rt = mobile.app.state.runtimes.items[account['user']['id']]
    entered, release = Event(), Event()
    request_id = uuid4().hex

    async def delayed(text, source):
        entered.set()
        while not release.is_set():
            await asyncio.sleep(.01)
        return {'text': text, 'response': '完成', 'speak': False}

    monkeypatch.setattr(rt, 'turn', delayed)
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(mobile.post, '/v1/chat', headers=headers,
                              json={'text': '合成测试', 'request_id': request_id})
        try:
            assert entered.wait(5)
            response = mobile.get('/v1/turns/' + request_id, headers=headers)
            assert response.status_code == 200
            assert response.json()['status'] == 'processing' and not response.json()['retryable']
        finally:
            release.set()
        assert pending.result(timeout=5).status_code == 200


def test_ambiguous_business_failure_never_reexecutes(store):
    store.mobile_turn('request-1', 'text:original')
    store.add('memories', content='已提交的约定', kind='fact', source='user')
    store.fail_mobile_turn('request-1')
    status = store.mobile_turn_status('request-1')
    assert status['status'] == 'failed' and not status['retryable']
    import pytest
    with pytest.raises(ValueError):
        store.mobile_turn('request-1', 'text:original')
    assert len(store.list('memories')) == 1
