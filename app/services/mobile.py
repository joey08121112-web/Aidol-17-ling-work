"""One isolated orchestrator per active account, bounded foreground HTTP turns."""
import asyncio
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import HTTPException

from ..domain import Settings, TextInput
from ..events import EventBus
from ..storage import Store
from .agent import Agent
from .conversation import ConversationService


class MobileConversation(ConversationService):
    history = None

    async def reply(self, text, instructions):
        return await super().reply(text, instructions, self.history)


class MobileRuntime:
    def __init__(self, config, path):
        fresh = not Path(path).exists()
        self.store = Store(str(path))
        if fresh:
            self.store.save_settings(Settings(auto_mode=False, preferences=""))
        settings = self.store.settings()
        if settings.auto_mode:
            settings.auto_mode = False
            self.store.save_settings(settings)
        self.agent = Agent(config, self.store, EventBus(self.store))
        self.agent.conversation = MobileConversation(config)
        self.lock = asyncio.Lock()
        self.pending_location = None

    def record_passive_location(self, point):
        previous = self.pending_location or self.agent.location or {}
        if previous.get('observed_at', '') <= point['observed_at']:
            self.pending_location = point
        if not self.lock.locked():
            self.apply_passive_location()

    def apply_passive_location(self):
        if self.pending_location is not None:
            previous = self.agent.location or {}
            if previous.get('observed_at', '') <= self.pending_location['observed_at']:
                self.agent.location = self.pending_location
            self.pending_location = None

    def conversation_history(self):
        history = list(reversed(self.store.conversation_history(12)))
        return [
            {'role': 'assistant' if row['speaker'] == 'assistant' else 'user', 'content': row['text'][:2000]}
            for row in history]

    async def turn(self, text, source, interaction_source='ambient_capture', on_response=None, response_provider=None):
        self.agent.conversation.history = self.conversation_history()
        # Work audio is evidence, never a command, regardless of client source labels.
        work_audio = source == 'asr' and self.store.settings().mode == 'work'
        directed = interaction_source in {'direct_question', 'continuous_call'} and not work_audio
        content = text
        silent = work_audio or (interaction_source == 'ambient_capture' and self.store.settings().mode == 'work')
        result = await self.agent.ingest(TextInput(text=content, source=source), respond=True,
                                        directed=directed, on_response=on_response,
                                        response_provider=response_provider, silent=silent, recording=work_audio,
                                        allow_mode_switch=interaction_source != 'continuous_call')
        answer = result.get('response', '')
        if answer and result.get('response_complete', True) and self.store.settings().retain_transcripts:
            self.store.add('transcripts', speaker='assistant', text=answer, mode=result['mode'], source=source)
        return {'text': text, **result, 'speak': not silent and (result['mode'] != 'work' or result.get('explicit_reply', False)),
                'notice': '模型暂不可用，本次为基础规则回复' if result['interaction'] == 'rules'
                    and (result['mode'] != 'work' or result.get('explicit_reply', False)) else ''}


class MobileRuntimes:
    def __init__(self, config, directory, capacity=128):
        self.config, self.directory, self.capacity = config, Path(directory), capacity
        self.items = {}

    def obtain(self, user_id):
        """The account's orchestrator without acquiring its business lock.

        Snapshot reads must not queue behind a live turn; creation and idle
        eviction follow the same capacity rules as the locking path."""
        runtime = self.items.get(user_id)
        if runtime is None:
            if len(self.items) >= self.capacity:
                idle = next((key for key, value in self.items.items() if not value.lock.locked()), None)
                if idle is None:
                    raise HTTPException(503, '服务器繁忙，请稍后重试')
                del self.items[idle]
            runtime = self.items[user_id] = MobileRuntime(self.config, self.directory / (user_id + '.sqlite3'))
        return runtime

    @asynccontextmanager
    async def use(self, user_id, wait_seconds=0):
        runtime = self.obtain(user_id)
        if runtime.lock.locked() and not wait_seconds:
            raise HTTPException(409, '你的上一项操作仍在进行，请稍后再试')
        try:
            if wait_seconds:
                await asyncio.wait_for(runtime.lock.acquire(), wait_seconds)
            else:
                await runtime.lock.acquire()
        except TimeoutError:
            raise HTTPException(409, '上一项操作仍在收尾，请稍后再开始') from None
        try:
            runtime.apply_passive_location()
            yield runtime
        finally:
            runtime.lock.release()
            runtime.apply_passive_location()
