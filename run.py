"""Run the Android account API; environment overrides are loaded from .env."""
import os
from dotenv import load_dotenv
import uvicorn


def main():
    load_dotenv()
    trusted_proxies = os.getenv("FORWARDED_ALLOW_IPS", "").strip()
    uvicorn.run("app.main:create_hosted_app", factory=True,
                host=os.getenv("HOST", "127.0.0.1"), port=int(os.getenv("PORT", "8108")),
                workers=1, proxy_headers=bool(trusted_proxies),
                forwarded_allow_ips=trusted_proxies)


if __name__ == "__main__":
    main()
