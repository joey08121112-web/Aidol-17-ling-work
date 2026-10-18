"""Resolve saved meeting coordinates without delaying recording or page reads."""
import asyncio
import time

from ..domain import LocationInput
from .maps import MapService


class MeetingAddresses:
    def __init__(self, config, clock=time.time, sleep=asyncio.sleep):
        self.config = config
        self.clock, self.sleep = clock, sleep
        self.running = set()
        self.tasks = set()
        self.slots = asyncio.Semaphore(2)
        self.closed = False

    def schedule(self, background, store, meetings):
        if self.closed or store is None or not self.config.amap_key:
            return
        scheduled = 0
        for meeting in meetings:
            location = meeting.get('location') or {}
            key = (str(store.path), meeting['id'])
            if (location.get('address') or location.get('source', 'gps') != 'gps'
                    or key in self.running or (meeting.get('address_attempts', 0) >= 4
                        and meeting.get('address_retry_at') is None)):
                continue
            try:
                point = LocationInput.model_validate(location)
            except ValueError:
                continue
            if len(self.running) >= 8 or scheduled >= 2:
                break
            self.running.add(key)
            background.add_task(self.resolve, store, meeting['id'], dict(location), point, key)
            scheduled += 1

    async def resolve(self, store, meeting_id, location, point, key):
        retry = await self.attempt(store, meeting_id, location, point)
        if retry is not None and not self.closed:
            task = asyncio.create_task(self.retry(store, meeting_id, location, point, key, retry))
            self.tasks.add(task)
            task.add_done_callback(self.tasks.discard)
        else:
            self.running.discard(key)

    async def retry(self, store, meeting_id, location, point, key, delay):
        try:
            while delay is not None and not self.closed:
                await self.sleep(delay)
                delay = await self.attempt(store, meeting_id, location, point)
        finally:
            self.running.discard(key)

    async def attempt(self, store, meeting_id, location, point):
        attempt = None
        try:
            async with self.slots:
                claimed = store.claim_meeting_address(meeting_id, location, self.clock())
                if claimed is None:
                    return None
                if 'wait' in claimed:
                    return claimed['wait']
                attempt = claimed['attempt']
                async with asyncio.timeout(12):
                    place = await MapService(self.config).reverse(point)
                address = place.get('address')
                if place.get('available') and isinstance(address, str) and address.strip():
                    store.save_work_meeting_address(meeting_id, location, address.strip())
                    return None
        except Exception:
            # Keep coordinates for a later retry; never invent an address or log locations/keys.
            pass
        if attempt is not None:
            retry_at = self.clock() + 30 if attempt < 4 else None
            if store.finish_meeting_address_attempt(meeting_id, location, attempt, retry_at) and retry_at is not None:
                return 30
        return None

    async def close(self):
        self.closed = True
        for task in list(self.tasks):
            task.cancel()
        if self.tasks:
            await asyncio.gather(*list(self.tasks), return_exceptions=True)
        self.tasks.clear()
        self.running.clear()
