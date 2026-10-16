import asyncio
from datetime import datetime, timezone
import json
from uuid import uuid4


class EventBus:
    """Bounded per-client queues: slow dashboards must never block the audio loop."""
    def __init__(self, store):
        self.store = store
        self.subscribers = set()

    def subscribe(self):
        queue = asyncio.Queue(maxsize=128)
        self.subscribers.add(queue)
        return queue

    def unsubscribe(self, queue):
        self.subscribers.discard(queue)

    async def emit(self, event_type, payload, persist=True):
        event = {"id": uuid4().hex, "type": event_type, "payload": payload,
                 "created_at": datetime.now(timezone.utc).isoformat()}
        if persist:
            self.store.add("events", type=event_type, payload=json.dumps(payload, ensure_ascii=False))
        for queue in tuple(self.subscribers):
            if queue.full():
                queue.get_nowait()
            queue.put_nowait(event)
        return event
