"""AMap v3/v4 protocol boundary; errors must not expose key-bearing URLs."""
import httpx


async def amap_get(client, key, path, params):
    version = "v4" if path == "direction/bicycling" else "v3"
    try:
        result = await client.get(f"https://restapi.amap.com/{version}/" + path,
                                  params={**params, "key": key})
        result.raise_for_status()
        body = result.json()
    except (httpx.HTTPError, ValueError):
        raise ValueError("高德服务暂不可用，请稍后重试") from None
    if not isinstance(body, dict):
        raise ValueError("高德返回了无效数据")
    success = str(body.get("errcode")) == "0" if version == "v4" else str(body.get("status")) == "1"
    if not success:
        raise ValueError("高德请求失败，请检查 Key 权限、配额和参数")
    return body


async def amap_image(client, key, params):
    """Fetch a bounded official image; never return or log the key-bearing upstream URL."""
    try:
        async with client.stream("GET", "https://restapi.amap.com/v3/staticmap", params={**params, "key": key}) as result:
            result.raise_for_status()
            content = bytearray()
            async for block in result.aiter_bytes():
                content.extend(block)
                if len(content) > 4 * 1024 * 1024:
                    raise ValueError("地图图片过大")
            data = bytes(content)
            if data.startswith(b"\x89PNG\r\n\x1a\n"):
                return data, "image/png"
            if data.startswith(b"\xff\xd8\xff"):
                return data, "image/jpeg"
            raise ValueError("地图图片暂不可用，请检查高德静态地图权限与配额")
    except httpx.HTTPError:
        raise ValueError("地图图片暂不可用，请稍后重试") from None
