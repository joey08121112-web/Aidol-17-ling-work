"""Account-local saved addresses and the current explicit commute. No provider calls."""
import json
from pathlib import Path
from uuid import uuid4
from datetime import datetime, timezone


def stamp():
    return datetime.now(timezone.utc).isoformat()


class CommuteStorage:
    @staticmethod
    def _commute_read(db, key, default):
        row = db.execute('SELECT payload FROM commute_data WHERE key=?', (key,)).fetchone()
        return json.loads(row[0]) if row else default

    @staticmethod
    def _commute_write(db, key, value):
        db.execute('INSERT INTO commute_data VALUES(?,?) ON CONFLICT(key) DO UPDATE SET payload=excluded.payload',
                   (key, json.dumps(value, ensure_ascii=False)))

    def _addresses(self, db):
        values = self._commute_read(db, 'addresses', None)
        if values is not None:
            return values
        # Existing home/work settings remain usable without another geocode request.
        row = db.execute('SELECT value FROM settings WHERE id=1').fetchone()
        settings = json.loads(row[0]) if row else {}
        return [dict(id='legacy-' + key, tag=key, name=settings.get(key + '_name') or label,
                     address=settings.get(key + '_name') or label, city=settings.get(key + '_city') or settings.get('transit_city', '上海'),
                     longitude=settings[key + '_lon'], latitude=settings[key + '_lat'], adcode='', updated_at='')
                for key, label in [('home', '家'), ('work', '公司')]
                if settings.get(key + '_lon') is not None and settings.get(key + '_lat') is not None]

    def saved_addresses(self):
        with self.connect() as db:
            return self._addresses(db)

    def save_address(self, address_id, value):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            addresses = self._addresses(db)
            if any(a['id'] != address_id and a['tag'] == value['tag'] and value['tag'] != 'custom' for a in addresses):
                raise ValueError('该标签已有地址，请编辑已有地址')
            if len(addresses) >= 40 and not any(a['id'] == address_id for a in addresses):
                raise ValueError('最多保存40个常用地址')
            address = {**value, 'id': address_id, 'updated_at': stamp()}
            previous = next((a for a in addresses if a['id'] == address_id), None)
            addresses = [address if a['id'] == address_id else a for a in addresses]
            if previous is None:
                addresses.append(address)
            self._commute_write(db, 'addresses', addresses)
            self._mirror_places(db, addresses)
            return address

    def delete_address(self, address_id):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            before = self._addresses(db)
            after = [a for a in before if a['id'] != address_id]
            if len(before) == len(after):
                return False
            self._commute_write(db, 'addresses', after)
            self._mirror_places(db, after)
            return True

    @staticmethod
    def _mirror_places(db, addresses):
        row = db.execute('SELECT value FROM settings WHERE id=1').fetchone()
        settings = json.loads(row[0]) if row else {}
        for tag in ['home', 'work']:
            address = next((a for a in addresses if a['tag'] == tag), None)
            settings.update({tag + '_lon': address['longitude'] if address else None,
                             tag + '_lat': address['latitude'] if address else None,
                             tag + '_name': address['name'] if address else ('家' if tag == 'home' else '公司'),
                             tag + '_city': address['city'] if address else ''})
        db.execute('INSERT INTO settings VALUES(1,?) ON CONFLICT(id) DO UPDATE SET value=excluded.value', (json.dumps(settings, ensure_ascii=False),))

    def commute_dialogue(self):
        with self.connect() as db:
            return self._commute_read(db, 'dialogue', {})

    def save_commute_dialogue(self, value):
        with self.connect() as db:
            self._commute_write(db, 'dialogue', value)

    def commute_journey(self):
        with self.connect() as db:
            return self._commute_read(db, 'journey', {})

    def start_commute(self, value):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            old = self._commute_read(db, 'journey', {})
            if old.get('id') == value['id']:
                if any(old.get(k) != value[k] for k in ['origin_mode', 'origin', 'destination', 'transport']):
                    raise ValueError('同一通勤请求的内容不一致')
                return old
            if old.get('active'):
                raise ValueError('请先结束当前通勤')
            if db.execute("SELECT 1 FROM work_meetings WHERE status='recording' LIMIT 1").fetchone():
                raise ValueError('请先结束当前工作录音')
            value = {**value, 'active': True, 'started_at': stamp(), 'route': {}, 'error': '',
                     'last_attempt': 0, 'refreshing': False, 'request_id': ''}
            self._commute_write(db, 'journey', value)
            return value

    def stop_commute(self, journey_id):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            value = self._commute_read(db, 'journey', {})
            if value.get('id') != journey_id:
                return None
            value.update(active=False, refreshing=False, ended_at=stamp())
            self._commute_write(db, 'journey', value)
            return value

    def claim_commute_refresh(self, journey_id, request_id, location, now):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            value = self._commute_read(db, 'journey', {})
            if value.get('id') != journey_id or not value.get('active'):
                raise LookupError('通勤已结束或不存在')
            if value['origin_mode'] == 'gps' and location is None:
                raise ValueError('需要近期手机定位')
            if value['origin_mode'] == 'fixed' and location is not None:
                raise ValueError('自定义起点不接受手机位置替换')
            previous = value.get('location', {})
            if location and previous.get('observed_at', '') > location['observed_at']:
                return value, False
            if value.get('request_id') == request_id or now - value.get('last_attempt', 0) < (45 if value.get('refreshing') else 30):
                return value, False
            value.update(request_id=request_id, last_attempt=now, refreshing=True, error='')
            if location:
                value['location'] = location
            self._commute_write(db, 'journey', value)
            return value, True

    def finish_commute_refresh(self, journey_id, request_id, route):
        if not Path(self.path).exists():
            return {}
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            value = self._commute_read(db, 'journey', {})
            if value.get('id') != journey_id or not value.get('active') or value.get('request_id') != request_id:
                return value
            value['refreshing'] = False
            if route.get('available'):
                value.update(route=route, error='', updated_at=stamp())
                row = db.execute('SELECT value FROM settings WHERE id=1').fetchone()
                settings = json.loads(row[0]) if row else {}
                if value['origin_mode'] == 'gps' and not value.get('arrival_draft_id') and 0 <= route.get('eta_seconds', 1e9) <= settings.get('arrival_notice_minutes', 5) * 60:
                    draft_id = uuid4().hex
                    minutes = max(1, round(route['eta_seconds'] / 60))
                    contact = settings.get('contact', '同事')
                    message = f"{contact}，路线估算我约{minutes}分钟后到达{value['destination']['name']}，实际时间可能随路况变化。"
                    db.execute("INSERT INTO drafts(id,contact,text,status,created_at) VALUES(?,?,?,'pending',?)", (draft_id, contact, message, stamp()))
                    value['arrival_draft_id'] = draft_id
            else:
                value['error'] = route.get('reason') or '路线暂未更新，请稍后重试'
            self._commute_write(db, 'journey', value)
            return value
