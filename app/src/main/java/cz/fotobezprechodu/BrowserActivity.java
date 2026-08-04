package cz.fotobezprechodu;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class BrowserActivity extends Activity {
    static final String EXTRA_TREE_URI = "tree_uri";
    static final String EXTRA_FOLDER_ID = "folder_id";
    static final String EXTRA_SELECTED_ID = "selected_id";
    static final String EXTRA_SELECTED_URI = "selected_uri";
    static final String EXTRA_SELECTED_NAME = "selected_name";
    static final String EXTRA_SELECTED_MIME = "selected_mime";

    private static final int REQUEST_TREE = 2001;
    private static final String PREFS = "foto_bez_prechodu";
    private static final String PREF_TREE_URI = "tree_uri";
    private static final String PREF_FOLDER_ID = "browser_folder_id";
    private static final String PREF_FOLDER_NAME = "browser_folder_name";

    private final ExecutorService worker = Executors.newFixedThreadPool(4);
    private final AtomicInteger generation = new AtomicInteger();
    private final ArrayDeque<FolderState> backStack = new ArrayDeque<>();

    private SharedPreferences preferences;
    private Uri treeUri;
    private String rootId;
    private String currentFolderId;
    private String currentFolderName;
    private TextView titleView;
    private TextView emptyView;
    private GridLayout grid;
    private Button backButton;
    private LruCache<String, Bitmap> thumbnails;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        thumbnails = new LruCache<String, Bitmap>(24 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        buildUi();

        String savedTree = preferences.getString(PREF_TREE_URI, null);
        if (savedTree == null) {
            showNoStorage();
            return;
        }
        treeUri = Uri.parse(savedTree);
        try {
            rootId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            showNoStorage();
            return;
        }
        String savedFolder = preferences.getString(PREF_FOLDER_ID, rootId);
        String savedName = preferences.getString(PREF_FOLDER_NAME, "Vybraná složka");
        showFolder(savedFolder, savedName, false);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111111);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(6), dp(6), dp(6), dp(6));
        toolbar.setBackgroundColor(0xFF202020);

        backButton = makeButton("← Zpět");
        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);
        titleView.setSingleLine(true);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setPadding(dp(10), 0, dp(10), 0);
        Button storageButton = makeButton("Úložiště");

        toolbar.addView(backButton, new LinearLayout.LayoutParams(dp(90), dp(46)));
        toolbar.addView(titleView, new LinearLayout.LayoutParams(0, dp(46), 1f));
        toolbar.addView(storageButton, new LinearLayout.LayoutParams(dp(100), dp(46)));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout content = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        grid = new GridLayout(this);
        grid.setPadding(dp(4), dp(4), dp(4), dp(20));
        scroll.addView(grid, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        emptyView = new TextView(this);
        emptyView.setTextColor(0xFFE0E0E0);
        emptyView.setTextSize(18);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(28), dp(28), dp(28), dp(28));
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        backButton.setOnClickListener(v -> goBackFolder());
        storageButton.setOnClickListener(v -> openTreePicker());
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setPadding(dp(3), 0, dp(3), 0);
        return button;
    }

    private void showNoStorage() {
        treeUri = null;
        rootId = null;
        currentFolderId = null;
        currentFolderName = null;
        grid.removeAllViews();
        titleView.setText("Foto bez přechodů");
        backButton.setEnabled(false);
        emptyView.setVisibility(View.VISIBLE);
        emptyView.setText("Nejprve klepněte na Úložiště a povolte přístup k hlavní složce s fotografiemi a videi.\n\nPotom už budete složky procházet přímo v této aplikaci.");
    }

    private void openTreePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        treeUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        try {
            rootId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            Toast.makeText(this, "Tuto složku se nepodařilo otevřít", Toast.LENGTH_SHORT).show();
            showNoStorage();
            return;
        }
        String rootName = MediaUtils.resolveDocumentName(this, treeUri, rootId, "Vybraná složka");
        preferences.edit()
                .putString(PREF_TREE_URI, treeUri.toString())
                .putString(PREF_FOLDER_ID, rootId)
                .putString(PREF_FOLDER_NAME, rootName)
                .apply();
        backStack.clear();
        thumbnails.evictAll();
        showFolder(rootId, rootName, false);
    }

    private void showFolder(String folderId, String folderName, boolean pushCurrent) {
        if (treeUri == null || folderId == null) {
            showNoStorage();
            return;
        }
        if (pushCurrent && currentFolderId != null) {
            backStack.push(new FolderState(currentFolderId, currentFolderName));
        }
        currentFolderId = folderId;
        currentFolderName = folderName == null || folderName.trim().isEmpty() ? "Složka" : folderName;
        titleView.setText(currentFolderName);
        backButton.setEnabled(!backStack.isEmpty() || !folderId.equals(rootId));
        emptyView.setVisibility(View.VISIBLE);
        emptyView.setText("Načítám složku…");
        grid.removeAllViews();
        int request = generation.incrementAndGet();

        worker.execute(() -> {
            ArrayList<MediaUtils.Entry> items = MediaUtils.queryDirectChildren(this, treeUri, folderId);
            runOnUiThread(() -> {
                if (request != generation.get()) return;
                preferences.edit()
                        .putString(PREF_FOLDER_ID, folderId)
                        .putString(PREF_FOLDER_NAME, currentFolderName)
                        .apply();
                renderItems(items, request);
            });
        });
    }

    private void renderItems(ArrayList<MediaUtils.Entry> items, int request) {
        grid.removeAllViews();
        int width = getResources().getDisplayMetrics().widthPixels;
        int columns = Math.max(2, Math.min(6, width / dp(128)));
        grid.setColumnCount(columns);
        if (items.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText("Tato složka je prázdná nebo neobsahuje podporované fotografie a videa.");
            return;
        }
        emptyView.setVisibility(View.GONE);
        int cardWidth = Math.max(dp(105), (width - dp(8)) / columns);
        for (MediaUtils.Entry item : items) {
            View card = createCard(item, cardWidth, request);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = cardWidth;
            params.height = dp(156);
            params.setMargins(dp(2), dp(2), dp(2), dp(4));
            grid.addView(card, params);
        }
    }

    private View createCard(MediaUtils.Entry item, int cardWidth, int request) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(3), dp(3), dp(3), dp(3));
        card.setBackground(new ColorDrawable(0xFF242424));

        FrameLayout preview = new FrameLayout(this);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(0xFF353535);
        preview.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView marker = new TextView(this);
        marker.setText(item.directory ? "📁" : (item.video ? "▶" : ""));
        marker.setTextSize(item.directory ? 34 : 22);
        marker.setTextColor(Color.WHITE);
        marker.setGravity(item.directory ? Gravity.CENTER : Gravity.BOTTOM | Gravity.RIGHT);
        marker.setPadding(dp(6), dp(6), dp(7), dp(5));
        preview.addView(marker, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        card.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView label = new TextView(this);
        label.setText(item.name);
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setPadding(dp(2), dp(3), dp(2), 0);
        card.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(43)));

        card.setOnClickListener(v -> {
            if (item.directory) showFolder(item.documentId, item.name, true);
            else openViewer(item);
        });
        loadThumbnail(item, image, request, cardWidth);
        return card;
    }

    private void loadThumbnail(MediaUtils.Entry item, ImageView target, int request, int size) {
        String key = (item.directory ? "folder:" : "media:") + item.documentId;
        Bitmap cached = thumbnails.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        worker.execute(() -> {
            MediaUtils.Entry source = item;
            if (item.directory) source = MediaUtils.firstMediaChild(this, treeUri, item.documentId);
            if (source == null) return;
            Bitmap bitmap = MediaUtils.decodeThumbnail(this, source.uri, source.video, Math.max(dp(240), size * 2));
            if (bitmap == null) return;
            thumbnails.put(key, bitmap);
            runOnUiThread(() -> {
                if (request == generation.get() && target.getWindowToken() != null) {
                    target.setImageBitmap(bitmap);
                }
            });
        });
    }

    private void openViewer(MediaUtils.Entry selected) {
        Intent intent = new Intent(this, ViewerActivity.class);
        intent.putExtra(EXTRA_TREE_URI, treeUri.toString());
        intent.putExtra(EXTRA_FOLDER_ID, currentFolderId);
        intent.putExtra(EXTRA_SELECTED_ID, selected.documentId);
        intent.putExtra(EXTRA_SELECTED_URI, selected.uri.toString());
        intent.putExtra(EXTRA_SELECTED_NAME, selected.name);
        intent.putExtra(EXTRA_SELECTED_MIME, selected.mime);

        ClipData grants = ClipData.newRawUri("folder", treeUri);
        grants.addItem(new ClipData.Item(selected.uri));
        intent.setClipData(grants);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivity(intent);
    }

    private void goBackFolder() {
        if (!backStack.isEmpty()) {
            FolderState previous = backStack.pop();
            showFolder(previous.id, previous.name, false);
        } else if (currentFolderId != null && !currentFolderId.equals(rootId)) {
            showFolder(rootId, MediaUtils.resolveDocumentName(this, treeUri, rootId, "Vybraná složka"), false);
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (!backStack.isEmpty() || (currentFolderId != null && !currentFolderId.equals(rootId))) {
            goBackFolder();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        generation.incrementAndGet();
        worker.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class FolderState {
        final String id;
        final String name;

        FolderState(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
