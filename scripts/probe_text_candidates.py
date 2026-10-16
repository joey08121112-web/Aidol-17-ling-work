"""Check only models explicitly provided in the user's reference archive."""
import asyncio
import io
import json
import zipfile
import argparse
import httpx
from dotenv import dotenv_values, set_key


async def main(archive_path):
    with zipfile.ZipFile(archive_path) as archive:
        values = dotenv_values(stream=io.StringIO(archive.read('jiujiu-web-main/.env.local').decode('utf-8-sig')))
    for prefix in ('AI', 'MEMORY_AI', 'EVAL_AI'):
        model = values[prefix + '_MODEL_NAME']
        if prefix == 'MEMORY_AI' and model == values['AI_MODEL_NAME']:
            continue
        try:
            async with httpx.AsyncClient(timeout=30) as client:
                response = await client.post(values[prefix + '_BASE_URL'].rstrip('/') + '/chat/completions',
                    headers={'Authorization':'Bearer ' + values[prefix + '_API_KEY']},
                    json={'model':model, 'max_tokens':100, 'thinking':{'type':'disabled'},
                          'messages':[{'role':'user','content':'只回复：测试通过。'}]})
                print(json.dumps({'model':model, 'status':response.status_code,
                    'error_code':response.json().get('error',{}).get('code')}, ensure_ascii=True))
                if response.is_success:
                    for target, source in [('TEXT_API_KEY','_API_KEY'),('TEXT_BASE_URL','_BASE_URL'),('TEXT_MODEL','_MODEL_NAME')]:
                        set_key('.env',target,values[prefix + source])
                    print('Working text model saved locally; credentials withheld.')
                    return
        except Exception as error:
            print(json.dumps({'model':model,'error_type':type(error).__name__}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', help='User-authorized reference project ZIP')
    args = parser.parse_args()
    asyncio.run(main(args.archive))
