"""Bounded Bocha Web Search adapter, adapted from the colleague demo's search.py.

Only the explicit query is sent to Bocha. Provider errors never echo credentials,
URLs from HTTP exceptions, or response bodies. Results are untrusted evidence.
"""
from datetime import date
from urllib.parse import urlsplit

import httpx


def public_link(value):
    if not isinstance(value, str) or len(value) > 2048:
        return ""
    try:
        parsed = urlsplit(value)
        return value if parsed.scheme in {"https", "http"} and parsed.hostname and not parsed.username and not parsed.password else ""
    except ValueError:
        return ""


def validate_freshness(value):
    if value in {"noLimit", "oneDay", "oneWeek", "oneMonth", "oneYear"}:
        return value
    try:
        parts = value.split("..")
        if len(parts) not in {1, 2} or any(len(part) != 10 for part in parts):
            raise ValueError()
        days = [date.fromisoformat(part) for part in parts]
        if days[0] > days[-1]:
            raise ValueError()
        return value
    except (ValueError, AttributeError):
        raise ValueError("搜索时间范围无效") from None


class BochaSearch:
    def __init__(self, config):
        self.config = config

    async def search(self, query, freshness="noLimit", count=5):
        query = query.strip()
        if not query or len(query) > 500 or not 1 <= count <= 10:
            raise ValueError("搜索词需为 1–500 字，结果数量需为 1–10")
        validate_freshness(freshness)
        if not self.config.bocha_key:
            return {"available": False, "source": "bocha", "results": [], "reason": "尚未配置博查搜索 Key"}
        try:
            async with httpx.AsyncClient(timeout=12, proxy=self.config.proxy) as client:
                async with client.stream("POST", "https://api.bochaai.com/v1/web-search",
                        headers={"Authorization": "Bearer " + self.config.bocha_key},
                        json={"query": query, "freshness": freshness, "count": count, "summary": True}) as response:
                    response.raise_for_status()
                    raw = bytearray()
                    async for chunk in response.aiter_bytes():
                        raw.extend(chunk)
                        if len(raw) > 1024 * 1024:
                            raise ValueError()
                    import json
                    body = json.loads(raw)
            if not isinstance(body, dict) or str(body.get("code", 200)) != "200":
                raise ValueError()
            data = body.get("data", body)
            if not isinstance(data, dict):
                raise ValueError()
            pages = data.get("webPages", {})
            values = pages.get("value", []) if isinstance(pages, dict) else pages
            if not values:
                values = data.get("results", [])
            if not isinstance(values, list):
                raise ValueError()
            results = []
            for item in values[:count]:
                if not isinstance(item, dict):
                    continue
                url = public_link(item.get("url", item.get("link")))
                if not url:
                    continue
                def field(*keys, limit=1500):
                    return next((item[k].strip()[:limit] for k in keys if isinstance(item.get(k), str)), "")
                results.append({"title": field("name", "title", limit=200), "url": url,
                    "summary": field("summary", "snippet", "description"),
                    "site": field("siteName", "source", limit=100),
                    "published": field("datePublished", "publishTime", limit=80)})
            return {"available": True, "source": "bocha", "query": query, "results": results,
                    "reason": "" if results else "未找到可核对的网页结果"}
        except (httpx.HTTPError, ValueError, TypeError):
            raise ValueError("博查搜索暂不可用，请检查后端配置、权限和额度") from None
