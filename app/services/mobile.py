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
        self.agent = Agent(config, self.store, EventBus(self.store))
        self.agent.conversation = MobileConversation(config)
        self.lock = asyncio.Lock()

    async def turn(self, text, source):
        history = list(reversed(self.store.list('transcripts', 12)))
        self.agent.conversation.history = [
            {'role': 'assistant' if row['speaker'] == 'assistant' else 'user', 'content': row['text'][:2000]}
            for row in history]
        result = await self.agent.ingest(TextInput(text=text, source=source), respond=True)
        answer = result.get('response', '')
        if answer and self.store.settings().retain_transcripts:
            self.store.add('transcripts', speaker='assistant', text=answer, mode=result['mode'], source=source)
        return {'text': text, **result, 'speak': result['mode'] != 'work',
                'notice': '模型暂不可用，本次为基础规则回复' if result['interaction'] == 'rules' and result['mode'] != 'work' else ''}


class MobileRuntimes:
    def __init__(self, config, directory, capacity=128):
        self.config, self.directory, self.capacity = config, Path(directory), capacity
        self.items = {}

    @asynccontextmanager
    async def use(self, user_id):
        runtime = self.items.get(user_id)
        if runtime is None:
            if len(self.items) >= self.capacity:
                idle = next((key for key, value in self.items.items() if not value.lock.locked()), None)
                if idle is None:
                    raise HTTPException(503, '服务器繁忙，请稍后重试')
                del self.items[idle]
            runtime = self.items[user_id] = MobileRuntime(self.config, self.directory / (user_id + '.sqlite3'))
        if runtime.lock.locked():
            raise HTTPException(409, '你的上一项操作仍在进行，请稍后再试')
        async with runtime.lock:
            yield runtime
