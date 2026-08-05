from pathlib import Path

viewer = Path('app/src/main/java/cz/fotobezprechodu/ViewerActivity.java')
browser = Path('app/src/main/java/cz/fotobezprechodu/BrowserActivity.java')
gradle = Path('app/build.gradle')

v = viewer.read_text(encoding='utf-8')
b = browser.read_text(encoding='utf-8')

def replace_viewer(old: str, new: str, label: str) -> None:
    global v
    if old not in v:
        raise SystemExit(f'{label} was not found in ViewerActivity')
    v = v.replace(old, new, 1)

def replace_browser(old: str, new: str, label: str) -> None:
    global b
    if old not in b:
        raise SystemExit(f'{label} was not found in BrowserActivity')
    b = b.replace(old, new, 1)

replace_viewer(
    '''                if (gestureMode == G_NONE && Math.abs(dy) > dp(18) && Math.abs(dy) > Math.abs(dx) * 1.15f) {\n                    if (downX < v.getWidth() / 2f && brightnessGesture) gestureMode = G_BRIGHT;\n                    else if (downX >= v.getWidth() / 2f && volumeGesture && currentVideo()) gestureMode = G_VOLUME;\n                    else if (zoom > 1.001f) gestureMode = G_PAN;\n''',
    '''                if (gestureMode == G_NONE && Math.abs(dy) > dp(18) && Math.abs(dy) > Math.abs(dx) * 1.15f) {\n                    // Brightness and volume gestures must start within one finger-width\n                    // of the corresponding edge instead of occupying half the screen.\n                    float edgeGestureWidth = Math.min(dp(64), v.getWidth() * .24f);\n                    if (downX <= edgeGestureWidth && brightnessGesture) gestureMode = G_BRIGHT;\n                    else if (downX >= v.getWidth() - edgeGestureWidth\n                            && volumeGesture && currentVideo()) gestureMode = G_VOLUME;\n                    else if (zoom > 1.001f) gestureMode = G_PAN;\n''',
    'narrow edge gesture zones',
)

replace_browser(
    '''import android.graphics.Bitmap;\nimport android.graphics.Color;\nimport android.graphics.drawable.ColorDrawable;\n''',
    '''import android.graphics.Bitmap;\nimport android.graphics.Canvas;\nimport android.graphics.Color;\nimport android.graphics.Paint;\nimport android.graphics.drawable.ColorDrawable;\n''',
    'browser drawing imports',
)

replace_browser(
    '''import android.view.Gravity;\nimport android.view.View;\nimport android.view.Window;\n''',
    '''import android.view.Gravity;\nimport android.view.MotionEvent;\nimport android.view.View;\nimport android.view.Window;\n''',
    'browser touch import',
)

replace_browser(
    '''    private TextView emptyView;\n    private GridLayout grid;\n    private Button backButton;\n''',
    '''    private TextView emptyView;\n    private GridLayout grid;\n    private ScrollView scrollView;\n    private VerticalDirectoryScrollBar directoryScrollBar;\n    private Button backButton;\n''',
    'directory scrollbar fields',
)

replace_browser(
    '''        FrameLayout content = new FrameLayout(this);\n        ScrollView scroll = new ScrollView(this);\n        scroll.setFillViewport(true);\n        grid = new GridLayout(this);\n        grid.setPadding(dp(4), dp(4), dp(4), dp(20));\n        scroll.addView(grid, new ScrollView.LayoutParams(\n                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));\n        content.addView(scroll, new FrameLayout.LayoutParams(\n                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));\n''',
    '''        FrameLayout content = new FrameLayout(this);\n        scrollView = new ScrollView(this);\n        scrollView.setFillViewport(true);\n        scrollView.setVerticalScrollBarEnabled(false);\n        grid = new GridLayout(this);\n        grid.setPadding(dp(4), dp(4), dp(4), dp(20));\n        scrollView.addView(grid, new ScrollView.LayoutParams(\n                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));\n        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(\n                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);\n        scrollParams.rightMargin = dp(34);\n        content.addView(scrollView, scrollParams);\n\n        directoryScrollBar = new VerticalDirectoryScrollBar(this);\n        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(\n                dp(34), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END);\n        barParams.topMargin = dp(5);\n        barParams.bottomMargin = dp(5);\n        content.addView(directoryScrollBar, barParams);\n        directoryScrollBar.setListener(progress -> scrollDirectoryTo(progress));\n        scrollView.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY)\n                -> updateDirectoryScrollBar());\n        grid.addOnLayoutChangeListener((view, left, top, right, bottom,\n                oldLeft, oldTop, oldRight, oldBottom) -> updateDirectoryScrollBarLater());\n''',
    'visible draggable directory scrollbar UI',
)

replace_browser(
    '''        grid.removeAllViews();\n        titleView.setText("Foto bez přechodů");\n''',
    '''        grid.removeAllViews();\n        if (scrollView != null) scrollView.scrollTo(0, 0);\n        updateDirectoryScrollBarLater();\n        titleView.setText("Foto bez přechodů");\n''',
    'scrollbar reset without storage',
)

replace_browser(
    '''        emptyView.setText("Načítám složku…");\n        grid.removeAllViews();\n        int request = generation.incrementAndGet();\n''',
    '''        emptyView.setText("Načítám složku…");\n        grid.removeAllViews();\n        if (scrollView != null) scrollView.scrollTo(0, 0);\n        updateDirectoryScrollBarLater();\n        int request = generation.incrementAndGet();\n''',
    'scrollbar reset on folder change',
)

replace_browser(
    '''        if (items.isEmpty()) {\n            emptyView.setVisibility(View.VISIBLE);\n            emptyView.setText("Tato složka je prázdná nebo neobsahuje podporované fotografie a videa.");\n            return;\n        }\n''',
    '''        if (items.isEmpty()) {\n            emptyView.setVisibility(View.VISIBLE);\n            emptyView.setText("Tato složka je prázdná nebo neobsahuje podporované fotografie a videa.");\n            updateDirectoryScrollBarLater();\n            return;\n        }\n''',
    'empty folder scrollbar update',
)

replace_browser(
    '''            grid.addView(card, params);\n        }\n    }\n\n    private View createCard''',
    '''            grid.addView(card, params);\n        }\n        updateDirectoryScrollBarLater();\n    }\n\n    private View createCard''',
    'rendered folder scrollbar update',
)

replace_browser(
    '''    @Override\n    protected void onDestroy() {\n        generation.incrementAndGet();\n        worker.shutdownNow();\n        super.onDestroy();\n    }\n\n    private int dp(int value) {\n''',
    '''    @Override\n    protected void onDestroy() {\n        generation.incrementAndGet();\n        worker.shutdownNow();\n        super.onDestroy();\n    }\n\n    private void scrollDirectoryTo(float progress) {\n        if (scrollView == null || scrollView.getChildCount() == 0) return;\n        View content = scrollView.getChildAt(0);\n        int maximum = Math.max(0, content.getHeight() - scrollView.getHeight());\n        scrollView.scrollTo(0, Math.round(maximum * Math.max(0f, Math.min(1f, progress))));\n    }\n\n    private void updateDirectoryScrollBarLater() {\n        if (scrollView != null) scrollView.post(this::updateDirectoryScrollBar);\n    }\n\n    private void updateDirectoryScrollBar() {\n        if (scrollView == null || directoryScrollBar == null || scrollView.getChildCount() == 0) return;\n        View content = scrollView.getChildAt(0);\n        int viewport = Math.max(1, scrollView.getHeight());\n        int contentHeight = Math.max(1, content.getHeight());\n        int maximum = Math.max(0, contentHeight - viewport);\n        float visibleFraction = Math.min(1f, viewport / (float) contentHeight);\n        float progress = maximum == 0 ? 0f : scrollView.getScrollY() / (float) maximum;\n        directoryScrollBar.setState(progress, visibleFraction);\n        directoryScrollBar.setEnabled(maximum > 0);\n        directoryScrollBar.setAlpha(maximum > 0 ? 1f : .38f);\n    }\n\n    private static final class VerticalDirectoryScrollBar extends View {\n        interface Listener { void onProgressChanged(float progress); }\n\n        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);\n        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);\n        private final float density;\n        private float progress;\n        private float visibleFraction = 1f;\n        private Listener listener;\n\n        VerticalDirectoryScrollBar(BrowserActivity context) {\n            super(context);\n            density = context.getResources().getDisplayMetrics().density;\n            setBackgroundColor(0x18000000);\n            setClickable(true);\n        }\n\n        void setListener(Listener listener) {\n            this.listener = listener;\n        }\n\n        void setState(float progress, float visibleFraction) {\n            this.progress = clamp01(progress);\n            this.visibleFraction = Math.max(.08f, Math.min(1f, visibleFraction));\n            invalidate();\n        }\n\n        @Override\n        protected void onDraw(Canvas canvas) {\n            super.onDraw(canvas);\n            float center = getWidth() / 2f;\n            float padding = 7f * density;\n            float trackTop = padding;\n            float trackBottom = Math.max(trackTop, getHeight() - padding);\n            float trackHeight = trackBottom - trackTop;\n\n            trackPaint.setColor(0x99606060);\n            float trackHalfWidth = 2.5f * density;\n            canvas.drawRoundRect(center - trackHalfWidth, trackTop, center + trackHalfWidth,\n                    trackBottom, trackHalfWidth, trackHalfWidth, trackPaint);\n\n            float minimumThumb = 54f * density;\n            float thumbHeight = Math.min(trackHeight, Math.max(minimumThumb, trackHeight * visibleFraction));\n            float thumbTop = trackTop + Math.max(0f, trackHeight - thumbHeight) * progress;\n            float thumbHalfWidth = 8f * density;\n            thumbPaint.setColor(isEnabled() ? 0xFFE0E0E0 : 0xFF808080);\n            canvas.drawRoundRect(center - thumbHalfWidth, thumbTop, center + thumbHalfWidth,\n                    thumbTop + thumbHeight, thumbHalfWidth, thumbHalfWidth, thumbPaint);\n        }\n\n        @Override\n        public boolean onTouchEvent(MotionEvent event) {\n            if (!isEnabled()) return true;\n            switch (event.getActionMasked()) {\n                case MotionEvent.ACTION_DOWN:\n                    getParent().requestDisallowInterceptTouchEvent(true);\n                    updateFromTouch(event.getY());\n                    return true;\n                case MotionEvent.ACTION_MOVE:\n                    updateFromTouch(event.getY());\n                    return true;\n                case MotionEvent.ACTION_UP:\n                case MotionEvent.ACTION_CANCEL:\n                    updateFromTouch(event.getY());\n                    getParent().requestDisallowInterceptTouchEvent(false);\n                    performClick();\n                    return true;\n                default:\n                    return true;\n            }\n        }\n\n        @Override\n        public boolean performClick() {\n            super.performClick();\n            return true;\n        }\n\n        private void updateFromTouch(float y) {\n            float padding = 7f * density;\n            float trackHeight = Math.max(1f, getHeight() - padding * 2f);\n            float thumbHeight = Math.min(trackHeight, Math.max(54f * density, trackHeight * visibleFraction));\n            float travel = Math.max(1f, trackHeight - thumbHeight);\n            progress = clamp01((y - padding - thumbHeight / 2f) / travel);\n            invalidate();\n            if (listener != null) listener.onProgressChanged(progress);\n        }\n\n        private static float clamp01(float value) {\n            return Math.max(0f, Math.min(1f, value));\n        }\n    }\n\n    private int dp(int value) {\n''',
    'directory scrollbar behavior',
)

viewer.write_text(v, encoding='utf-8')
browser.write_text(b, encoding='utf-8')

g = gradle.read_text(encoding='utf-8')
old_version = "versionCode 12\n        versionName '1.3.8'"
new_version = "versionCode 13\n        versionName '1.3.9'"
if old_version not in g:
    raise SystemExit('Expected version 1.3.8 was not found')
gradle.write_text(g.replace(old_version, new_version, 1), encoding='utf-8')
