from pathlib import Path

media_path = Path("app/src/main/java/cz/fotobezprechodu/MediaUtils.java")
text = media_path.read_text(encoding="utf-8")

if "import android.os.ParcelFileDescriptor;" not in text:
    text = text.replace(
        "import android.os.Build;\n",
        "import android.os.Build;\nimport android.os.ParcelFileDescriptor;\n",
    )
if "import java.io.FileDescriptor;" not in text:
    text = text.replace(
        "import java.io.InputStream;\n",
        "import java.io.FileDescriptor;\nimport java.io.InputStream;\n",
    )

start_marker = "    private static Bitmap decodeImageOnce(Context context, Uri uri, int target) {"
end_marker = "    static Bitmap decodeVideoFrame(Context context, Uri uri, int targetPx) {"
start = text.find(start_marker)
end = text.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("MediaUtils image decoder anchors were not found")

replacement = '''    private static Bitmap decodeImageOnce(Context context, Uri uri, int target) {
        ContentResolver resolver = context.getContentResolver();

        // Prefer BitmapFactory through a freshly opened file descriptor. Some
        // document providers expose image URIs that MediaPlayer can read while
        // ImageDecoder rejects them. Reopening also avoids seek/reset problems.
        Bitmap bitmap = decodeWithFileDescriptor(resolver, uri, target);
        if (bitmap == null) bitmap = decodeWithStreams(resolver, uri, target);
        if (bitmap != null) return applyExifOrientation(resolver, uri, bitmap);

        // Keep ImageDecoder only as a fallback for formats BitmapFactory cannot read.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(resolver, uri);
                return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    float scale = Math.min(1f, (float) target / Math.max(width, height));
                    if (scale < 1f) decoder.setTargetSize(
                            Math.max(1, Math.round(width * scale)),
                            Math.max(1, Math.round(height * scale)));
                    decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } catch (Exception | OutOfMemoryError ignored) {
            }
        }
        return null;
    }

    private static Bitmap decodeWithFileDescriptor(ContentResolver resolver, Uri uri, int target) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
            if (descriptor == null) return null;
            FileDescriptor file = descriptor.getFileDescriptor();
            BitmapFactory.decodeFileDescriptor(file, null, bounds);
        } catch (Exception ignored) {
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options options = decodingOptions(bounds.outWidth, bounds.outHeight, target);
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
            if (descriptor == null) return null;
            return BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, options);
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static Bitmap decodeWithStreams(ContentResolver resolver, Uri uri, int target) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) return null;
            BitmapFactory.decodeStream(stream, null, bounds);
        } catch (Exception ignored) {
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options options = decodingOptions(bounds.outWidth, bounds.outHeight, target);
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) return null;
            return BitmapFactory.decodeStream(stream, null, options);
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static BitmapFactory.Options decodingOptions(int width, int height, int target) {
        int sample = 1;
        while (Math.max(width, height) / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inDither = false;
        return options;
    }

    private static Bitmap applyExifOrientation(ContentResolver resolver, Uri uri, Bitmap bitmap) {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream != null) {
                ExifInterface exif = new ExifInterface(stream);
                orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception ignored) {
        }
        return rotateBitmap(bitmap, orientation);
    }

'''
media_path.write_text(text[:start] + replacement + text[end:], encoding="utf-8")

gradle_path = Path("app/build.gradle")
gradle = gradle_path.read_text(encoding="utf-8")
old = "versionCode 5\n        versionName '1.3.1'"
new = "versionCode 6\n        versionName '1.3.2'"
if old not in gradle:
    raise SystemExit("Expected version 1.3.1 was not found")
gradle_path.write_text(gradle.replace(old, new, 1), encoding="utf-8")
