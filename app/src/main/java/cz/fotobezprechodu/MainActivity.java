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

public class MainActivity extends Activity {
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
    private final AtomicInteger folderRequest = new AtomicInteger(0);
    private final AtomicInteger mediaRequest = new AtomicInteger(0);

    private SharedPreferences preferences;
    private FrameLayout mediaContainer;
    private ImageView imageView;
    private TextureView videoView;
    private LinearLayout controls;
    private TextView statusView;
    private Button playButton;
    private LruCache<String, Bitmap> bitmapCache;
    private ScaleGestureDetector scaleGestureDetector;
    private MediaPlayer mediaPlayer;

    private int currentIndex = -1;
    private int desiredIndex = -1;
    private int pendingVideoIndex = -1;
    private int pendingVideoToken = -1;
    private int videoWidth;
    private int videoHeight;
    private long intervalMs = 1000;
    private float videoSpeed = 1f;
    private boolean playing = false;
    private boolean loop = true;
    private boolean randomOrder = false;
    private boolean fillScreen = false;
    private boolean videoPrepared = false;
    private boolean videoFinished = false;

    private float zoomScale = 1f;
    private float zoomTranslationX = 0f;
    private float zoomTranslationY = 0f;
    private float touchDownX;
    private float touchDownY;
    private float lastTouchX;
    private float lastTouchY;
    private boolean touchMoved;
    private boolean scalingGesture;

    private final Runnable slideshowTick = new Runnable() {
        @Override
        public void run() {
            if (!playing || mediaItems.isEmpty()) return;
            advanceToNext();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        intervalMs = Math.max(100, preferences.getLong(PREF_INTERVAL_MS, 1000));
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

        String savedTree = preferences.getString(PREF_TREE_URI, null);
        if (savedTree != null) {
            loadFolder(Uri.parse(savedTree));
        } else {
            statusView.setText("Vyberte složku s fotografiemi a videi");
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        mediaContainer = new FrameLayout(this);
        mediaContainer.setBackgroundColor(0xFF000000);
        mediaContainer.setPivotX(0f);
        mediaContainer.setPivotY(0f);
        root.addView(mediaContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        imageView = new ImageView(this);
        imageView.setBackgroundColor(0xFF000000);
        imageView.setScaleType(fillScreen ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(false);
        mediaContainer.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        videoView = new TextureView(this);
        videoView.setOpaque(false);
        videoView.setVisibility(View.INVISIBLE);
        mediaContainer.addView(videoView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (pendingVideoIndex >= 0 && pendingVideoToken >= 0) {
                    int index = pendingVideoIndex;
                    int token = pendingVideoToken;
                    pendingVideoIndex = -1;
                    pendingVideoToken = -1;
                    prepareVideo(index, token);
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
        videoView.addOnLayoutChangeListener((v, left, top, right, bottom,
                                             oldLeft, oldTop, oldRight, oldBottom) -> updateVideoTransform());

        View touchLayer = new View(this);
        touchLayer.setBackgroundColor(0x00000000);
        root.addView(touchLayer, new FrameLayout.LayoutParams(
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

        scaleGestureDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        scalingGesture = true;
                        stopImageSlideshowForGesture();
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float oldScale = zoomScale;
                        float newScale = clamp(oldScale * detector.getScaleFactor(), MIN_ZOOM, MAX_ZOOM);
                        if (Math.abs(newScale - oldScale) < 0.0001f) return true;

                        float ratio = newScale / oldScale;
                        zoomTranslationX = detector.getFocusX()
                                - (detector.getFocusX() - zoomTranslationX) * ratio;
                        zoomTranslationY = detector.getFocusY()
                                - (detector.getFocusY() - zoomTranslationY) * ratio;
                        zoomScale = newScale;
                        clampZoomTranslation();
                        applyZoom();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        clampZoomTranslation();
                        applyZoom();
                    }
                });

        touchLayer.setOnTouchListener(this::handleMediaTouch);
    }

    private boolean handleMediaTouch(View view, MotionEvent event) {
        scaleGestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                lastTouchX = touchDownX;
                lastTouchY = touchDownY;
                touchMoved = false;
                scalingGesture = false;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                scalingGesture = true;
                touchMoved = true;
                stopImageSlideshowForGesture();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1 && !scaleGestureDetector.isInProgress()
                        && zoomScale > MIN_ZOOM + 0.001f) {
                    float x = event.getX();
                    float y = event.getY();
                    float dx = x - lastTouchX;
                    float dy = y - lastTouchY;
                    if (Math.abs(x - touchDownX) > dp(3) || Math.abs(y - touchDownY) > dp(3)) {
                        touchMoved = true;
                    }
                    zoomTranslationX += dx;
                    zoomTranslationY += dy;
                    clampZoomTranslation();
                    applyZoom();
                    lastTouchX = x;
                    lastTouchY = y;
                    stopImageSlideshowForGesture();
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (!scalingGesture) {
                    float dx = event.getX() - touchDownX;
                    float dy = event.getY() - touchDownY;
                    if (zoomScale <= MIN_ZOOM + 0.001f
                            && Math.abs(dx) > dp(60)
                            && Math.abs(dx) > Math.abs(dy)) {
                        stopSlideshow();
                        if (dx < 0) showNextManual(); else showPreviousManual();
                    } else if (!touchMoved && Math.abs(dx) < dp(12) && Math.abs(dy) < dp(12)) {
                        controls.setVisibility(
                                controls.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                    }
                }
                scalingGesture = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                scalingGesture = false;
                return true;

            default:
                return true;
        }
    }

    private void stopImageSlideshowForGesture() {
        if (currentIndex >= 0 && currentIndex < mediaItems.size()
                && !mediaItems.get(currentIndex).video) {
            stopSlideshow();
        }
    }

    private void resetZoom() {
        zoomScale = MIN_ZOOM;
        zoomTranslationX = 0f;
        zoomTranslationY = 0f;
        applyZoom();
    }

    private void clampZoomTranslation() {
        if (mediaContainer == null || zoomScale <= MIN_ZOOM + 0.001f) {
            zoomScale = MIN_ZOOM;
            zoomTranslationX = 0f;
            zoomTranslationY = 0f;
            return;
        }
        float width = mediaContainer.getWidth();
        float height = mediaContainer.getHeight();
        if (width <= 0 || height <= 0) return;

        float minX = width - width * zoomScale;
        float minY = height - height * zoomScale;
        zoomTranslationX = clamp(zoomTranslationX, minX, 0f);
        zoomTranslationY = clamp(zoomTranslationY, minY, 0f);
    }

    private void applyZoom() {
        if (mediaContainer == null) return;
        mediaContainer.setScaleX(zoomScale);
        mediaContainer.setScaleY(zoomScale);
        mediaContainer.setTranslationX(zoomTranslationX);
        mediaContainer.setTranslationY(zoomTranslationY);
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
        if (requestCode == REQUEST_TREE && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
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
        releaseVideoPlayer();
        resetZoom();
        statusView.setText("Načítám fotografie a videa…");
        int request = folderRequest.incrementAndGet();
        mediaRequest.incrementAndGet();

        worker.execute(() -> {
            ArrayList<MediaEntry> found = scanTree(treeUri);
            Collator collator = Collator.getInstance(new Locale("cs", "CZ"));
            collator.setStrength(Collator.PRIMARY);
            found.sort((a, b) -> naturalCompare(a.name, b.name, collator));

            mainHandler.post(() -> {
                if (request != folderRequest.get()) return;
                mediaItems.clear();
                mediaItems.addAll(found);
                bitmapCache.evictAll();
                loading.clear();
                currentIndex = -1;
                desiredIndex = -1;

                if (mediaItems.isEmpty()) {
                    statusView.setText("Ve složce nebyly nalezeny žádné fotografie ani video");
                    imageView.setImageDrawable(null);
                    videoView.setVisibility(View.INVISIBLE);
                } else {
                    displayIndex(0);
                }
            });
        });
    }

    private ArrayList<MediaEntry> scanTree(Uri treeUri) {
        ArrayList<MediaEntry> result = new ArrayList<>();
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
                    } else if (isSupportedMedia(mime, name)) {
                        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                        boolean video = isVideo(mime, name);
                        result.add(new MediaEntry(documentUri, name == null ? "" : name, mime, video));
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
        if (index < 0 || index >= mediaItems.size()) return;
        desiredIndex = index;
        currentIndex = index;
        resetZoom();
        mainHandler.removeCallbacks(slideshowTick);
        int token = mediaRequest.incrementAndGet();
        MediaEntry entry = mediaItems.get(index);
        updateStatus();

        if (entry.video) {
            displayVideo(index, token);
        } else {
            displayImage(index, token);
        }
    }

    private void displayImage(int index, int token) {
        releaseVideoPlayer();
        videoView.setVisibility(View.INVISIBLE);
        imageView.setVisibility(View.VISIBLE);

        String key = imageKey(mediaItems.get(index));
        Bitmap cached = bitmapCache.get(key);
        if (cached != null) {
            showLoadedImage(index, token, cached);
            return;
        }
        requestImage(index, token, true);
    }

    private void requestImage(int index, int token, boolean displayWhenReady) {
        if (index < 0 || index >= mediaItems.size()) return;
        MediaEntry entry = mediaItems.get(index);
        if (entry.video) return;
        String key = imageKey(entry);
        Bitmap cached = bitmapCache.get(key);
        if (cached != null) {
            if (displayWhenReady) showLoadedImage(index, token, cached);
            return;
        }
        if (!loading.add(key)) return;
        int expectedFolder = folderRequest.get();

        worker.execute(() -> {
            Bitmap bitmap = decodeScaledBitmap(entry.uri);
            if (bitmap != null) bitmapCache.put(key, bitmap);
            loading.remove(key);
            if (!displayWhenReady || bitmap == null) return;

            mainHandler.post(() -> {
                if (expectedFolder != folderRequest.get()) return;
                showLoadedImage(index, token, bitmap);
            });
        });
    }

    private void showLoadedImage(int index, int token, Bitmap bitmap) {
        if (token != mediaRequest.get() || desiredIndex != index) return;
        releaseVideoPlayer();
        videoView.setVisibility(View.INVISIBLE);
        imageView.setVisibility(View.VISIBLE);
        imageView.setImageBitmap(bitmap);
        currentIndex = index;
        updateStatus();
        preloadAround(index);
        scheduleCurrentItem();
    }

    private void displayVideo(int index, int token) {
        videoView.setVisibility(View.INVISIBLE);
        imageView.setVisibility(View.VISIBLE);
        videoFinished = false;
        requestVideoPoster(index, token, true);
        prepareVideo(index, token);
        preloadAround(index);
    }

    private void requestVideoPoster(int index, int token, boolean displayWhenReady) {
        if (index < 0 || index >= mediaItems.size()) return;
        MediaEntry entry = mediaItems.get(index);
        if (!entry.video) return;
        String key = posterKey(entry);
        Bitmap cached = bitmapCache.get(key);
        if (cached != null) {
            if (displayWhenReady && token == mediaRequest.get() && desiredIndex == index) {
                imageView.setImageBitmap(cached);
            }
            return;
        }
        if (!loading.add(key)) return;
        int expectedFolder = folderRequest.get();

        worker.execute(() -> {
            Bitmap bitmap = decodeVideoPoster(entry.uri);
            if (bitmap != null) bitmapCache.put(key, bitmap);
            loading.remove(key);
            if (!displayWhenReady || bitmap == null) return;

            mainHandler.post(() -> {
                if (expectedFolder != folderRequest.get()) return;
                if (token != mediaRequest.get() || desiredIndex != index) return;
                imageView.setImageBitmap(bitmap);
            });
        });
    }

    private void prepareVideo(int index, int token) {
        if (index < 0 || index >= mediaItems.size()) return;
        if (token != mediaRequest.get() || desiredIndex != index) return;
        if (!videoView.isAvailable()) {
            pendingVideoIndex = index;
            pendingVideoToken = token;
            return;
        }

        releaseVideoPlayer();
        MediaEntry entry = mediaItems.get(index);
        try {
            MediaPlayer player = new MediaPlayer();
            mediaPlayer = player;
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
                if (mp != mediaPlayer || token != mediaRequest.get() || desiredIndex != index) {
                    if (mp == mediaPlayer) mediaPlayer = null;
                    safeRelease(mp);
                    return;
                }
                videoPrepared = true;
                videoFinished = false;
                applyVideoSpeed();
                try {
                    mp.start();
                } catch (Exception ignored) {
                }
                updateStatus();
            });

            player.setOnInfoListener((mp, what, extra) -> {
                if (mp == mediaPlayer && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START
                        && token == mediaRequest.get() && desiredIndex == index) {
                    videoView.setVisibility(View.VISIBLE);
                    imageView.setVisibility(View.VISIBLE);
                    return true;
                }
                return false;
            });

            player.setOnCompletionListener(mp -> {
                if (mp != mediaPlayer || token != mediaRequest.get() || desiredIndex != index) return;
                videoFinished = true;
                if (playing) {
                    advanceToNext();
                }
            });

            player.setOnErrorListener((mp, what, extra) -> {
                if (mp == mediaPlayer && token == mediaRequest.get() && desiredIndex == index) {
                    videoView.setVisibility(View.INVISIBLE);
                    Toast.makeText(this, "Video se nepodařilo přehrát", Toast.LENGTH_SHORT).show();
                    if (playing) mainHandler.postDelayed(this::advanceToNext, 300);
                }
                return true;
            });

            player.prepareAsync();
        } catch (Exception e) {
            releaseVideoPlayer();
            Toast.makeText(this, "Video se nepodařilo otevřít", Toast.LENGTH_SHORT).show();
            if (playing) mainHandler.postDelayed(this::advanceToNext, 300);
        }
    }

    private void applyVideoSpeed() {
        MediaPlayer player = mediaPlayer;
        if (player == null || !videoPrepared) return;
        try {
            PlaybackParams params = new PlaybackParams();
            params.setSpeed(videoSpeed);
            params.setPitch(1f);
            player.setPlaybackParams(params);
        } catch (Exception ignored) {
        }
    }

    private void updateVideoTransform() {
        if (videoView == null || videoWidth <= 0 || videoHeight <= 0) return;
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
        pendingVideoToken = -1;
        videoPrepared = false;
        videoFinished = false;
        videoWidth = 0;
        videoHeight = 0;
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        if (player != null) safeRelease(player);
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
        int screenTarget = Math.max(metrics.widthPixels, metrics.heightPixels);
        int target = Math.min(4096, Math.max(screenTarget, screenTarget * 3));
        ContentResolver resolver = getContentResolver();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.Source source = ImageDecoder.createSource(resolver, uri);
                return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    float scale = Math.min(1f, (float) target / Math.max(width, height));
                    if (scale < 1f) {
                        decoder.setTargetSize(
                                Math.max(1, Math.round(width * scale)),
                                Math.max(1, Math.round(height * scale)));
                    }
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

            String rotationText = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            if ("90".equals(rotationText)) orientation = ExifInterface.ORIENTATION_ROTATE_90;
            else if ("180".equals(rotationText)) orientation = ExifInterface.ORIENTATION_ROTATE_180;
            else if ("270".equals(rotationText)) orientation = ExifInterface.ORIENTATION_ROTATE_270;
            frame = rotateBitmap(frame, orientation);

            int maxDimension = Math.max(frame.getWidth(), frame.getHeight());
            int target = Math.min(2560, Math.max(
                    getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels) * 2);
            if (maxDimension > target) {
                float scale = (float) target / maxDimension;
                Bitmap scaled = Bitmap.createScaledBitmap(
                        frame,
                        Math.max(1, Math.round(frame.getWidth() * scale)),
                        Math.max(1, Math.round(frame.getHeight() * scale)),
                        true);
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

        Bitmap rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
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
        int count = Math.min(8, mediaItems.size() - 1);
        int token = mediaRequest.get();
        for (int offset = 1; offset <= count; offset++) {
            preloadItem((center + offset) % mediaItems.size(), token);
        }
        if (mediaItems.size() > 1) {
            preloadItem((center - 1 + mediaItems.size()) % mediaItems.size(), token);
        }
    }

    private void preloadItem(int index, int token) {
        MediaEntry entry = mediaItems.get(index);
        if (entry.video) requestVideoPoster(index, token, false);
        else requestImage(index, token, false);
    }

    private int chooseNextIndex() {
        if (mediaItems.isEmpty()) return -1;
        if (randomOrder && mediaItems.size() > 1) {
            int next;
            do {
                next = (int) (Math.random() * mediaItems.size());
            } while (next == currentIndex);
            return next;
        }
        if (currentIndex + 1 < mediaItems.size()) return currentIndex + 1;
        return loop ? 0 : -1;
    }

    private void advanceToNext() {
        if (!playing || mediaItems.isEmpty()) return;
        int next = chooseNextIndex();
        if (next < 0) {
            stopSlideshow();
            return;
        }
        displayIndex(next);
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

    private void startSlideshow() {
        if (mediaItems.isEmpty()) {
            Toast.makeText(this, "Nejdříve vyberte složku s fotografiemi nebo videi",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        playing = true;
        playButton.setText("Zastavit");
        controls.setVisibility(View.GONE);
        resetZoom();

        MediaEntry current = currentIndex >= 0 && currentIndex < mediaItems.size()
                ? mediaItems.get(currentIndex) : null;
        if (current != null && current.video) {
            MediaPlayer player = mediaPlayer;
            if (player != null && videoPrepared) {
                try {
                    if (videoFinished) player.seekTo(0);
                    if (!player.isPlaying()) player.start();
                    videoFinished = false;
                } catch (Exception ignored) {
                    displayIndex(currentIndex);
                }
            } else {
                displayIndex(Math.max(0, currentIndex));
            }
        } else {
            scheduleCurrentItem();
        }
    }

    private void scheduleCurrentItem() {
        mainHandler.removeCallbacks(slideshowTick);
        if (!playing || currentIndex < 0 || currentIndex >= mediaItems.size()) return;
        if (!mediaItems.get(currentIndex).video) {
            mainHandler.postDelayed(slideshowTick, intervalMs);
        }
    }

    private void stopSlideshow() {
        playing = false;
        mainHandler.removeCallbacks(slideshowTick);
        if (playButton != null) playButton.setText("Spustit");
        MediaPlayer player = mediaPlayer;
        if (player != null && videoPrepared) {
            try {
                if (player.isPlaying()) player.pause();
            } catch (Exception ignored) {
            }
        }
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

        TextView videoSpeedLabel = new TextView(this);
        videoSpeedLabel.setText("Rychlost videa (0,1× až 8×):");
        videoSpeedLabel.setTextSize(16);
        videoSpeedLabel.setPadding(0, dp(10), 0, 0);
        box.addView(videoSpeedLabel);

        EditText videoSpeedInput = new EditText(this);
        videoSpeedInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        videoSpeedInput.setSingleLine(true);
        videoSpeedInput.setText(formatSpeed(videoSpeed));
        box.addView(videoSpeedInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

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
                .setTitle("Nastavení slideshow")
                .setView(box)
                .setNegativeButton("Zrušit", null)
                .setPositiveButton("Uložit", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String intervalText = intervalInput.getText().toString().trim().replace(',', '.');
                    double seconds;
                    try {
                        seconds = Double.parseDouble(intervalText);
                    } catch (Exception e) {
                        intervalInput.setError("Zadejte číslo, například 0,1 nebo 2,5");
                        return;
                    }
                    if (seconds < 0.1 || seconds > 86400) {
                        intervalInput.setError("Povolený rozsah je 0,1 až 86400 sekund");
                        return;
                    }

                    String speedText = videoSpeedInput.getText().toString().trim().replace(',', '.');
                    float newVideoSpeed;
                    try {
                        newVideoSpeed = Float.parseFloat(speedText);
                    } catch (Exception e) {
                        videoSpeedInput.setError("Zadejte číslo, například 0,1 nebo 1,5");
                        return;
                    }
                    if (newVideoSpeed < 0.1f || newVideoSpeed > 8f) {
                        videoSpeedInput.setError("Povolený rozsah je 0,1× až 8×");
                        return;
                    }

                    intervalMs = Math.max(100, Math.round(seconds * 1000.0));
                    videoSpeed = newVideoSpeed;
                    loop = loopBox.isChecked();
                    randomOrder = randomBox.isChecked();
                    fillScreen = fillBox.isChecked();
                    imageView.setScaleType(fillScreen
                            ? ImageView.ScaleType.CENTER_CROP
                            : ImageView.ScaleType.FIT_CENTER);
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
                    if (playing && currentIndex >= 0
                            && !mediaItems.get(currentIndex).video) {
                        scheduleCurrentItem();
                    }
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private String formatSeconds(long milliseconds) {
        if (milliseconds % 1000 == 0) return String.valueOf(milliseconds / 1000);
        return String.format(Locale.US, "%.1f", milliseconds / 1000.0);
    }

    private String formatSpeed(float speed) {
        if (Math.abs(speed - Math.round(speed)) < 0.001f) {
            return String.valueOf(Math.round(speed));
        }
        return String.format(Locale.US, "%.1f", speed);
    }

    private void updateStatus() {
        if (mediaItems.isEmpty() || currentIndex < 0 || currentIndex >= mediaItems.size()) {
            statusView.setText("Žádný soubor");
            return;
        }
        MediaEntry entry = mediaItems.get(currentIndex);
        String timing = entry.video
                ? "video " + formatSpeed(videoSpeed) + "×"
                : formatSeconds(intervalMs) + " s";
        statusView.setText((currentIndex + 1) + " / " + mediaItems.size()
                + "   •   " + entry.name
                + "   •   " + timing);
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
