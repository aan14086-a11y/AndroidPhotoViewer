from pathlib import Path
import base64
import gzip

payload = ''.join(
    part.read_text(encoding='utf-8').strip()
    for part in sorted(Path('tools/v141').glob('part-*'))
)
source = gzip.decompress(base64.b64decode(payload)).decode('utf-8')
exec(compile(source, 'tools/apply_v141_source.py', 'exec'), {'__name__': '__main__'})
