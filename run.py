"""python run.py; environment overrides are loaded from .env."""
import os
from dotenv import load_dotenv
import uvicorn

load_dotenv()
if __name__ == "__main__":
    host = os.getenv("HOST", "127.0.0.1")
    if host not in {"127.0.0.1", "localhost", "::1"} and not os.getenv("APP_TOKEN"):
        raise SystemExit("公网/LAN 监听前请在 .env 设置 APP_TOKEN，并通过 HTTPS 代理访问麦克风。")
    uvicorn.run("app.main:app", host=host, port=int(os.getenv("PORT", "8000")), ws_max_size=65536)
