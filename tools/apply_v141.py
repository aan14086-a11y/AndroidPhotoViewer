from pathlib import Path
import base64
import gzip

payload = ''.join(
    part.read_text(encoding='utf-8').strip()
    for part in sorted(Path('tools/v141').glob('part-*'))
)
source = gzip.decompress(base64.b64decode(payload)).decode('utf-8')

old_helper = """    if old not in b:\n        raise SystemExit(f'{label} was not found in BrowserActivity')\n    b = b.replace(old, new, 1)"""
new_helper = """    if old not in b:\n        if label == 'browser scanned viewer launch':\n            method_start = b.index('    private void openViewer(MediaUtils.Entry selected) {')\n            method_end = b.index('\\n    private ', method_start + 12)\n            corrected = '''    private void openViewer(MediaUtils.Entry selected) {\n        Intent intent = new Intent(this, ViewerActivity.class);\n        if (scannedMode) {\n            intent.putStringArrayListExtra(EXTRA_SCAN_URIS, selectedScanUris());\n            intent.putExtra(EXTRA_SELECTED_URI, selected.uri.toString());\n            intent.setClipData(ClipData.newRawUri(\"selected\", selected.uri));\n            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);\n        } else {\n            intent.putExtra(EXTRA_TREE_URI, treeUri.toString());\n            intent.putExtra(EXTRA_FOLDER_ID, currentFolderId);\n            intent.putExtra(EXTRA_SELECTED_ID, selected.documentId);\n            intent.putExtra(EXTRA_SELECTED_URI, selected.uri.toString());\n            intent.putExtra(EXTRA_SELECTED_NAME, selected.name);\n            intent.putExtra(EXTRA_SELECTED_MIME, selected.mime);\n            ClipData grants = ClipData.newRawUri(\"folder\", treeUri);\n            grants.addItem(new ClipData.Item(selected.uri));\n            intent.setClipData(grants);\n            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION\n                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);\n        }\n        startActivity(intent);\n    }\n'''\n            b = b[:method_start] + corrected + b[method_end:]\n            return\n        raise SystemExit(f'{label} was not found in BrowserActivity')\n    b = b.replace(old, new, 1)"""
if old_helper not in source:
    raise SystemExit('Browser patch helper was not found in v1.4.1 payload')
source = source.replace(old_helper, new_helper, 1)

exec(compile(source, 'tools/apply_v141_source.py', 'exec'), {'__name__': '__main__'})
