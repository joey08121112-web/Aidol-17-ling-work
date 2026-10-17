from dataclasses import dataclass, field
import os
from dotenv import load_dotenv
from .providers.volc_speech import VolcSpeechConfig
from .providers.qwen_realtime import QwenRealtimeConfig
from .providers.qwen_work_asr import QwenWorkAsrConfig

load_dotenv()


@dataclass
class Config:
    database_path: str = field(default_factory=lambda: os.getenv("DATABASE_PATH", "data/aidol.sqlite3"))
    text_key: str = field(default_factory=lambda: os.getenv("TEXT_API_KEY", ""))
    text_url: str = field(default_factory=lambda: os.getenv("TEXT_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3"))
    text_model: str = field(default_factory=lambda: os.getenv("TEXT_MODEL", ""))
    amap_key: str = field(default_factory=lambda: os.getenv("AMAP_API_KEY", ""))
    bocha_key: str = field(default_factory=lambda: os.getenv("BOCHA_API_KEY", ""), repr=False)
    proxy: str | None = field(default_factory=lambda: os.getenv("UPSTREAM_PROXY") or None)
    speech: VolcSpeechConfig = field(default_factory=VolcSpeechConfig.from_env)
    qwen: QwenRealtimeConfig = field(default_factory=QwenRealtimeConfig.from_env)
    work_asr: QwenWorkAsrConfig = field(default_factory=QwenWorkAsrConfig.from_env)
    asr_mode: str = field(default_factory=lambda: os.getenv("SPEECH_ASR_MODE", "stream"))

    def capabilities(self):
        return {"text_llm": bool(self.text_key and self.text_model), "amap": bool(self.amap_key),
                "web_search": bool(self.bocha_key), "voice_stream": self.speech.asr_ready,
                "speech_stream": self.speech.tts_ready,
                "voice_reply_stream": self.speech.asr_ready and self.speech.tts_ready,
                "voice_realtime": self.qwen.ready,
                "voice_prepare": self.qwen.ready,
                "voice_continuous_qa": self.qwen.ready, "voice_duplex": self.qwen.ready,
                "voice_barge_in": self.qwen.ready, "work_meetings": True,
                "work_asr": self.work_asr.ready, "work_asr_model": "qwen-audio-3.1-asr-flash-filetrans",
                "work_whole_audio": True,
                "voice_realtime_provider": "qwen" if self.qwen.ready else "",
                "asr": self.speech.asr_ready, "tts": self.speech.tts_ready, "asr_mode": self.asr_mode,
                "text_model": self.text_model, "thinking": False,
                "anc_control": False, "messaging": "draft_only", "audio": "pcm16/16000 → pcm16/24000"}
