from dataclasses import dataclass, field
import os
from dotenv import load_dotenv
from .providers.volc_speech import VolcSpeechConfig

load_dotenv()


@dataclass
class Config:
    database_path: str = field(default_factory=lambda: os.getenv("DATABASE_PATH", "data/aidol.sqlite3"))
    app_token: str = field(default_factory=lambda: os.getenv("APP_TOKEN", ""))
    doubao_key: str = field(default_factory=lambda: os.getenv("DOUBAO_API_KEY", ""))
    doubao_url: str = field(default_factory=lambda: os.getenv("DOUBAO_REALTIME_URL", "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue"))
    doubao_model: str = field(default_factory=lambda: os.getenv("DOUBAO_MODEL", "1.2.6.1"))
    doubao_voice: str = field(default_factory=lambda: os.getenv("DOUBAO_VOICE", "zh_female_vv_jupiter_bigtts"))
    qwen_key: str = field(default_factory=lambda: os.getenv("QWEN_API_KEY", ""))
    qwen_url: str = field(default_factory=lambda: os.getenv("QWEN_REALTIME_URL", "wss://maas.qianwenaiapi.com/api-ws/v1/realtime"))
    qwen_model: str = field(default_factory=lambda: os.getenv("QWEN_MODEL", "qwen3.8-omni-flash-realtime"))
    qwen_voice: str = field(default_factory=lambda: os.getenv("QWEN_VOICE", "Tina"))
    text_key: str = field(default_factory=lambda: os.getenv("TEXT_API_KEY", ""))
    text_url: str = field(default_factory=lambda: os.getenv("TEXT_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3"))
    text_model: str = field(default_factory=lambda: os.getenv("TEXT_MODEL", ""))
    amap_key: str = field(default_factory=lambda: os.getenv("AMAP_API_KEY", ""))
    proxy: str | None = field(default_factory=lambda: os.getenv("UPSTREAM_PROXY") or None)
    speech: VolcSpeechConfig = field(default_factory=VolcSpeechConfig.from_env)
    asr_mode: str = field(default_factory=lambda: os.getenv("SPEECH_ASR_MODE", "stream"))

    def capabilities(self):
        return {"doubao": bool(self.doubao_key), "qwen": bool(self.qwen_key),
                "text_llm": bool(self.text_key and self.text_model), "amap": bool(self.amap_key),
                "asr": self.speech.asr_ready, "tts": self.speech.tts_ready, "asr_mode": self.asr_mode,
                "text_model": self.text_model, "thinking": False,
                "anc_control": False, "messaging": "draft_only", "audio": "pcm16/16000 → pcm16/24000"}
