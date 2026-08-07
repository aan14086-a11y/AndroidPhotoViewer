from pathlib import Path
import base64
import gzip

payload = ''.join(
    part.read_text(encoding='utf-8').strip()
    for part in sorted(Path('tools/v141').glob('part-*'))
)
source = gzip.decompress(base64.b64decode(payload)).decode('utf-8')

stale_expected = r'''    ''' + "'''" + r'''    private void openViewer(MediaUtils.Entry selected) {\n        Intent intent = new Intent(this, ViewerActivity.class);\n        intent.putExtra(EXTRA_TREE_URI, treeUri.toString());\n        intent.putExtra(EXTRA_FOLDER_ID, currentFolderId);\n        intent.putExtra(EXTRA_SELECTED_ID, selected.documentId);\n        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);\n        startActivity(intent);\n    }\n''' + "'''" + r''','''
current_expected = r'''    ''' + "'''" + r'''    private void openViewer(MediaUtils.Entry selected) {\n        Intent intent = new Intent(this, ViewerActivity.class);\n        intent.putExtra(EXTRA_TREE_URI, treeUri.toString());\n        intent.putExtra(EXTRA_FOLDER_ID, currentFolderId);\n        intent.putExtra(EXTRA_SELECTED_ID, selected.documentId);\n        intent.putExtra(EXTRA_SELECTED_URI, selected.uri.toString());\n        intent.putExtra(EXTRA_SELECTED_NAME, selected.name);\n        intent.putExtra(EXTRA_SELECTED_MIME, selected.mime);\n\n        // Flags without an attached URI do not grant anything on some document providers.\n        // Put both the tree and selected document into ClipData so ViewerActivity can\n        // read the chosen picture immediately even when persistable access is unavailable.\n        ClipData grants = ClipData.newRawUri("folder", treeUri);\n        grants.addItem(new ClipData.Item(selected.uri));\n        intent.setClipData(grants);\n        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION\n                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);\n        startActivity(intent);\n    }\n''' + "'''" + r''','''

stale_replacement = r'''    ''' + "'''" + r'''    private void openViewer(MediaUtils.Entry selected) {\n        Intent intent = new Intent(this, ViewerActivity.class);\n        if (scannedMode) {\n            intent.putStringArrayListExtra(EXTRA_SCAN_URIS, selectedScanUris());\n            intent.putExtra(EXTRA_SELECTED_URI, selected.uri.toString());\n        } else {\n            intent.putExtra(EXTRA_TREE_URI, treeUri.toString());\n            intent.putExtra(EXTRA_FOLDER_ID, currentFolderId);\n            intent.putExtra(EXTRA_SELECTED_ID, selected.documentId);\n        }\n        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);\n        startActivity(intent);\n    }\n''' + "'''" + r''','''
corrected_replacement = r'''    ''' + "'''" + r'''    private void openViewer(MediaUtils.Entry selected) {\n        Intent intent = new Intent(this, ViewerActivity.class);\n        if (scannedMode) {\n            intent.putStringArrayListExtra(EXTRA_SCAN_URIS, selectedScanUris());\n            intent.putExtra(EXTRA_SELECTED_URI, selected.uri.toString());\n            intent.setClipData(ClipData.newRawUri("selected", selected.uri));\n            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);\n        } else {\n            intent.putExtra(EXTRA_TREE_URI, treeUri.toString());\n            intent.putExtra(EXTRA_FOLDER_ID, currentFolderId);\n            intent.putExtra(EXTRA_SELECTED_ID, selected.documentId);\n            intent.putExtra(EXTRA_SELECTED_URI, selected.uri.toString());\n            intent.putExtra(EXTRA_SELECTED_NAME, selected.name);\n            intent.putExtra(EXTRA_SELECTED_MIME, selected.mime);\n            ClipData grants = ClipData.newRawUri("folder", treeUri);\n            grants.addItem(new ClipData.Item(selected.uri));\n            intent.setClipData(grants);\n            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION\n                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);\n        }\n        startActivity(intent);\n    }\n''' + "'''" + r''','''

if stale_expected not in source:
    raise SystemExit('Stale viewer expected block was not found')
if stale_replacement not in source:
    raise SystemExit('Stale viewer replacement block was not found')
source = source.replace(stale_expected, current_expected, 1)
source = source.replace(stale_replacement, corrected_replacement, 1)

exec(compile(source, 'tools/apply_v141_source.py', 'exec'), {'__name__': '__main__'})

# v1.3.1 already introduced this intent key. The v1.4.1 compatibility payload
# was authored against the older source and adds it again, so remove only the
# second declaration after all source transformations have completed.
browser = Path('app/src/main/java/cz/fotobezprechodu/BrowserActivity.java')
browser_text = browser.read_text(encoding='utf-8')
constant = '    static final String EXTRA_SELECTED_URI = "selected_uri";\n'
first = browser_text.find(constant)
second = browser_text.find(constant, first + len(constant)) if first >= 0 else -1
if first < 0 or second < 0:
    raise SystemExit('Expected duplicate EXTRA_SELECTED_URI declarations were not found')
browser.write_text(browser_text[:second] + browser_text[second + len(constant):], encoding='utf-8')
