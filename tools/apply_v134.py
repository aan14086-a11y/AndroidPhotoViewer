from pathlib import Path

viewer = Path('app/src/main/java/cz/fotobezprechodu/ViewerActivity.java')
media = Path('app/src/main/java/cz/fotobezprechodu/MediaUtils.java')
gradle = Path('app/build.gradle')

v = viewer.read_text(encoding='utf-8')
old = '''        image = new ImageView(this);\n        image.setBackgroundColor(0xff000000);\n        updateScaleType();\n'''
new = '''        image = new ImageView(this);\n        image.setBackgroundColor(0xff000000);\n        // Large photographs can exceed the GPU texture limit on some devices.\n        // A software layer keeps ImageView rendering reliable after the bitmap\n        // has been reduced to a safe display size by MediaUtils.\n        image.setLayerType(View.LAYER_TYPE_SOFTWARE, null);\n        updateScaleType();\n'''
if old not in v:
    raise SystemExit('ViewerActivity insertion point not found')
v = v.replace(old, new, 1)
viewer.write_text(v, encoding='utf-8')

m = media.read_text(encoding='utf-8')
old = '''        Bitmap bitmap = decodeWithFileDescriptor(resolver, uri, target);\n        if (bitmap == null) bitmap = decodeWithStreams(resolver, uri, target);\n        if (bitmap != null) return applyExifOrientation(resolver, uri, bitmap);\n'''
new = '''        Bitmap bitmap = decodeWithFileDescriptor(resolver, uri, target);\n        if (bitmap == null) bitmap = decodeWithStreams(resolver, uri, target);\n        if (bitmap != null) {\n            bitmap = applyExifOrientation(resolver, uri, bitmap);\n            return constrainBitmap(bitmap, target);\n        }\n'''
if old not in m:
    raise SystemExit('MediaUtils decode return point not found')
m = m.replace(old, new, 1)

old = '''                return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {\n                    int width = info.getSize().getWidth();\n                    int height = info.getSize().getHeight();\n                    float scale = Math.min(1f, (float) target / Math.max(width, height));\n                    if (scale < 1f) decoder.setTargetSize(\n                            Math.max(1, Math.round(width * scale)),\n                            Math.max(1, Math.round(height * scale)));\n                    decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);\n                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);\n                });\n'''
new = '''                Bitmap decoded = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {\n                    int width = info.getSize().getWidth();\n                    int height = info.getSize().getHeight();\n                    float scale = Math.min(1f, (float) target / Math.max(width, height));\n                    if (scale < 1f) decoder.setTargetSize(\n                            Math.max(1, Math.round(width * scale)),\n                            Math.max(1, Math.round(height * scale)));\n                    decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);\n                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);\n                });\n                return constrainBitmap(decoded, target);\n'''
if old not in m:
    raise SystemExit('ImageDecoder return point not found')
m = m.replace(old, new, 1)

old = '''    private static BitmapFactory.Options decodingOptions(int width, int height, int target) {\n        int sample = 1;\n        while (Math.max(width, height) / (sample * 2) >= target) sample *= 2;\n        BitmapFactory.Options options = new BitmapFactory.Options();\n'''
new = '''    private static BitmapFactory.Options decodingOptions(int width, int height, int target) {\n        int sample = 1;\n        int maximum = Math.max(width, height);\n        // The old condition could return an image almost twice as large as target\n        // (for example about 6000 px for a 3072 px request). Such a bitmap may\n        // decode correctly but render as a black rectangle when it exceeds the\n        // device GPU texture limit. Ensure the decoded side is never above target.\n        while ((maximum + sample - 1) / sample > target && sample < 128) sample *= 2;\n        BitmapFactory.Options options = new BitmapFactory.Options();\n'''
if old not in m:
    raise SystemExit('decodingOptions point not found')
m = m.replace(old, new, 1)

marker = '''    private static Bitmap applyExifOrientation(ContentResolver resolver, Uri uri, Bitmap bitmap) {\n'''
helper = '''    private static Bitmap constrainBitmap(Bitmap bitmap, int target) {\n        if (bitmap == null) return null;\n        int max = Math.max(bitmap.getWidth(), bitmap.getHeight());\n        int safeTarget = Math.max(128, Math.min(target, 3072));\n        if (max <= safeTarget) return bitmap;\n        float scale = (float) safeTarget / max;\n        try {\n            Bitmap scaled = Bitmap.createScaledBitmap(bitmap,\n                    Math.max(1, Math.round(bitmap.getWidth() * scale)),\n                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true);\n            if (scaled != bitmap) bitmap.recycle();\n            return scaled;\n        } catch (OutOfMemoryError error) {\n            return bitmap;\n        }\n    }\n\n'''
if marker not in m:
    raise SystemExit('helper insertion point not found')
m = m.replace(marker, helper + marker, 1)
media.write_text(m, encoding='utf-8')

g = gradle.read_text(encoding='utf-8')
g = g.replace("versionCode 7\n        versionName '1.3.3'", "versionCode 8\n        versionName '1.3.4'", 1)
if "versionName '1.3.4'" not in g:
    raise SystemExit('Gradle version point not found')
gradle.write_text(g, encoding='utf-8')
