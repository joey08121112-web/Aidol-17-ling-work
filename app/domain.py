from enum import Enum
from pydantic import BaseModel, Field, model_validator, field_validator


class Mode(str, Enum):
    work = "work"
    commute = "commute"
    life = "life"


class Settings(BaseModel):
    auto_mode: bool = True
    mode: Mode = Mode.life
    provider: str = Field(default="cascade", pattern="^(auto|doubao|qwen|cascade|demo)$")
    work_start: int = Field(default=9, ge=0, le=23)
    work_end: int = Field(default=18, ge=1, le=24)
    timezone: str = "Asia/Shanghai"
    life_poll_enabled: bool = False
    life_poll_seconds: int = Field(default=300, ge=60, le=3600)
    listen_seconds: int = Field(default=30, ge=5, le=60)
    vad_enabled: bool = True
    vad_threshold: float = Field(default=0.025, ge=0.005, le=0.2)
    preferences: str = Field(default="清淡饮食，喜欢安静的咖啡馆和轻松的笑话。", max_length=2000)
    destination_name: str = Field(default="公司", max_length=100)
    transport: str = Field(default="driving", pattern="^(driving|walking|transit|bicycling)$")
    transit_city: str = Field(default="上海", min_length=1, max_length=80)
    destination_lon: float | None = Field(default=None, ge=-180, le=180)
    destination_lat: float | None = Field(default=None, ge=-90, le=90)
    contact: str = Field(default="老板", max_length=100)
    arrival_notice_minutes: int = Field(default=5, ge=1, le=60)
    retain_transcripts: bool = True

    @model_validator(mode="after")
    def valid_settings(self):
        from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
        try:
            ZoneInfo(self.timezone)
        except (ZoneInfoNotFoundError, ValueError):
            raise ValueError("timezone 必须是有效 IANA 时区")
        if (self.destination_lon is None) != (self.destination_lat is None):
            raise ValueError("目的地经纬度必须同时填写")
        if self.work_end <= self.work_start:
            raise ValueError("工作结束时间必须晚于开始时间")
        return self


class TextInput(BaseModel):
    text: str = Field(min_length=1, max_length=6000)
    speaker: str = Field(default="用户", max_length=80)
    source: str = Field(default="text", pattern="^(text|asr|demo)$")
    event_id: str | None = Field(default=None, max_length=120)


class LocationInput(BaseModel):
    longitude: float = Field(ge=-180, le=180)
    latitude: float = Field(ge=-90, le=90)
    accuracy: float = Field(default=0, ge=0, le=100000)
    speed: float | None = Field(default=None, ge=0, le=400)
    source: str = Field(default="gps", pattern="^(gps|demo)$")


class MemoryInput(BaseModel):
    content: str = Field(min_length=1, max_length=6000)
    kind: str = Field(default="preference", pattern="^(preference|task|fact|trip)$")

    @field_validator("content")
    @classmethod
    def content_not_blank(cls, value):
        value = value.strip()
        if not value:
            raise ValueError("记忆内容不能为空白")
        return value


class MemoryUpdate(MemoryInput):
    pinned: bool = False


class SpeechInput(BaseModel):
    text: str = Field(min_length=1, max_length=1500)


class NearbyInput(LocationInput):
    keyword: str = Field(default="美食", min_length=1, max_length=80)
    radius: int = Field(default=3000, ge=100, le=50000)
    metro_only: bool = False


class MeetingRequest(BaseModel):
    hours: int = Field(default=24, ge=1, le=168)
    include_demo: bool = False


class RecallRequest(BaseModel):
    question: str = Field(min_length=1, max_length=500)
    include_demo: bool = False


class TaskStatusInput(BaseModel):
    status: str = Field(pattern="^(open|done)$")
