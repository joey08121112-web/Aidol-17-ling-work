"""Low-latency text interaction model, separate from ASR and voice rendering."""
import httpx


class ConversationService:
    def __init__(self, config):
        self.config = config

    async def reply(self, text, instructions, history=None):
        if not self.config.text_key or not self.config.text_model:
            return None
        try:
            async with httpx.AsyncClient(timeout=15, proxy=self.config.proxy) as client:
                result = await client.post(self.config.text_url.rstrip('/') + '/chat/completions',
                    headers={'Authorization': 'Bearer ' + self.config.text_key},
                    json={'model': self.config.text_model, 'thinking': {'type': 'disabled'},
                          'temperature': 0.5, 'max_tokens': 240,
                          'messages': [{'role': 'system', 'content': instructions + ' 用简短中文回应，通常不超过80字。'}]
                                      + (history or []) + [{'role': 'user', 'content': text}]})
                result.raise_for_status()
                content = result.json()['choices'][0]['message']['content']
                return content.strip() if isinstance(content, str) else None
        except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError):
            return None
