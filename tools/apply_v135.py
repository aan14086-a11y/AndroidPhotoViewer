from pathlib import Path

viewer = Path('app/src/main/java/cz/fotobezprechodu/ViewerActivity.java')
gradle = Path('app/build.gradle')

v = viewer.read_text(encoding='utf-8')

if 'import android.util.Size;' not in v:
    v = v.replace('import android.util.LruCache;\n', 'import android.util.LruCache;\nimport android.util.Size;\n', 1)

old = '''        image = new ImageView(this);\n        image.setBackgroundColor(0xff000000);\n        // Large photographs can exceed the GPU texture limit on some devices.\n        // A software layer keeps ImageView rendering reliable after the bitmap\n        // has been reduced to a safe display size by MediaUtils.\n        image.setLayerType(View.LAYER_TYPE_SOFTWARE, null);\n        updateScaleType();\n        stage.addView(image, match());\n'''
new = '''        image = createPhotoView();\n        stage.addView(image, match());\n'''
if old not in v:
    raise SystemExit('Initial ImageView block was not found')
v = v.replace(old, new, 1)

old = '''            int screen = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);\n            Bitmap b = MediaUtils.decodeImage(this, e.uri, Math.min(3072, Math.max(screen, screen * 2)));\n'''
new = '''            int screen = Math.max(getResources().getDisplayMetrics().widthPixels,\n                    getResources().getDisplayMetrics().heightPixels);\n            Bitmap b = decodePhotoForDisplay(e.uri, Math.min(1600, Math.max(720, screen)));\n'''
if old not in v:
    raise SystemExit('Full-screen image decode block was not found')
v = v.replace(old, new, 1)

old = '''        image.setImageBitmap(b);\n        activateImageLayer();\n        image.invalidate();\n        stage.invalidate();\n        preload(i); schedule(); updateStatus();\n'''
new = '''        installPhotoLayer(b);\n        preload(i); schedule(); updateStatus();\n'''
if old not in v:
    raise SystemExit('readyImage block was not found')
v = v.replace(old, new, 1)

old = '''    private void activateImageLayer() {\n        if (video != null) {\n            video.setAlpha(0f);\n            video.setVisibility(View.INVISIBLE);\n        }\n        if (image != null) {\n            image.setVisibility(View.VISIBLE);\n            image.setAlpha(1f);\n            image.bringToFront();\n        }\n    }\n\n    private void activateVideoLayer() {\n        if (image != null) {\n            image.setVisibility(View.VISIBLE);\n            image.setAlpha(1f);\n        }\n        if (video != null) {\n            video.setVisibility(View.VISIBLE);\n            video.setAlpha(0f);\n            video.bringToFront();\n        }\n    }\n'''
new = '''    private ImageView createPhotoView() {\n        ImageView view = new ImageView(this);\n        view.setBackgroundColor(0xff000000);\n        view.setScaleType(fill ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);\n        view.setVisibility(View.VISIBLE);\n        view.setAlpha(1f);\n        return view;\n    }\n\n    private Bitmap decodePhotoForDisplay(Uri uri, int target) {\n        Bitmap bitmap = null;\n        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {\n            try {\n                // This uses the same document-provider thumbnail path that is reliable\n                // in the folder browser, but requests enough pixels for full-screen use.\n                bitmap = getContentResolver().loadThumbnail(uri, new Size(target, target), null);\n            } catch (Exception | OutOfMemoryError ignored) {\n            }\n        }\n        if (bitmap == null) bitmap = MediaUtils.decodeImage(this, uri, target);\n        if (bitmap == null) return null;\n\n        // Normalize unusual wide-gamut/F16 buffers to a plain Android bitmap. Some\n        // devices decode those buffers successfully but ImageView displays them black.\n        Bitmap.Config config = bitmap.getConfig();\n        if (config != Bitmap.Config.ARGB_8888 && config != Bitmap.Config.RGB_565) {\n            try {\n                Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);\n                if (copy != null) {\n                    if (copy != bitmap) bitmap.recycle();\n                    bitmap = copy;\n                }\n            } catch (Exception | OutOfMemoryError ignored) {\n            }\n        }\n        return bitmap;\n    }\n\n    private void installPhotoLayer(Bitmap bitmap) {\n        // Do not merely hide TextureView. Remove it from the hierarchy so no retained\n        // video surface can cover the photograph on device-specific graphics drivers.\n        if (video != null && video.getParent() == stage) stage.removeView(video);\n        if (image != null && image.getParent() == stage) stage.removeView(image);\n        image = createPhotoView();\n        image.setImageBitmap(bitmap);\n        stage.addView(image, match());\n        image.requestLayout();\n        image.invalidate();\n        stage.requestLayout();\n        stage.invalidate();\n    }\n\n    private void activateImageLayer() {\n        if (video != null && video.getParent() == stage) stage.removeView(video);\n        if (image == null) image = createPhotoView();\n        if (image.getParent() == null) stage.addView(image, match());\n        image.setVisibility(View.VISIBLE);\n        image.setAlpha(1f);\n    }\n\n    private void activateVideoLayer() {\n        if (image != null) {\n            image.setVisibility(View.VISIBLE);\n            image.setAlpha(1f);\n        }\n        if (video != null) {\n            if (video.getParent() == null) stage.addView(video, match());\n            video.setVisibility(View.VISIBLE);\n            video.setAlpha(0f);\n            video.bringToFront();\n        }\n    }\n'''
if old not in v:
    raise SystemExit('Layer helper block was not found')
v = v.replace(old, new, 1)

viewer.write_text(v, encoding='utf-8')

g = gradle.read_text(encoding='utf-8')
old_version = "versionCode 8\n        versionName '1.3.4'"
new_version = "versionCode 9\n        versionName '1.3.5'"
if old_version not in g:
    raise SystemExit('Expected version 1.3.4 was not found')
gradle.write_text(g.replace(old_version, new_version, 1), encoding='utf-8')
