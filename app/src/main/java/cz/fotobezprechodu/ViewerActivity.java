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
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioAttributes;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
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

public class ViewerActivity extends Activity {
    private static final int REQUEST_TREE = 1001;
    private static final String PREFS = "foto_bez_prechodu";
    private static final String PREF_TREE_URI = "tree_uri";
    private static final String PREF_INTERVAL_MS = "interval_ms";
    private static final String PREF_VIDEO_SPEED = "video_speed";
    private static final String PREF_LOOP = "loop";
    private static final String PREF_RANDOM = "random";
    private static final String PREF_FILL = "fill";
    private static final float MIN_ZOOM = 1f;
    private static final float MAX_ZOOM = 100f;

    private final ArrayList<MediaEntry> mediaItems = new ArrayList<>();
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> loading = Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger folderGeneration = new AtomicInteger(0);
    private final AtomicInteger mediaGeneration = new AtomicInteger(0);

    private SharedPreferences preferences;
    private FrameLayout mediaStage;
    private ImageView imageView;
    private TextureView videoView;
    private LinearLayout controls;
    private TextView statusView;
    private Button slideshowButton;
    private Button videoPlayPauseButton;
    private Button videoStopButton;
    private Button zoomLockButton;
    private LruCache<String, Bitmap> bitmapCache;
    private ScaleGestureDetector scaleDetector;
    private MediaPlayer mediaPlayer;

    private int currentIndex = -1;
    private int requestedIndex = -1;
    private int pendingVideoIndex = -1;
    private int pendingVideoGeneration = -1;
    private int videoWidth;
    private int videoHeight;
    private long intervalMs = 1000;
    private float videoSpeed = 1f;
    private boolean slideshowRunning;
    private boolean loop = true;
    private boolean randomOrder;
    private boolean fillScreen;
    private boolean videoPrepared;
    private boolean videoFinished;
    private boolean zoomLocked;

    private float zoomScale = 1f;
    private float zoomX;
    private float zoomY;
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private boolean moved;
    private boolean scaling;

    private final Runnable imageTimer = new Runnable() {
        @Override
        public void run() {
            if (slideshowRunning) advanceToNext();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        intervalMs = Math.max(100L, preferences.getLong(PREF_INTERVAL_MS, 1000L));
        videoSpeed = clamp(preferences.getFloat(PREF_VIDEO_SPEED, 1f), 0.1f, 8f);
        loop = preferences.getBoolean(PREF_LOOP, true);
        randomOrder = preferences.getBoolean(PREF_RANDOM, false);
        fillScreen = preferences.getBoolean(PREF_FILL, false);

        int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        int cacheSizeKb = Math.max(16 * 1024, Math.min(160 * 1024, maxMemoryKb / 3));
        bitmapCache = new LruCache<String, Bitmap>(cacheSizeKb) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return Math.max(1, bitmap.getByteCount() / 1024);
            }
        };

        buildUi();
        enterImmersiveMode();

        if (!handleExternalIntent(getIntent())) {
            String savedTree = preferences.getString(PREF_TREE_URI, null);
            if (savedTree != null) loadFolder(Uri.parse(savedTree));
            else statusView.setText("Vyberte složku s fotografiemi a videi");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleExternalIntent(intent);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        mediaStage = new FrameLayout(this);
        mediaStage.setBackgroundColor(0xFF000000);
        mediaStage.setPivotX(0f);
        mediaStage.setPivotY(0f);
        root.addView(mediaStage, matchParent());

        imageView = new ImageView(this);
        imageView.setBackgroundColor(0xFF000000);
        imageView.setAdjustViewBounds(false);
        updateImageScaleType();
        mediaStage.addView(imageView, matchParent());

        videoView = new TextureView(this);
        videoView.setOpaque(false);
        videoView.setVisibility(View.VISIBLE);
        videoView.setAlpha(0f);
        mediaStage.addView(videoView, matchParent());
        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (pendingVideoIndex >= 0) {
                    int index = pendingVideoIndex;
                    int generation = pendingVideoGeneration;
                    pendingVideoIndex = -1;
                    pendingVideoGeneration = -1;
                    prepareVideo(index, generation);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                updateVideoTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                releaseVideoPlayer();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
        videoView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateVideoTransform());

        View touchLayer = new View(this);
        touchLayer.setBackgroundColor(0x00000000);
        root.addView(touchLayer, matchParent());

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(8), dp(7), dp(8), dp(7));
        controls.setBackground(new ColorDrawable(0xB8000000));

        statusView = new TextView(this);
        statusView.setTextColor(0xFFFFFFFF);
        statusView.setTextSize(14);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(4), 0, dp(4), dp(5));
        controls.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout mainRow = horizontalRow();
        Button folderButton = makeButton("Složka");
        Button previousButton = makeButton("◀");
        slideshowButton = makeButton("Slideshow");
        Button nextButton = makeButton("▶");
        Button settingsButton = makeButton("Nastavení");
        mainRow.addView(folderButton, weighted(1.25f));
        mainRow.addView(previousButton, weighted(0.65f));
        mainRow.addView(slideshowButton, weighted(1.25f));
        mainRow.addView(nextButton, weighted(0.65f));
        mainRow.addView(settingsButton, weighted(1.45f));
        controls.addView(mainRow);

        LinearLayout mediaRow = horizontalRow();
        videoPlayPauseButton = makeButton("▶ Video");
        videoStopButton = makeButton("■ Stop");
        zoomLockButton = makeButton("🔓 Pozice");
        mediaRow.addView(videoPlayPauseButton, weighted(1f));
        mediaRow.addView(videoStopButton, weighted(1f));
        mediaRow.addView(zoomLockButton, weighted(1.15f));
        controls.addView(mediaRow);

        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(controls, controlParams);
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
        slideshowButton.setOnClickListener(v -> {
            if (slideshowRunning) stopSlideshow();
            else startSlideshow();
        });
        settingsButton.setOnClickListener(v -> showSettingsDialog());
        videoPlayPauseButton.setOnClickListener(v -> toggleVideoPlayback());
        videoStopButton.setOnClickListener(v -> stopCurrentVideo());
        zoomLockButton.setOnClickListener(v -> toggleZoomLock());

        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        scaling = true;
                        stopImageSlideshowForGesture();
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float old = zoomScale;
                        float next = clamp(old * detector.getScaleFactor(), MIN_ZOOM, MAX_ZOOM);
                        if (Math.abs(next - old) < 0.0001f) return true;
                        float ratio = next / old;
                        zoomX = detector.getFocusX() - (detector.getFocusX() - zoomX) * ratio;
                        zoomY = detector.getFocusY() - (detector.getFocusY() - zoomY) * ratio;
                        zoomScale = next;
                        clampZoom();
                        applyZoom();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        clampZoom();
                        applyZoom();
                        updateStatus();
                    }
                });
        touchLayer.setOnTouchListener(this::handleTouch);
        updateVideoButtons();
        updateZoomLockButton();
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setPadding(dp(3), 0, dp(3), 0);
        return button;
    }

    private LinearLayout.LayoutParams weighted(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(45), weight);
        params.setMargins(dp(2), dp(1), dp(2), dp(1));
        return params;
    }

    private boolean handleTouch(View view, MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                moved = false;
                scaling = false;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                scaling = true;
                moved = true;
                stopImageSlideshowForGesture();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()
                        && zoomScale > MIN_ZOOM + 0.001f) {
                    float x = event.getX();
                    float y = event.getY();
                    zoomX += x - lastX;
                    zoomY += y - lastY;
                    if (Math.abs(x - downX) > dp(3) || Math.abs(y - downY) > dp(3)) moved = true;
                    lastX = x;
                    lastY = y;
                    clampZoom();
                    applyZoom();
                    stopImageSlideshowForGesture();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!scaling) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (zoomScale <= MIN_ZOOM + 0.001f
                            && Math.abs(dx) > dp(60) && Math.abs(dx) > Math.abs(dy)) {
                        stopSlideshow();
                        if (dx < 0) showNextManual();
                        else showPreviousManual();
                    } else if (!moved && Math.abs(dx) < dp(12) && Math.abs(dy) < dp(12)) {
                        controls.setVisibility(controls.getVisibility() == View.VISIBLE
                                ? View.GONE : View.VISIBLE);
                    }
                }
                scaling = false;
                updateStatus();
                return true;
            case MotionEvent.ACTION_CANCEL:
                scaling = false;
                return true;
            default:
                return true;
        }
    }

    private void stopImageSlideshowForGesture() {
        if (currentIndex >= 0 && currentIndex < mediaItems.size()
                && !mediaItems.get(currentIndex).video) stopSlideshow();
    }

    private void toggleZoomLock() {
        zoomLocked = !zoomLocked;
        updateZoomLockButton();
        updateStatus();
    }

    private void updateZoomLockButton() {
        if (zoomLockButton != null) zoomLockButton.setText(zoomLocked ? "🔒 Pozice" : "🔓 Pozice");
    }

    private void resetZoom() {
        zoomScale = MIN_ZOOM;
        zoomX = 0f;
        zoomY = 0f;
        applyZoom();
    }

    private void clampZoom() {
        if (mediaStage == null || zoomScale <= MIN_ZOOM + 0.001f) {
            zoomScale = MIN_ZOOM;
            zoomX = 0f;
            zoomY = 0f;
            return;
        }
        float width = mediaStage.getWidth();
        float height = mediaStage.getHeight();
        if (width <= 0 || height <= 0) return;
        zoomX = clamp(zoomX, width - width * zoomScale, 0f);
        zoomY = clamp(zoomY, height - height * zoomScale, 0f);
    }

    private void applyZoom() {
        if (mediaStage == null) return;
        mediaStage.setScaleX(zoomScale);
        mediaStage.setScaleY(zoomScale);
        mediaStage.setTranslationX(zoomX);
        mediaStage.setTranslationY(zoomY);
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
        if (requestCode == REQUEST_TREE && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            Uri treeUri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            preferences.edit().putString(PREF_TREE_URI, treeUri.toString()).apply();
            loadFolder(treeUri);
        }
    }

    private boolean handleExternalIntent(Intent intent) {
        if (intent == null) return false;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) uri = intent.getData();
        else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            if (Build.VERSION.SDK_INT >= 33) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            else {
                Object value = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (value instanceof Uri) uri = (Uri) value;
            }
        }
        if (uri == null) return false;

        int flags = intent.getFlags();
        if ((flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }

        String name = resolveDisplayName(uri);
        String mime = getContentResolver().getType(uri);
        boolean video = isVideo(mime, name);
        if (!isSupportedMedia(mime, name)) {
            Toast.makeText(this, "Tento typ souboru není podporován", Toast.LENGTH_SHORT).show();
            return false;
        }

        stopSlideshow();
        releaseVideoPlayer();
        zoomLocked = false;
        resetZoom();
        updateZoomLockButton();
        folderGeneration.incrementAndGet();
        mediaGeneration.incrementAndGet();
        loading.clear();
        bitmapCache.evictAll();
        mediaItems.clear();
        mediaItems.add(new MediaEntry(uri, name, mime, video));
        currentIndex = -1;
        requestedIndex = -1;
        displayIndex(0);
        return true;
    }

    private String resolveDisplayName(Uri uri) {
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(
                    uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (column >= 0) {
                        String value = cursor.getString(column);
                        if (value != null && !value.isBlank()) return value;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        String segment = uri.getLastPathSegment();
        return segment == null || segment.isBlank() ? "Otevřený soubor" : segment;
    }

    private void loadFolder(Uri treeUri) {
        stopSlideshow();
        releaseVideoPlayer();
        zoomLocked = false;
        resetZoom();
        updateZoomLockButton();
        statusView.setText("Načítám fotografie a videa…");
        int generation = folderGeneration.incrementAndGet();
        mediaGeneration.incrementAndGet();

        worker.execute(() -> {
            ArrayList<MediaEntry> found = scanTree(treeUri);
            Collator collator = Collator.getInstance(new Locale("cs", "CZ"));
            collator.setStrength(Collator.PRIMARY);
            found.sort((a, b) -> naturalCompare(a.name, b.name, collator));
            mainHandler.post(() -> {
                if (generation != folderGeneration.get()) return;
                mediaItems.clear();
                mediaItems.addAll(found);
                loading.clear();
                bitmapCache.evictAll();
                currentIndex = -1;
                requestedIndex = -1;
                if (mediaItems.isEmpty()) {
                    imageView.setImageDrawable(null);
                    videoView.setAlpha(0f);
                    statusView.setText("Ve složce nejsou podporované fotografie ani videa");
                    updateVideoButtons();
                } else displayIndex(0);
            });
        });
    }

    private ArrayList<MediaEntry> scanTree(Uri treeUri) {
        ArrayList<MediaEntry> result = new ArrayList<>();
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
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
            try (Cursor cursor = getContentResolver().query(children, columns, null, null, null)) {
                if (cursor == null) continue;
                int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                while (cursor.moveToNext()) {
                    String id = cursor.getString(idColumn);
                    String name = cursor.getString(nameColumn);
                    String mime = cursor.getString(mimeColumn);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) folders.addLast(id);
                    else if (isSupportedMedia(mime, name)) {
                        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                        result.add(new MediaEntry(uri, name == null ? "" : name,
                                mime, isVideo(mime, name)));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private boolean isSupportedMedia(String mime, String name) {
        if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) return true;
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(jpg|jpeg|png|webp|bmp|gif|heic|heif|mp4|m4v|mov|mkv|webm|3gp|avi)$");
    }

    private boolean isVideo(String mime, String name) {
        if (mime != null && mime.startsWith("video/")) return true;
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(mp4|m4v|mov|mkv|webm|3gp|avi)$");
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

    private void displayIndex(int index) {
        if (index < 0 || index >= mediaItems.size()) return;
        requestedIndex = index;
        currentIndex = index;
        if (!zoomLocked) resetZoom();
        else mediaStage.post(() -> {
            clampZoom();
            applyZoom();
        });
        mainHandler.removeCallbacks(imageTimer);
        int generation = mediaGeneration.incrementAndGet();
        MediaEntry entry = mediaItems.get(index);
        updateStatus();
        if (entry.video) displayVideo(index, generation);
        else displayImage(index, generation);
    }

    private void displayImage(int index, int generation) {
        releaseVideoPlayer();
        videoView.setAlpha(0f);
        imageView.setAlpha(1f);
        imageView.setVisibility(View.VISIBLE);
        updateVideoButtons();
        MediaEntry entry = mediaItems.get(index);
        String key = imageKey(entry);
        Bitmap cached = bitmapCache.get(key);
        if (cached != null) showLoadedImage(index, generation, cached);
        else requestImage(index);
    }

    private void requestImage(int index) {
        if (index < 0 || index >= mediaItems.size()) return;
        MediaEntry entry = mediaItems.get(index);
        if (entry.video) return;
        String key = imageKey(entry);
        Bitmap cached = bitmapCache.get(key);
        if (cached != null) {
            if (requestedIndex == index) showLoadedImage(index, mediaGeneration.get(), cached);
            return;
        }
        if (!loading.add(key)) return;
        int folder = folderGeneration.get();
        worker.execute(() -> {
            Bitmap bitmap = decodeScaledBitmap(entry.uri);
            if (bitmap != null) bitmapCache.put(key, bitmap);
            loading.remove(key);
            if (bitmap == null) return;
            mainHandler.post(() -> {
                if (folder != folderGeneration.get()) return;
                if (requestedIndex == index && index < mediaItems.size()
                        && !mediaItems.get(index).video) {
                    showLoadedImage(index, mediaGeneration.get(), bitmap);
                }
            });
        });
    }

    private void showLoadedImage(int index, int generation, Bitmap bitmap) {
        if (generation != mediaGeneration.get() || requestedIndex != index) return;
        imageView.setImageBitmap(bitmap);
        videoView.setAlpha(0f);
        imageView.setAlpha(1f);
        updateStatus();
        preloadAround(index);
        scheduleCurrentItem();
    }

    private void displayVideo(int index, int generation) {
        releaseVideoPlayer();
        videoFinished = false;
        videoView.setVisibility(View.VISIBLE);
        videoView.setAlpha(0f);
        imageView.setVisibility(View.VISIBLE);
        imageView.setAlpha(1f);
        requestVideoPoster(index);
        prepareVideo(index, generation);
        preloadAround(index);
        updateVideoButtons();
    }

    private void requestVideoPoster(int index) {
        if (index < 0 || index >= mediaItems.size()) return;
        MediaEntry entry = mediaItems.get(index);
        if (!entry.video) return;
        String key = posterKey(entry);
        Bitmap cached = bitmapCache.get(key);
        if (cached != null) {
            if (requestedIndex == index) imageView.setImageBitmap(cached);
            return;
        }
        if (!loading.add(key)) return;
        int folder = folderGeneration.get();
        worker.execute(() -> {
            Bitmap bitmap = decodeVideoPoster(entry.uri);
            if (bitmap != null) bitmapCache.put(key, bitmap);
            loading.remove(key);
            if (bitmap == null) return;
            mainHandler.post(() -> {
                if (folder == folderGeneration.get() && requestedIndex == index
                        && index < mediaItems.size() && mediaItems.get(index).video) {
                    imageView.setImageBitmap(bitmap);
                }
            });
        });
    }

    private void prepareVideo(int index, int generation) {
        if (index < 0 || index >= mediaItems.size()) return;
        if (generation != mediaGeneration.get() || requestedIndex != index) return;
        if (!videoView.isAvailable()) {
            pendingVideoIndex = index;
            pendingVideoGeneration = generation;
            return;
        }

        MediaEntry entry = mediaItems.get(index);
        try {
            MediaPlayer player = new MediaPlayer();
            mediaPlayer = player;
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
            Surface surface = new Surface(videoView.getSurfaceTexture());
            player.setSurface(surface);
            surface.release();
            player.setDataSource(this, entry.uri);

            player.setOnVideoSizeChangedListener((mp, width, height) -> {
                if (mp != mediaPlayer) return;
                videoWidth = width;
                videoHeight = height;
                updateVideoTransform();
            });
            player.setOnPreparedListener(mp -> {
                if (mp != mediaPlayer || generation != mediaGeneration.get() || requestedIndex != index) {
                    safeRelease(mp);
                    return;
                }
                videoPrepared = true;
                videoFinished = false;
                videoWidth = Math.max(videoWidth, mp.getVideoWidth());
                videoHeight = Math.max(videoHeight, mp.getVideoHeight());
                updateVideoTransform();
                applyVideoSpeed();
                try {
                    mp.start();
                } catch (Exception e) {
                    Toast.makeText(this, "Video se nepodařilo spustit", Toast.LENGTH_SHORT).show();
                }
                updateVideoButtons();
                updateStatus();
            });
            player.setOnInfoListener((mp, what, extra) -> {
                if (mp == mediaPlayer && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START
                        && generation == mediaGeneration.get() && requestedIndex == index) {
                    videoView.setAlpha(1f);
                    imageView.setAlpha(0f);
                    return true;
                }
                return false;
            });
            player.setOnCompletionListener(mp -> {
                if (mp != mediaPlayer || generation != mediaGeneration.get() || requestedIndex != index) return;
                videoFinished = true;
                updateVideoButtons();
                if (slideshowRunning) advanceToNext();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                if (mp == mediaPlayer && generation == mediaGeneration.get() && requestedIndex == index) {
                    videoView.setAlpha(0f);
                    imageView.setAlpha(1f);
                    Toast.makeText(this, "Video se nepodařilo přehrát", Toast.LENGTH_SHORT).show();
                    updateVideoButtons();
                    if (slideshowRunning) mainHandler.postDelayed(this::advanceToNext, 300);
                }
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            releaseVideoPlayer();
            Toast.makeText(this, "Video se nepodařilo otevřít", Toast.LENGTH_SHORT).show();
            if (slideshowRunning) mainHandler.postDelayed(this::advanceToNext, 300);
        }
    }

    private void toggleVideoPlayback() {
        if (!currentIsVideo()) return;
        MediaPlayer player = mediaPlayer;
        if (player == null || !videoPrepared) {
            displayIndex(currentIndex);
            return;
        }
        try {
            if (player.isPlaying()) player.pause();
            else {
                if (videoFinished) player.seekTo(0);
                applyVideoSpeed();
                player.start();
                videoFinished = false;
            }
        } catch (Exception e) {
            displayIndex(currentIndex);
        }
        updateVideoButtons();
    }

    private void stopCurrentVideo() {
        if (!currentIsVideo()) return;
        slideshowRunning = false;
        mainHandler.removeCallbacks(imageTimer);
        updateSlideshowButton();
        MediaPlayer player = mediaPlayer;
        if (player != null && videoPrepared) {
            try {
                if (player.isPlaying()) player.pause();
                player.seekTo(0);
                videoFinished = false;
                videoView.setAlpha(0f);
                imageView.setAlpha(1f);
            } catch (Exception ignored) {
            }
        }
        updateVideoButtons();
    }

    private boolean currentIsVideo() {
        return currentIndex >= 0 && currentIndex < mediaItems.size() && mediaItems.get(currentIndex).video;
    }

    private void updateVideoButtons() {
        boolean video = currentIsVideo();
        if (videoPlayPauseButton == null || videoStopButton == null) return;
        videoPlayPauseButton.setEnabled(video);
        videoStopButton.setEnabled(video);
        boolean isPlaying = false;
        if (video && mediaPlayer != null && videoPrepared) {
            try {
                isPlaying = mediaPlayer.isPlaying();
            } catch (Exception ignored) {
            }
        }
        videoPlayPauseButton.setText(isPlaying ? "⏸ Pauza" : "▶ Video");
    }

    private void applyVideoSpeed() {
        if (mediaPlayer == null || !videoPrepared) return;
        try {
            PlaybackParams params = new PlaybackParams();
            params.setSpeed(videoSpeed);
            params.setPitch(1f);
            mediaPlayer.setPlaybackParams(params);
        } catch (Exception ignored) {
        }
    }

    private void updateVideoTransform() {
        if (videoWidth <= 0 || videoHeight <= 0 || videoView == null) return;
        int viewWidth = videoView.getWidth();
        int viewHeight = videoView.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) return;
        float viewAspect = (float) viewWidth / viewHeight;
        float videoAspect = (float) videoWidth / videoHeight;
        float scaleX = 1f;
        float scaleY = 1f;
        if (fillScreen) {
            if (videoAspect > viewAspect) scaleX = videoAspect / viewAspect;
            else scaleY = viewAspect / videoAspect;
        } else {
            if (videoAspect > viewAspect) scaleY = viewAspect / videoAspect;
            else scaleX = videoAspect / viewAspect;
        }
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
        videoView.setTransform(matrix);
    }

    private void releaseVideoPlayer() {
        pendingVideoIndex = -1;
        pendingVideoGeneration = -1;
        videoPrepared = false;
        videoFinished = false;
        videoWidth = 0;
        videoHeight = 0;
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        if (player != null) safeRelease(player);
        if (videoView != null) videoView.setAlpha(0f);
        if (imageView != null) imageView.setAlpha(1f);
        updateVideoButtons();
    }

    private void safeRelease(MediaPlayer player) {
        try {
            player.setSurface(null);
        } catch (Exception ignored) {
        }
        try {
            player.reset();
        } catch (Exception ignored) {
        }
        try {
            player.release();
        } catch (Exception ignored) {
        }
    }

    private Bitmap decodeScaledBitmap(Uri uri) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screen = Math.max(metrics.widthPixels, metrics.heightPixels);
        int target = Math.min(4096, Math.max(screen, screen * 3));
        ContentResolver resolver = getContentResolver();
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
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = resolver.openInputStream(uri)) {
                BitmapFactory.decodeStream(stream, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2;
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
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL);
                }
            } catch (Exception ignored) {
            }
            return rotateBitmap(bitmap, orientation);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private Bitmap decodeVideoPoster(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            if ("90".equals(rotation)) orientation = ExifInterface.ORIENTATION_ROTATE_90;
            else if ("180".equals(rotation)) orientation = ExifInterface.ORIENTATION_ROTATE_180;
            else if ("270".equals(rotation)) orientation = ExifInterface.ORIENTATION_ROTATE_270;
            frame = rotateBitmap(frame, orientation);
            int max = Math.max(frame.getWidth(), frame.getHeight());
            int screen = Math.max(getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            int target = Math.min(2560, screen * 2);
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

    private Bitmap rotateBitmap(Bitmap bitmap, int orientation) {
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

    private String imageKey(MediaEntry entry) {
        return "image:" + entry.uri;
    }

    private String posterKey(MediaEntry entry) {
        return "poster:" + entry.uri;
    }

    private void preloadAround(int center) {
        if (mediaItems.isEmpty()) return;
        int count = Math.min(10, mediaItems.size() - 1);
        for (int offset = 1; offset <= count; offset++) preloadItem((center + offset) % mediaItems.size());
        if (mediaItems.size() > 1) preloadItem((center - 1 + mediaItems.size()) % mediaItems.size());
    }

    private void preloadItem(int index) {
        MediaEntry entry = mediaItems.get(index);
        if (entry.video) requestVideoPoster(index);
        else requestImage(index);
    }

    private void showNextManual() {
        if (mediaItems.isEmpty()) return;
        int next = currentIndex + 1;
        if (next >= mediaItems.size()) next = 0;
        displayIndex(next);
    }

    private void showPreviousManual() {
        if (mediaItems.isEmpty()) return;
        int previous = currentIndex - 1;
        if (previous < 0) previous = mediaItems.size() - 1;
        displayIndex(previous);
    }

    private int chooseNextIndex() {
        if (mediaItems.isEmpty()) return -1;
        if (randomOrder && mediaItems.size() > 1) {
            int next;
            do next = (int) (Math.random() * mediaItems.size());
            while (next == currentIndex);
            return next;
        }
        if (currentIndex + 1 < mediaItems.size()) return currentIndex + 1;
        return loop ? 0 : -1;
    }

    private void advanceToNext() {
        if (!slideshowRunning || mediaItems.isEmpty()) return;
        int next = chooseNextIndex();
        if (next < 0) {
            stopSlideshow();
            return;
        }
        displayIndex(next);
    }

    private void startSlideshow() {
        if (mediaItems.isEmpty()) {
            Toast.makeText(this, "Nejdříve vyberte složku nebo otevřete soubor",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        slideshowRunning = true;
        updateSlideshowButton();
        controls.setVisibility(View.GONE);
        if (!zoomLocked) resetZoom();
        if (currentIsVideo()) {
            MediaPlayer player = mediaPlayer;
            if (player != null && videoPrepared) {
                try {
                    if (videoFinished) player.seekTo(0);
                    applyVideoSpeed();
                    if (!player.isPlaying()) player.start();
                    videoFinished = false;
                } catch (Exception e) {
                    displayIndex(currentIndex);
                }
            } else displayIndex(Math.max(0, currentIndex));
        } else scheduleCurrentItem();
    }

    private void stopSlideshow() {
        slideshowRunning = false;
        mainHandler.removeCallbacks(imageTimer);
        updateSlideshowButton();
        if (mediaPlayer != null && videoPrepared) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            } catch (Exception ignored) {
            }
        }
        updateVideoButtons();
    }

    private void updateSlideshowButton() {
        if (slideshowButton != null) slideshowButton.setText(slideshowRunning ? "Zastavit" : "Slideshow");
    }

    private void scheduleCurrentItem() {
        mainHandler.removeCallbacks(imageTimer);
        if (slideshowRunning && currentIndex >= 0 && currentIndex < mediaItems.size()
                && !mediaItems.get(currentIndex).video) {
            mainHandler.postDelayed(imageTimer, intervalMs);
        }
    }

    private void showSettingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        TextView intervalLabel = new TextView(this);
        intervalLabel.setText("Čas fotografie v sekundách (minimum 0,1):");
        intervalLabel.setTextSize(16);
        box.addView(intervalLabel);
        EditText intervalInput = new EditText(this);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        intervalInput.setSingleLine(true);
        intervalInput.setText(formatSeconds(intervalMs));
        box.addView(intervalInput);

        TextView speedLabel = new TextView(this);
        speedLabel.setText("Rychlost videa (0,1× až 8×):");
        speedLabel.setTextSize(16);
        speedLabel.setPadding(0, dp(10), 0, 0);
        box.addView(speedLabel);
        EditText speedInput = new EditText(this);
        speedInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        speedInput.setSingleLine(true);
        speedInput.setText(formatSpeed(videoSpeed));
        box.addView(speedInput);

        CheckBox loopBox = new CheckBox(this);
        loopBox.setText("Po posledním souboru pokračovat od začátku");
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
                .setTitle("Nastavení")
                .setView(box)
                .setNegativeButton("Zrušit", null)
                .setPositiveButton("Uložit", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    double seconds;
                    try {
                        seconds = Double.parseDouble(intervalInput.getText().toString()
                                .trim().replace(',', '.'));
                    } catch (Exception e) {
                        intervalInput.setError("Zadejte například 0,1 nebo 2,5");
                        return;
                    }
                    if (seconds < 0.1 || seconds > 86400) {
                        intervalInput.setError("Rozsah je 0,1 až 86400 sekund");
                        return;
                    }
                    float speed;
                    try {
                        speed = Float.parseFloat(speedInput.getText().toString()
                                .trim().replace(',', '.'));
                    } catch (Exception e) {
                        speedInput.setError("Zadejte například 0,1 nebo 1,5");
                        return;
                    }
                    if (speed < 0.1f || speed > 8f) {
                        speedInput.setError("Rozsah je 0,1× až 8×");
                        return;
                    }
                    intervalMs = Math.max(100L, Math.round(seconds * 1000.0));
                    videoSpeed = speed;
                    loop = loopBox.isChecked();
                    randomOrder = randomBox.isChecked();
                    fillScreen = fillBox.isChecked();
                    updateImageScaleType();
                    updateVideoTransform();
                    applyVideoSpeed();
                    preferences.edit()
                            .putLong(PREF_INTERVAL_MS, intervalMs)
                            .putFloat(PREF_VIDEO_SPEED, videoSpeed)
                            .putBoolean(PREF_LOOP, loop)
                            .putBoolean(PREF_RANDOM, randomOrder)
                            .putBoolean(PREF_FILL, fillScreen)
                            .apply();
                    updateStatus();
                    scheduleCurrentItem();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void updateImageScaleType() {
        if (imageView != null) imageView.setScaleType(fillScreen
                ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
    }

    private String formatSeconds(long milliseconds) {
        if (milliseconds % 1000 == 0) return String.valueOf(milliseconds / 1000);
        return String.format(Locale.US, "%.1f", milliseconds / 1000.0);
    }

    private String formatSpeed(float speed) {
        if (Math.abs(speed - Math.round(speed)) < 0.001f) return String.valueOf(Math.round(speed));
        return String.format(Locale.US, "%.1f", speed);
    }

    private void updateStatus() {
        if (mediaItems.isEmpty() || currentIndex < 0 || currentIndex >= mediaItems.size()) {
            statusView.setText("Žádný soubor");
            return;
        }
        MediaEntry entry = mediaItems.get(currentIndex);
        String timing = entry.video ? "video " + formatSpeed(videoSpeed) + "×"
                : formatSeconds(intervalMs) + " s";
        String lock = zoomLocked ? "   •   🔒 " + formatSpeed(zoomScale) + "×" : "";
        statusView.setText((currentIndex + 1) + " / " + mediaItems.size()
                + "   •   " + entry.name + "   •   " + timing + lock);
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
        releaseVideoPlayer();
        worker.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static class MediaEntry {
        final Uri uri;
        final String name;
        final String mime;
        final boolean video;

        MediaEntry(Uri uri, String name, String mime, boolean video) {
            this.uri = uri;
            this.name = name;
            this.mime = mime;
            this.video = video;
        }
    }
}
