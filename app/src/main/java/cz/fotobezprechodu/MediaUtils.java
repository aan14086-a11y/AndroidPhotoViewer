package cz.fotobezprechodu;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import java.io.InputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Locale;

final class MediaUtils {
    private MediaUtils() {}

    static final class Entry {
        final String documentId;
        final Uri uri;
        final String name;
        final String mime;
        final boolean directory;
        final boolean video;

        Entry(String documentId, Uri uri, String name, String mime, boolean directory, boolean video) {
            this.documentId = documentId;
            this.uri = uri;
            this.name = name == null ? "" : name;
            this.mime = mime;
            this.directory = directory;
            this.video = video;
        }
    }

    static ArrayList<Entry> queryDirectChildren(Context context, Uri treeUri, String folderId) {
        ArrayList<Entry> result = new ArrayList<>();
        if (treeUri == null || folderId == null) return result;
        Uri childrenUri;
        try {
            childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId);
        } catch (Exception e) {
            return result;
        }

        String[] columns = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(childrenUri, columns, null, null, null)) {
            if (cursor == null) return result;
            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String id = idColumn >= 0 ? cursor.getString(idColumn) : null;
                String name = nameColumn >= 0 ? cursor.getString(nameColumn) : "";
                String mime = mimeColumn >= 0 ? cursor.getString(mimeColumn) : null;
                if (id == null) continue;
                boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                if (!directory && !isSupportedMedia(mime, name)) continue;
                Uri uri;
                try {
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                } catch (Exception e) {
                    continue;
                }
                result.add(new Entry(id, uri, name, mime, directory, !directory && isVideo(mime, name)));
            }
        } catch (Exception ignored) {
        }

        Collator collator = Collator.getInstance(new Locale("cs", "CZ"));
        collator.setStrength(Collator.PRIMARY);
        result.sort((a, b) -> {
            if (a.directory != b.directory) return a.directory ? -1 : 1;
            return naturalCompare(a.name, b.name, collator);
        });
        return result;
    }

    static String resolveDocumentName(Context context, Uri treeUri, String documentId, String fallback) {
        try {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
            String[] columns = {DocumentsContract.Document.COLUMN_DISPLAY_NAME};
            try (Cursor cursor = context.getContentResolver().query(uri, columns, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                    if (index >= 0) {
                        String value = cursor.getString(index);
                        if (value != null && !value.trim().isEmpty()) return value;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    static MediaUtils.Entry firstMediaChild(Context context, Uri treeUri, String folderId) {
        ArrayList<Entry> items = queryDirectChildren(context, treeUri, folderId);
        for (Entry item : items) if (!item.directory) return item;
        return null;
    }

    static boolean isSupportedMedia(String mime, String name) {
        if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) return true;
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(jpg|jpeg|png|webp|bmp|gif|heic|heif|mp4|m4v|mov|mkv|webm|3gp|avi)$");
    }

    static boolean isVideo(String mime, String name) {
        if (mime != null && mime.startsWith("video/")) return true;
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(mp4|m4v|mov|mkv|webm|3gp|avi)$");
    }

    static Bitmap decodeThumbnail(Context context, Uri uri, boolean video, int targetPx) {
        return video ? decodeVideoFrame(context, uri, targetPx) : decodeImage(context, uri, targetPx);
    }

    static Bitmap decodeImage(Context context, Uri uri, int targetPx) {
        int requested = Math.max(128, targetPx);
        int[] attempts = {requested, Math.min(requested, 2048), Math.min(requested, 1280)};
        int previous = -1;
        for (int target : attempts) {
            if (target == previous) continue;
            previous = target;
            Bitmap bitmap = decodeImageOnce(context, uri, target);
            if (bitmap != null) return bitmap;
            System.gc();
        }
        return null;
    }

    private static Bitmap decodeImageOnce(Context context, Uri uri, int target) {
        ContentResolver resolver = context.getContentResolver();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = resolver.openInputStream(uri)) {
                if (stream == null) return null;
                BitmapFactory.decodeStream(stream, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap;
            try (InputStream stream = resolver.openInputStream(uri)) {
                if (stream == null) return null;
                bitmap = BitmapFactory.decodeStream(stream, null, options);
            }
            if (bitmap == null) return null;
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
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    static Bitmap decodeVideoFrame(Context context, Uri uri, int targetPx) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            if ("90".equals(rotation)) orientation = ExifInterface.ORIENTATION_ROTATE_90;
            else if ("180".equals(rotation)) orientation = ExifInterface.ORIENTATION_ROTATE_180;
            else if ("270".equals(rotation)) orientation = ExifInterface.ORIENTATION_ROTATE_270;
            frame = rotateBitmap(frame, orientation);
            int max = Math.max(frame.getWidth(), frame.getHeight());
            int target = Math.max(128, targetPx);
            if (max > target) {
                float scale = (float) target / max;
                Bitmap scaled = Bitmap.createScaledBitmap(frame,
                        Math.max(1, Math.round(frame.getWidth() * scale)),
                        Math.max(1, Math.round(frame.getHeight() * scale)), true);
                if (scaled != frame) frame.recycle();
                frame = scaled;
            }
            return frame;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static Bitmap rotateBitmap(Bitmap bitmap, int orientation) {
        Matrix matrix = new Matrix();
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90) matrix.postRotate(90);
        else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) matrix.postRotate(180);
        else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) matrix.postRotate(270);
        else return bitmap;
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }

    private static int naturalCompare(String left, String right, Collator collator) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            char a = left.charAt(i);
            char b = right.charAt(j);
            if (Character.isDigit(a) && Character.isDigit(b)) {
                int startI = i;
                int startJ = j;
                while (i < left.length() && Character.isDigit(left.charAt(i))) i++;
                while (j < right.length() && Character.isDigit(right.charAt(j))) j++;
                String numberA = left.substring(startI, i).replaceFirst("^0+(?!$)", "");
                String numberB = right.substring(startJ, j).replaceFirst("^0+(?!$)", "");
                if (numberA.length() != numberB.length()) return Integer.compare(numberA.length(), numberB.length());
                int numeric = numberA.compareTo(numberB);
                if (numeric != 0) return numeric;
            } else {
                int startI = i;
                int startJ = j;
                while (i < left.length() && !Character.isDigit(left.charAt(i))) i++;
                while (j < right.length() && !Character.isDigit(right.charAt(j))) j++;
                int text = collator.compare(left.substring(startI, i), right.substring(startJ, j));
                if (text != 0) return text;
            }
        }
        return Integer.compare(left.length(), right.length());
    }
}
