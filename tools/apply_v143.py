from pathlib import Path

viewer = Path('app/src/main/java/cz/fotobezprechodu/ViewerActivity.java')
browser = Path('app/src/main/java/cz/fotobezprechodu/BrowserActivity.java')
media = Path('app/src/main/java/cz/fotobezprechodu/MediaUtils.java')
gradle = Path('app/build.gradle')

v = viewer.read_text(encoding='utf-8')
b = browser.read_text(encoding='utf-8')
m = media.read_text(encoding='utf-8')
g = gradle.read_text(encoding='utf-8')

def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'{label} was not found')
    return text.replace(old, new, 1)

v = replace_once(
    v,
    '''                float position = e.getX() / Math.max(1f, stage.getWidth());\n                // Edge taps are handled immediately in ACTION_UP. Do not wait for\n                // GestureDetector's double-tap timeout and do not navigate twice.\n                if (tapNextGesture && (position < .35f || position > .65f)) return false;\n''',
    '''                float tapEdgeWidth = Math.min(dp(48), stage.getWidth() * .16f);\n                // Edge taps are handled immediately in ACTION_UP. Keep the active\n                // areas narrow so the centre of the picture remains available.\n                if (tapNextGesture && (e.getX() <= tapEdgeWidth\n                        || e.getX() >= stage.getWidth() - tapEdgeWidth)) return false;\n''',
    'single tap edge width',
)

v = replace_once(
    v,
    '''                    float position = e.getX() / Math.max(1f, v.getWidth());\n                    if (position < .35f) {\n                        stopSlideshow();\n                        previous();\n                        navigatedByTap = true;\n                    } else if (position > .65f) {\n                        stopSlideshow();\n                        next();\n                        navigatedByTap = true;\n                    }\n''',
    '''                    float tapEdgeWidth = Math.min(dp(48), v.getWidth() * .16f);\n                    if (e.getX() <= tapEdgeWidth) {\n                        stopSlideshow();\n                        previous();\n                        navigatedByTap = true;\n                    } else if (e.getX() >= v.getWidth() - tapEdgeWidth) {\n                        stopSlideshow();\n                        next();\n                        navigatedByTap = true;\n                    }\n''',
    'ACTION_UP edge width',
)

b = replace_once(
    b,
    '''import java.util.ArrayList;\nimport java.util.concurrent.ExecutorService;\n''',
    '''import java.util.ArrayList;\nimport java.util.concurrent.ConcurrentHashMap;\nimport java.util.concurrent.ExecutorService;\n''',
    'ConcurrentHashMap import',
)

b = replace_once(
    b,
    '''    private final AtomicInteger generation = new AtomicInteger();\n    private final ArrayDeque<FolderState> backStack = new ArrayDeque<>();\n''',
    '''    private final AtomicInteger generation = new AtomicInteger();\n    private final ArrayDeque<FolderState> backStack = new ArrayDeque<>();\n    private final ConcurrentHashMap<String, Integer> folderMediaCounts = new ConcurrentHashMap<>();\n''',
    'folder count cache field',
)

b = replace_once(
    b,
    '''        backStack.clear();\n        thumbnails.evictAll();\n        showFolder(rootId, rootName, false);\n''',
    '''        backStack.clear();\n        thumbnails.evictAll();\n        folderMediaCounts.clear();\n        showFolder(rootId, rootName, false);\n''',
    'clear folder count cache on storage change',
)

b = replace_once(
    b,
    '''        preview.addView(marker, new FrameLayout.LayoutParams(\n                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));\n        card.addView(preview, new LinearLayout.LayoutParams(\n''',
    '''        preview.addView(marker, new FrameLayout.LayoutParams(\n                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));\n\n        if (item.directory) {\n            TextView countBadge = new TextView(this);\n            countBadge.setText("Počítám…");\n            countBadge.setTextColor(Color.WHITE);\n            countBadge.setTextSize(11);\n            countBadge.setGravity(Gravity.CENTER);\n            countBadge.setPadding(dp(7), dp(3), dp(7), dp(3));\n            countBadge.setBackground(new ColorDrawable(0xD8111111));\n            FrameLayout.LayoutParams countParams = new FrameLayout.LayoutParams(\n                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,\n                    Gravity.BOTTOM | Gravity.START);\n            countParams.setMargins(dp(5), dp(5), dp(5), dp(5));\n            preview.addView(countBadge, countParams);\n            loadFolderCount(item, countBadge, request);\n        }\n        card.addView(preview, new LinearLayout.LayoutParams(\n''',
    'folder count badge',
)

b = replace_once(
    b,
    '''    private void loadThumbnail(MediaUtils.Entry item, ImageView target, int request, int size) {\n''',
    '''    private void loadFolderCount(MediaUtils.Entry item, TextView target, int request) {\n        Integer cached = folderMediaCounts.get(item.documentId);\n        if (cached != null) {\n            target.setText(formatFileCount(cached));\n            return;\n        }\n        worker.execute(() -> {\n            int count = MediaUtils.countDirectMediaFiles(this, treeUri, item.documentId);\n            folderMediaCounts.put(item.documentId, count);\n            runOnUiThread(() -> {\n                if (request == generation.get() && target.getWindowToken() != null) {\n                    target.setText(formatFileCount(count));\n                }\n            });\n        });\n    }\n\n    private String formatFileCount(int count) {\n        if (count == 1) return "1 soubor";\n        if (count >= 2 && count <= 4) return count + " soubory";\n        return count + " souborů";\n    }\n\n    private void loadThumbnail(MediaUtils.Entry item, ImageView target, int request, int size) {\n''',
    'folder count loader',
)

m = replace_once(
    m,
    '''    static String resolveDocumentName(Context context, Uri treeUri, String documentId, String fallback) {\n''',
    '''    static int countDirectMediaFiles(Context context, Uri treeUri, String folderId) {\n        if (treeUri == null || folderId == null) return 0;\n        Uri childrenUri;\n        try {\n            childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId);\n        } catch (Exception e) {\n            return 0;\n        }\n        String[] columns = {\n                DocumentsContract.Document.COLUMN_DISPLAY_NAME,\n                DocumentsContract.Document.COLUMN_MIME_TYPE\n        };\n        int count = 0;\n        try (Cursor cursor = context.getContentResolver().query(childrenUri, columns, null, null, null)) {\n            if (cursor == null) return 0;\n            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);\n            int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);\n            while (cursor.moveToNext()) {\n                String name = nameColumn >= 0 ? cursor.getString(nameColumn) : "";\n                String mime = mimeColumn >= 0 ? cursor.getString(mimeColumn) : null;\n                if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)\n                        && isSupportedMedia(mime, name)) count++;\n            }\n        } catch (Exception ignored) {\n        }\n        return count;\n    }\n\n    static String resolveDocumentName(Context context, Uri treeUri, String documentId, String fallback) {\n''',
    'direct media file counter',
)

old_version = "versionCode 14\n        versionName '1.4.0'"
new_version = "versionCode 17\n        versionName '1.4.3'"
if old_version not in g:
    raise SystemExit('Expected version 1.4.0 was not found')
g = g.replace(old_version, new_version, 1)

viewer.write_text(v, encoding='utf-8')
browser.write_text(b, encoding='utf-8')
media.write_text(m, encoding='utf-8')
gradle.write_text(g, encoding='utf-8')
