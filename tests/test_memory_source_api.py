def test_remember_transcript_keeps_source_and_is_idempotent(client, runtime):
    store = runtime.store
    row = store.add('transcripts', speaker='测试同学', text='演示入口在东门。', mode='work', source='text')
    first = client.post('/v1/memories/from-transcript/' + row['id']).json()
    second = client.post('/v1/memories/from-transcript/' + row['id']).json()
    assert first['id'] == second['id']
    assert first['source'] == 'transcript:' + row['id']
    assert client.get('/v1/memories/' + first['id'] + '/source').json()['text'] == row['text']
    response = client.put('/v1/memories/' + first['id'], json={'content': '入口改为南门', 'kind': 'fact', 'pinned': True})
    assert response.status_code == 200
    updated = client.get('/v1/state').json()['memories'][0]
    assert updated['content'] == '入口改为南门' and updated['pinned'] == 1 and updated['updated_at']
    assert client.get('/v1/memories/' + first['id'] + '/source').json()['text'] == '演示入口在东门。'
    # Repeated save never silently reverts a user's correction or favorite.
    assert client.post('/v1/memories/from-transcript/' + row['id']).json()['content'] == '入口改为南门'


def test_missing_and_cleared_source_are_explicit(client, runtime):
    row = runtime.store.add('transcripts', speaker='我', text='原始内容', mode='work', source='asr')
    saved = client.post('/v1/memories/from-transcript/' + row['id']).json()
    assert saved['source'] == 'transcript:' + row['id']
    manual = client.post('/v1/memories', json={'kind': 'fact', 'content': '手动记忆'}).json()
    assert client.get('/v1/memories/' + manual['id'] + '/source').status_code == 404
    assert client.post('/v1/memories/from-transcript/missing').status_code == 404
    assert client.put('/v1/memories/missing', json={'content': '更正', 'kind': 'fact'}).status_code == 404
    assert client.request('DELETE', '/v1/data', json={'password': 'test-password-123'}).status_code == 200
    assert client.get('/v1/memories/' + saved['id'] + '/source').status_code == 404


def test_new_account_defaults_to_foreground_speech_and_manual_modes(client):
    state = client.get('/v1/state').json()
    assert state['settings']['provider'] == 'cascade'
    assert state['settings']['auto_mode'] is False
    assert state['settings']['life_poll_enabled'] is False
    assert state['capabilities']['asr_mode'] == 'stream'


def test_blank_memory_content_is_validation_error(client):
    assert client.post('/v1/memories', json={'content': ' \n ', 'kind': 'fact'}).status_code == 422
    assert client.put('/v1/memories/any', json={'content': ' \t ', 'kind': 'fact'}).status_code == 422
