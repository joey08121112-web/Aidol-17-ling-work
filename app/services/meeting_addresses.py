"""Resolve saved meeting coordinates without delaying recording or page reads."""
import asyncio
from collections import OrderedDict
import time

from ..domain import LocationInput
from .maps import MapService


class MeetingAddresses:
    def __init__(self, config):
        self.config = config
        self.running = set()
        self.retry_after = OrderedDict()
        self.slots = asyncio.Semaphore(2)

    def schedule(self, background, store, meetings):
        if store is None or not self.config.amap_key:
            return
        scheduled = 0
        for meeting in meetings:
            location = meeting.get('location') or {}
            key = (str(store.path), meeting['id'])
            if (location.get('address') or location.get('source', 'gps') != 'gps'
                    or key in self.running or self.retry_after.get(key, 0) > time.monotonic()):
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
        try:
            async with self.slots:
                async with asyncio.timeout(12):
                    place = await MapService(self.config).reverse(point)
                address = place.get('address')
                if place.get('available') and isinstance(address, str) and address.strip():
                    store.save_work_meeting_address(meeting_id, location, address.strip())
        except Exception:
            # Keep coordinates for a later retry; never invent an address or log locations/keys.
            pass
        finally:
            self.running.discard(key)
            self.retry_after[key] = time.monotonic() + 60
            self.retry_after.move_to_end(key)
            while len(self.retry_after) > 512:
                self.retry_after.popitem(last=False)
