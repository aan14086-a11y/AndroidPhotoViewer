package cz.fotobezprechodu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.drawable.ColorDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.Collator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private static final int REQUEST_TREE = 1001;
    private static final String PREFS = "foto_bez_prechodu";
    private static final String PREF_TREE_URI = "tree_uri";
    private static final String PREF_INTERVAL_MS = "interval_ms";
    private static final String PREF_LOOP = "loop";
    private static final String PREF_RANDOM = "random";
    private static final String PREF_FILL = "fill";

    private final ArrayList<PhotoItem> photos = new ArrayList<>();
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> loading = Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger folderRequest = new AtomicInteger(0);

    private SharedPreferences preferences;
    private ImageView imageView;
    private LinearLayout controls;
    private TextView statusView;
    private Button playButton;
    private LruCache<String, Bitmap> bitmapCache;

    private int currentIndex = -1;
    private int desiredIndex = -1;
    private long intervalMs = 1000;
    private boolean playing = false;
    private boolean loop = true;
    private boolean randomOrder = false;
    private boolean fillScreen = false;
    private float downX;
    private float downY;

    private final Runnable slideshowTick = new Runnable() {
        @Override
        public void run() {
            if (!playing || photos.isEmpty()) return;

            int next = chooseNextIndex();
            if (next < 0) {
                stopSlideshow();
                return;
            }

            displayIndex(next);
            mainHandler.postDelayed(this, intervalMs);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        intervalMs = Math.max(100, preferences.getLong(PREF_INTERVAL_MS, 1000));
        loop = preferences.getBoolean(PREF_LOOP, true);
        randomOrder = preferences.getBoolean(PREF_RANDOM, false);
        fillScreen = preferences.getBoolean(PREF_FILL, false);

        int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        int cacheSizeKb = Math.max(16 * 1024, Math.min(128 * 1024, maxMemoryKb / 4));
        bitmapCache = new LruCache<String, Bitmap>(cacheSizeKb) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return Math.max(1, bitmap.getByteCount() / 1024);
            }
        };

        buildUi();
        enterImmersiveMode();

        String savedTree = preferences.getString(PREF_TREE_URI, null);
        if (savedTree != null) {
            loadFolder(Uri.parse(savedTree));
        } else {
            statusView.setText("Vyberte složku s fotografiemi");
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        imageView = new ImageView(this);
        imageView.setBackgroundColor(0xFF000000);
        imageView.setScaleType(fillScreen ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(false);
        root.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(8), dp(8), dp(8), dp(8));
        controls.setBackground(new ColorDrawable(0xB0000000));

        statusView = new TextView(this);
        statusView.setTextColor(0xFFFFFFFF);
        statusView.setTextSize(15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(6), dp(2), dp(6), dp(7));
        controls.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);

        Button folderButton = makeButton("Složka");
        Button previousButton = makeButton("◀");
        playButton = makeButton("Spustit");
        Button nextButton = makeButton("▶");
        Button settingsButton = makeButton("Nastavení");

        buttons.addView(folderButton, weightedButtonParams(1.35f));
        buttons.addView(previousButton, weightedButtonParams(0.75f));
        buttons.addView(playButton, weightedButtonParams(1.15f));
        buttons.addView(nextButton, weightedButtonParams(0.75f));
        buttons.addView(settingsButton, weightedButtonParams(1.55f));
        controls.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(controls, controlsParams);
        setContentView(root);

        folderButton.setOnClickListener(v -> openFolderPicker());
        previousButton.setOnClickListener(v -> {
            stopSlideshow();
            showPreviousManual();
        });
        nextButton.setOnClickListener(v -> {
            stopSlideshow();
            showNextManual();
        });
        playButton.setOnClickListener(v -> {
            if (playing) stopSlideshow(); else startSlideshow();
        });
        settingsButton.setOnClickListener(v -> showSettingsDialog());

        imageView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dx) > dp(60) && Math.abs(dx) > Math.abs(dy)) {
                    stopSlideshow();
                    if (dx < 0) showNextManual(); else showPreviousManual();
                } else {
                    controls.setVisibility(controls.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                }
                return true;
            }
            return false;
        });
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(42));
        button.setMinimumHeight(dp(42));
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private LinearLayout.LayoutParams weightedButtonParams(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), weight);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri treeUri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            preferences.edit().putString(PREF_TREE_URI, treeUri.toString()).apply();
            loadFolder(treeUri);
        }
    }

    private void loadFolder(Uri treeUri) {
        stopSlideshow();
        statusView.setText("Načítám fotografie…");
        int request = folderRequest.incrementAndGet();
        worker.execute(() -> {
            ArrayList<PhotoItem> found = scanTree(treeUri);
            final Collator collator = Collator.getInstance(new Locale("cs", "CZ"));
            collator.setStrength(Collator.PRIMARY);
            found.sort((a, b) -> naturalCompare(a.name, b.name, collator));

            mainHandler.post(() -> {
                if (request != folderRequest.get()) return;
                photos.clear();
                photos.addAll(found);
                bitmapCache.evictAll();
                loading.clear();
                currentIndex = photos.isEmpty() ? -1 : 0;
                desiredIndex = currentIndex;
                if (photos.isEmpty()) {
                    statusView.setText("Ve složce nebyly nalezeny žádné fotografie");
                    imageView.setImageDrawable(null);
                } else {
                    displayIndex(currentIndex);
                    preloadAround(currentIndex);
                    updateStatus();
                }
            });
        });
    }

    private ArrayList<PhotoItem> scanTree(Uri treeUri) {
        ArrayList<PhotoItem> result = new ArrayList<>();
        ContentResolver resolver = getContentResolver();
        String rootId;
        try {
            rootId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            return result;
        }

        ArrayDeque<String> folders = new ArrayDeque<>();
        folders.add(rootId);
        String[] columns = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        while (!folders.isEmpty()) {
            String parentId = folders.removeFirst();
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
            try (Cursor cursor = resolver.query(childrenUri, columns, null, null, null)) {
                if (cursor == null) continue;
                int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);

                while (cursor.moveToNext()) {
                    String id = cursor.getString(idColumn);
                    String name = cursor.getString(nameColumn);
                    String mime = cursor.getString(mimeColumn);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        folders.addLast(id);
                    } else if (mime != null && mime.startsWith("image/")) {
                        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                        result.add(new PhotoItem(documentUri, name == null ? "" : name));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private int naturalCompare(String left, String right, Collator collator) {
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
                if (numberA.length() != numberB.length()) {
                    return Integer.compare(numberA.length(), numberB.length());
                }
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

    private void displayIndex(int index) {
        if (index < 0 || index >= photos.size()) return;
        desiredIndex = index;
        PhotoItem item = photos.get(index);
        String key = item.uri.toString();
        Bitmap cached = bitmapCache.get(key);
        if (cached != null) {
            showLoadedBitmap(index, cached);
            return;
        }
        requestBitmap(index);
    }

    private void requestBitmap(int index) {
        if (index < 0 || index >= photos.size()) return;
        PhotoItem item = photos.get(index);
        String key = item.uri.toString();
        if (bitmapCache.get(key) != null || !loading.add(key)) return;

        int expectedFolder = folderRequest.get();
        worker.execute(() -> {
            Bitmap bitmap = decodeScaledBitmap(item.uri);
            if (bitmap != null) bitmapCache.put(key, bitmap);
            loading.remove(key);
            if (bitmap == null) return;

            mainHandler.post(() -> {
                if (expectedFolder != folderRequest.get()) return;
                if (desiredIndex == index) showLoadedBitmap(index, bitmap);
            });
        });
    }

    private Bitmap decodeScaledBitmap(Uri uri) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int target = Math.max(metrics.widthPixels, metrics.heightPixels);
        ContentResolver resolver = getContentResolver();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.Source source = ImageDecoder.createSource(resolver, uri);
                return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    float scale = Math.min(1f, (float) target / Math.max(width, height));
                    decoder.setTargetSize(
                            Math.max(1, Math.round(width * scale)),
                            Math.max(1, Math.round(height * scale)));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = resolver.openInputStream(uri)) {
                BitmapFactory.decodeStream(stream, null, bounds);
            }

            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) {
                sample *= 2;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap;
            try (InputStream stream = resolver.openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(stream, null, options);
            }
            if (bitmap == null) return null;

            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try (InputStream exifStream = resolver.openInputStream(uri)) {
                if (exifStream != null) {
                    ExifInterface exif = new ExifInterface(exifStream);
                    orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL);
                }
            } catch (Exception ignored) {
            }

            Matrix matrix = new Matrix();
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) matrix.postRotate(90);
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) matrix.postRotate(180);
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) matrix.postRotate(270);
            else return bitmap;

            Bitmap rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (Exception e) {
            return null;
        }
    }

    private void showLoadedBitmap(int index, Bitmap bitmap) {
        currentIndex = index;
        imageView.setImageBitmap(bitmap);
        updateStatus();
        preloadAround(currentIndex);
    }

    private void preloadAround(int center) {
        if (photos.isEmpty()) return;
        int count = Math.min(12, photos.size() - 1);
        for (int offset = 1; offset <= count; offset++) {
            requestBitmap((center + offset) % photos.size());
        }
        if (photos.size() > 1) {
            requestBitmap((center - 1 + photos.size()) % photos.size());
        }
    }

    private int chooseNextIndex() {
        if (photos.isEmpty()) return -1;
        if (randomOrder && photos.size() > 1) {
            int next;
            do {
                next = (int) (Math.random() * photos.size());
            } while (next == currentIndex);
            return next;
        }
        if (currentIndex + 1 < photos.size()) return currentIndex + 1;
        return loop ? 0 : -1;
    }

    private void showNextManual() {
        if (photos.isEmpty()) return;
        int next = currentIndex + 1;
        if (next >= photos.size()) next = 0;
        displayIndex(next);
    }

    private void showPreviousManual() {
        if (photos.isEmpty()) return;
        int previous = currentIndex - 1;
        if (previous < 0) previous = photos.size() - 1;
        displayIndex(previous);
    }

    private void startSlideshow() {
        if (photos.isEmpty()) {
            Toast.makeText(this, "Nejdříve vyberte složku s fotografiemi", Toast.LENGTH_SHORT).show();
            return;
        }
        playing = true;
        playButton.setText("Zastavit");
        controls.setVisibility(View.GONE);
        preloadAround(Math.max(0, currentIndex));
        mainHandler.removeCallbacks(slideshowTick);
        mainHandler.postDelayed(slideshowTick, intervalMs);
    }

    private void stopSlideshow() {
        playing = false;
        mainHandler.removeCallbacks(slideshowTick);
        if (playButton != null) playButton.setText("Spustit");
    }

    private void showSettingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        box.setPadding(padding, dp(8), padding, 0);

        TextView intervalLabel = new TextView(this);
        intervalLabel.setText("Čas jedné fotografie v sekundách (minimum 0,1):");
        intervalLabel.setTextSize(16);
        box.addView(intervalLabel);

        EditText intervalInput = new EditText(this);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        intervalInput.setSingleLine(true);
        intervalInput.setText(formatSeconds(intervalMs));
        intervalInput.selectAll();
        box.addView(intervalInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        CheckBox loopBox = new CheckBox(this);
        loopBox.setText("Po poslední fotografii pokračovat od začátku");
        loopBox.setChecked(loop);
        box.addView(loopBox);

        CheckBox randomBox = new CheckBox(this);
        randomBox.setText("Náhodné pořadí");
        randomBox.setChecked(randomOrder);
        box.addView(randomBox);

        CheckBox fillBox = new CheckBox(this);
        fillBox.setText("Vyplnit celou obrazovku (může oříznout okraje)");
        fillBox.setChecked(fillScreen);
        box.addView(fillBox);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Nastavení slideshow")
                .setView(box)
                .setNegativeButton("Zrušit", null)
                .setPositiveButton("Uložit", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = intervalInput.getText().toString().trim().replace(',', '.');
            double seconds;
            try {
                seconds = Double.parseDouble(text);
            } catch (Exception e) {
                intervalInput.setError("Zadejte číslo, například 0,1 nebo 2,5");
                return;
            }
            if (seconds < 0.1 || seconds > 86400) {
                intervalInput.setError("Povolený rozsah je 0,1 až 86400 sekund");
                return;
            }

            intervalMs = Math.max(100, Math.round(seconds * 1000.0));
            loop = loopBox.isChecked();
            randomOrder = randomBox.isChecked();
            fillScreen = fillBox.isChecked();
            imageView.setScaleType(fillScreen
                    ? ImageView.ScaleType.CENTER_CROP
                    : ImageView.ScaleType.FIT_CENTER);

            preferences.edit()
                    .putLong(PREF_INTERVAL_MS, intervalMs)
                    .putBoolean(PREF_LOOP, loop)
                    .putBoolean(PREF_RANDOM, randomOrder)
                    .putBoolean(PREF_FILL, fillScreen)
                    .apply();
            updateStatus();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private String formatSeconds(long milliseconds) {
        if (milliseconds % 1000 == 0) return String.valueOf(milliseconds / 1000);
        return String.format(Locale.US, "%.1f", milliseconds / 1000.0);
    }

    private void updateStatus() {
        if (photos.isEmpty() || currentIndex < 0 || currentIndex >= photos.size()) {
            statusView.setText("Žádná fotografie");
            return;
        }
        statusView.setText((currentIndex + 1) + " / " + photos.size()
                + "   •   " + photos.get(currentIndex).name
                + "   •   " + formatSeconds(intervalMs) + " s");
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopSlideshow();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class PhotoItem {
        final Uri uri;
        final String name;

        PhotoItem(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }
}
