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
        'local_env': Path('.env').exists(), 'database_path': config.database_path,
        'url': f"http://{os.getenv('HOST', '127.0.0.1')}:{os.getenv('PORT', '8000')}",
        'capabilities': config.capabilities(),
        'tts_resource': config.speech.tts_resource_id,
        'asr_resource': config.speech.asr_flash_resource_id if config.asr_mode == 'flash' else config.speech.asr_resource_id,
        'hardware': 'Pair headset in the OS, select its microphone/output in the browser.'
    }, indent=2, ensure_ascii=True))


if __name__ == '__main__':
    main()
