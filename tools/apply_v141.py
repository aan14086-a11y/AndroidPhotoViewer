from pathlib import Path
import base64
import gzip

payload = ''.join(
    part.read_text(encoding='utf-8').strip()
    for part in sorted(Path('tools/v141').glob('part-*'))
)
source = gzip.decompress(base64.b64decode(payload)).decode('utf-8')
marker = source.find('browser scanned viewer launch')
if marker < 0:
    raise SystemExit('Viewer launch label was not found')
print('--- V141 VIEWER LAUNCH PATCH START ---')
print(source[max(0, marker - 3500):marker + 1000])
print('--- V141 VIEWER LAUNCH PATCH END ---')
raise SystemExit('Diagnostic output complete')
