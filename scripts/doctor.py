"""Safe local diagnostics. Does not contact vendors or print secret values."""
import json
import os
import platform
import sqlite3
from pathlib import Path
from app.config import Config


def main():
    config = Config()
    print(json.dumps({
        'python': platform.python_version(), 'sqlite': sqlite3.sqlite_version,
        'local_env': Path('.env').exists(),
        'data_directory': os.getenv('AIDOL_DATA_DIR', 'data/hosted'),
        'url': f"http://{os.getenv('HOST', '127.0.0.1')}:{os.getenv('PORT', '8108')}",
        'local_android_url': 'http://127.0.0.1:8108',
        'local_android_data_directory': str(Path(__file__).resolve().parents[1] / 'data' / 'android-local'),
        'capabilities': config.capabilities(),
        'tts_resource': config.speech.tts_resource_id,
        'asr_resource': config.speech.asr_flash_resource_id if config.asr_mode == 'flash' else config.speech.asr_resource_id,
        'hardware': 'Pair the headset with the Android phone; choose the input and backend in the Android app.'
    }, indent=2, ensure_ascii=True))


if __name__ == '__main__':
    main()
