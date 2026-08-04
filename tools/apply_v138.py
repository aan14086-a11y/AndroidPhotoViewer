from pathlib import Path

viewer = Path('app/src/main/java/cz/fotobezprechodu/ViewerActivity.java')
gradle = Path('app/build.gradle')

v = viewer.read_text(encoding='utf-8')

def replace_once(old: str, new: str, label: str) -> None:
    global v
    if old not in v:
        raise SystemExit(f'{label} was not found')
    v = v.replace(old, new, 1)

replace_once(
    '''    private Button slideshowButton, videoButton, stopButton, lockButton;\n''',
    '''    private Button slideshowButton, videoButton, stopButton, repeatButton, lockButton;\n''',
    'repeat button field',
)

replace_once(
    '''    private boolean brightnessGesture, volumeGesture, tapNextGesture, moved, scaling, seekingVideo;\n''',
    '''    private boolean brightnessGesture, volumeGesture, tapNextGesture, moved, scaling, seekingVideo, repeatVideo;\n''',
    'repeat video state',
)

replace_once(
    '''        LinearLayout row2 = row();\n        videoButton = button("▶ Video"); stopButton = button("■ Stop"); lockButton = button("🔓 Pozice");\n        row2.addView(videoButton, weight(1)); row2.addView(stopButton, weight(1)); row2.addView(lockButton, weight(1.15f));\n        controls.addView(row2);\n''',
    '''        LinearLayout row2 = row();\n        videoButton = button("▶ Video"); stopButton = button("■ Stop");\n        repeatButton = button("🔁 Opakovat"); lockButton = button("🔓 Pozice");\n        row2.addView(videoButton, weight(1)); row2.addView(stopButton, weight(.9f));\n        row2.addView(repeatButton, weight(1.2f)); row2.addView(lockButton, weight(1.05f));\n        controls.addView(row2);\n''',
    'repeat button UI',
)

replace_once(
    '''        videoButton.setOnClickListener(v -> toggleVideo());\n        stopButton.setOnClickListener(v -> stopVideo());\n        lockButton.setOnClickListener(v -> { zoomLocked = !zoomLocked; updateButtons(); updateStatus(); });\n''',
    '''        videoButton.setOnClickListener(v -> toggleVideo());\n        stopButton.setOnClickListener(v -> stopVideo());\n        repeatButton.setOnClickListener(v -> toggleVideoRepeat());\n        lockButton.setOnClickListener(v -> { zoomLocked = !zoomLocked; updateButtons(); updateStatus(); });\n''',
    'repeat button listener',
)

replace_once(
    '''    private void installPhotoLayer(Bitmap bitmap) {\n        // Do not merely hide TextureView. Remove it from the hierarchy so no retained\n        // video surface can cover the photograph on device-specific graphics drivers.\n        if (video != null && video.getParent() == stage) stage.removeView(video);\n        if (image != null && image.getParent() == stage) stage.removeView(image);\n        image = createPhotoView();\n        image.setImageBitmap(bitmap);\n        stage.addView(image, match());\n        image.requestLayout();\n        image.invalidate();\n        stage.requestLayout();\n        stage.invalidate();\n    }\n''',
    '''    private void installPhotoLayer(Bitmap bitmap) {\n        // Keep the currently displayed photograph in place while the new bitmap is\n        // being installed. Removing the old ImageView first exposed the black stage\n        // for one or more frames on the first uncached visit. Cached revisits looked\n        // instant because decoding had already finished.\n        if (video != null && video.getParent() == stage) stage.removeView(video);\n        final ImageView previousImage = image;\n        final ImageView nextImage = createPhotoView();\n        nextImage.setImageBitmap(bitmap);\n        stage.addView(nextImage, match());\n        nextImage.bringToFront();\n        image = nextImage;\n\n        nextImage.getViewTreeObserver().addOnPreDrawListener(\n                new android.view.ViewTreeObserver.OnPreDrawListener() {\n                    @Override public boolean onPreDraw() {\n                        if (nextImage.getViewTreeObserver().isAlive()) {\n                            nextImage.getViewTreeObserver().removeOnPreDrawListener(this);\n                        }\n                        if (previousImage != null && previousImage != nextImage\n                                && previousImage.getParent() == stage) {\n                            stage.removeView(previousImage);\n                        }\n                        return true;\n                    }\n                });\n        nextImage.requestLayout();\n        nextImage.invalidate();\n        stage.requestLayout();\n        stage.invalidate();\n    }\n''',
    'double-buffered photo layer',
)

replace_once(
    '''            // Videos repeat continuously. setLooping is the normal path; the\n            // completion listener is retained as a fallback for device-specific players.\n            mp.setLooping(true);\n            mp.setOnCompletionListener(p -> {\n                if (p == player) {\n                    finished = false;\n                    try {\n                        p.seekTo(0);\n                        p.start();\n                    } catch (Exception ignored) {\n                    }\n                    updateVideoTimeline();\n                    updateButtons();\n                    startVideoProgress();\n                }\n            });\n''',
    '''            mp.setLooping(repeatVideo);\n            mp.setOnCompletionListener(p -> {\n                if (p != player) return;\n                if (repeatVideo) {\n                    finished = false;\n                    try {\n                        p.seekTo(0);\n                        p.start();\n                    } catch (Exception ignored) {\n                    }\n                    startVideoProgress();\n                } else {\n                    finished = true;\n                    if (slideshow) advance();\n                }\n                updateVideoTimeline();\n                updateButtons();\n            });\n''',
    'optional video loop',
)

replace_once(
    '''    private void toggleVideo() {\n''',
    '''    private void toggleVideoRepeat() {\n        if (!currentVideo()) return;\n        repeatVideo = !repeatVideo;\n        try {\n            if (player != null) {\n                player.setLooping(repeatVideo);\n                if (repeatVideo && prepared && finished) {\n                    player.seekTo(0);\n                    player.start();\n                    finished = false;\n                    startVideoProgress();\n                }\n            }\n        } catch (Exception ignored) {\n        }\n        updateButtons();\n    }\n\n    private void toggleVideo() {\n''',
    'repeat toggle method',
)

replace_once(
    '''    private void preload(int center) {\n        int n = Math.min(6, items.size()-1);\n        for (int o=1;o<=n;o++) { int i=(center+o)%items.size(); if (items.get(i).video) loadPoster(i); else loadImage(i); }\n    }\n''',
    '''    private void preload(int center) {\n        int n = Math.min(3, items.size() - 1);\n        for (int offset = 1; offset <= n; offset++) {\n            int forward = (center + offset) % items.size();\n            int backward = (center - offset + items.size()) % items.size();\n            if (items.get(forward).video) loadPoster(forward); else loadImage(forward);\n            if (backward != forward) {\n                if (items.get(backward).video) loadPoster(backward); else loadImage(backward);\n            }\n        }\n    }\n''',
    'bidirectional preload',
)

replace_once(
    '''        if(videoTimeline!=null) videoTimeline.setVisibility(v ? View.VISIBLE : View.GONE);\n        if(v) updateVideoTimeline();\n        if(lockButton!=null) lockButton.setText(zoomLocked?"🔒 Pozice":"🔓 Pozice");\n''',
    '''        if(videoTimeline!=null) videoTimeline.setVisibility(v ? View.VISIBLE : View.GONE);\n        if(v) updateVideoTimeline();\n        if(repeatButton!=null) {\n            repeatButton.setEnabled(v);\n            repeatButton.setText(repeatVideo ? "🔁 Opakuje" : "🔁 Opakovat");\n        }\n        if(lockButton!=null) lockButton.setText(zoomLocked?"🔒 Pozice":"🔓 Pozice");\n''',
    'repeat button state',
)

viewer.write_text(v, encoding='utf-8')

g = gradle.read_text(encoding='utf-8')
old_version = "versionCode 11\n        versionName '1.3.7'"
new_version = "versionCode 12\n        versionName '1.3.8'"
if old_version not in g:
    raise SystemExit('Expected version 1.3.7 was not found')
gradle.write_text(g.replace(old_version, new_version, 1), encoding='utf-8')
