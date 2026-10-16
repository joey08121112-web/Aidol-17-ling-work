def test_remember_transcript_keeps_source_and_is_idempotent(client):
    store = client.app.state.store
    row = store.add('transcripts', speaker='测试同学', text='演示入口在东门。', mode='work', source='text')
    first = client.post('/api/memories/from-transcript/' + row['id']).json()
    second = client.post('/api/memories/from-transcript/' + row['id']).json()
    assert first['id'] == second['id']
    assert first['source'] == 'transcript:' + row['id']
    assert client.get('/api/memories/' + first['id'] + '/source').json()['text'] == row['text']
    response = client.put('/api/memories/' + first['id'], json={'content': '入口改为南门', 'kind': 'fact', 'pinned': True})
    assert response.status_code == 200
    updated = client.get('/api/memories').json()[0]
    assert updated['content'] == '入口改为南门' and updated['pinned'] == 1 and updated['updated_at']
    assert client.get('/api/memories/' + first['id'] + '/source').json()['text'] == '演示入口在东门。'
    # Repeated save never silently reverts a user's correction or favorite.
    assert client.post('/api/memories/from-transcript/' + row['id']).json()['content'] == '入口改为南门'


def test_demo_source_preserved_and_missing_source_explicit(client):
    row = client.app.state.store.add('transcripts', speaker='我', text='模拟内容', mode='work', source='demo')
    saved = client.post('/api/memories/from-transcript/' + row['id']).json()
    assert saved['source'] == 'demo_transcript:' + row['id']
    manual = client.post('/api/memories', json={'kind': 'fact', 'content': '手动记忆'}).json()
    assert client.get('/api/memories/' + manual['id'] + '/source').status_code == 404
    assert client.post('/api/memories/from-transcript/missing').status_code == 404
    assert client.put('/api/memories/missing', json={'content': '更正', 'kind': 'fact'}).status_code == 404
    assert client.delete('/api/data').status_code == 200
    assert client.get('/api/memories/' + saved['id'] + '/source').status_code == 404


def test_new_workspace_defaults_to_verified_continuous_pipeline(client):
    state = client.get('/api/state').json()
    assert state['settings']['provider'] == 'cascade'
    assert state['capabilities']['asr_mode'] == 'stream'


def test_blank_memory_content_is_validation_error(client):
    assert client.post('/api/memories', json={'content': ' \n ', 'kind': 'fact'}).status_code == 422
    assert client.put('/api/memories/any', json={'content': ' \t ', 'kind': 'fact'}).status_code == 422
