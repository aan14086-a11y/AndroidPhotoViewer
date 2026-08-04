from pathlib import Path

viewer = Path('app/src/main/java/cz/fotobezprechodu/ViewerActivity.java')
gradle = Path('app/build.gradle')

v = viewer.read_text(encoding='utf-8')

def replace_once(old: str, new: str, label: str) -> None:
    global v
    if old not in v:
        raise SystemExit(f'{label} was not found')
    v = v.replace(old, new, 1)

if 'import android.widget.SeekBar;' not in v:
    replace_once(
        'import android.widget.LinearLayout;\n',
        'import android.widget.LinearLayout;\nimport android.widget.SeekBar;\n',
        'SeekBar import point',
    )

replace_once(
    '''    private LinearLayout controls;\n    private TextView status, gestureInfo;\n''',
    '''    private LinearLayout controls, videoTimeline;\n    private TextView status, gestureInfo, videoTime;\n    private SeekBar videoSeek;\n''',
    'video timeline fields',
)

replace_once(
    '''    private boolean brightnessGesture, volumeGesture, tapNextGesture, moved, scaling;\n\n    private final Runnable nextTick = () -> { if (slideshow) advance(); };\n''',
    '''    private boolean brightnessGesture, volumeGesture, tapNextGesture, moved, scaling, seekingVideo;\n\n    private final Runnable nextTick = () -> { if (slideshow) advance(); };\n    private final Runnable videoProgressTick = new Runnable() {\n        @Override public void run() {\n            updateVideoTimeline();\n            if (player != null && prepared && currentVideo()) {\n                main.postDelayed(this, 250);\n            }\n        }\n    };\n''',
    'video progress state',
)

replace_once(
    '''        controls.addView(status, new LinearLayout.LayoutParams(-1, -2));\n\n        LinearLayout row1 = row();\n''',
    '''        controls.addView(status, new LinearLayout.LayoutParams(-1, -2));\n\n        videoTimeline = row();\n        videoTimeline.setVisibility(View.GONE);\n        videoSeek = new SeekBar(this);\n        videoSeek.setMax(1);\n        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(0, dp(38), 1f);\n        seekParams.setMargins(dp(4), 0, dp(4), 0);\n        videoTimeline.addView(videoSeek, seekParams);\n        videoTime = new TextView(this);\n        videoTime.setTextColor(0xffffffff);\n        videoTime.setTextSize(12);\n        videoTime.setGravity(Gravity.CENTER);\n        videoTime.setText("0:00 / 0:00");\n        videoTimeline.addView(videoTime, new LinearLayout.LayoutParams(dp(112), dp(38)));\n        controls.addView(videoTimeline);\n\n        LinearLayout row1 = row();\n''',
    'video timeline UI point',
)

replace_once(
    '''        lockButton.setOnClickListener(v -> { zoomLocked = !zoomLocked; updateButtons(); updateStatus(); });\n\n        scaler = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {\n''',
    '''        lockButton.setOnClickListener(v -> { zoomLocked = !zoomLocked; updateButtons(); updateStatus(); });\n        videoSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {\n            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {\n                if (fromUser && videoTime != null) {\n                    videoTime.setText(formatMediaTime(progress) + " / " + formatMediaTime(seekBar.getMax()));\n                }\n            }\n            @Override public void onStartTrackingTouch(SeekBar seekBar) {\n                seekingVideo = true;\n            }\n            @Override public void onStopTrackingTouch(SeekBar seekBar) {\n                try {\n                    if (player != null && prepared) {\n                        player.seekTo(seekBar.getProgress());\n                        finished = false;\n                    }\n                } catch (Exception ignored) {\n                }\n                seekingVideo = false;\n                updateVideoTimeline();\n                startVideoProgress();\n            }\n        });\n\n        scaler = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {\n''',
    'video seek listener point',
)

replace_once(
    '''            @Override public boolean onScaleBegin(ScaleGestureDetector d) { scaling = true; moved = true; stopSlideshow(); return true; }\n''',
    '''            @Override public boolean onScaleBegin(ScaleGestureDetector d) { scaling = true; moved = true; stopSlideshowForTransform(); return true; }\n''',
    'scale playback preservation',
)

replace_once(
    '''                scaling = moved = true; gestureMode = G_NONE; stopSlideshow(); return true;\n''',
    '''                scaling = moved = true; gestureMode = G_NONE; stopSlideshowForTransform(); return true;\n''',
    'pointer playback preservation',
)

replace_once(
    '''                    zoomX += x - lastX; zoomY += y - lastY; clampZoom(); applyZoom(); stopSlideshow();\n''',
    '''                    zoomX += x - lastX; zoomY += y - lastY; clampZoom(); applyZoom(); stopSlideshowForTransform();\n''',
    'pan playback preservation',
)

replace_once(
    '''                prepared = true; videoWidth = p.getVideoWidth(); videoHeight = p.getVideoHeight(); transformVideo(); speed();\n                try { p.start(); } catch (Exception ignored) {}\n                updateButtons(); updateStatus();\n''',
    '''                prepared = true; videoWidth = p.getVideoWidth(); videoHeight = p.getVideoHeight(); transformVideo(); speed();\n                configureVideoTimeline(p);\n                try { p.start(); } catch (Exception ignored) {}\n                startVideoProgress();\n                updateButtons(); updateStatus();\n''',
    'prepared timeline setup',
)

replace_once(
    '''            mp.setOnCompletionListener(p -> { if (p == player) { finished = true; updateButtons(); if (slideshow) advance(); } });\n''',
    '''            mp.setOnCompletionListener(p -> { if (p == player) { finished = true; updateVideoTimeline(); updateButtons(); if (slideshow) advance(); } });\n''',
    'completion timeline update',
)

replace_once(
    '''            mp.setOnErrorListener((p,w,e) -> { if (p == player) { video.setAlpha(0); image.setAlpha(1); Toast.makeText(this,"Video se nepodařilo přehrát",Toast.LENGTH_SHORT).show(); if (slideshow) main.postDelayed(this::advance,300); } return true; });\n''',
    '''            mp.setOnErrorListener((p,w,e) -> { if (p == player) { stopVideoProgress(); video.setAlpha(0); image.setAlpha(1); Toast.makeText(this,"Video se nepodařilo přehrát",Toast.LENGTH_SHORT).show(); if (slideshow) main.postDelayed(this::advance,300); } return true; });\n''',
    'error timeline cleanup',
)

replace_once(
    '''        try { if (player != null && prepared) { if (player.isPlaying()) player.pause(); player.seekTo(0); finished = false; video.setAlpha(0); image.setAlpha(1); } } catch (Exception ignored) {}\n        updateButtons();\n''',
    '''        try { if (player != null && prepared) { if (player.isPlaying()) player.pause(); player.seekTo(0); finished = false; video.setAlpha(0); image.setAlpha(1); } } catch (Exception ignored) {}\n        updateVideoTimeline();\n        updateButtons();\n''',
    'stop timeline reset',
)

replace_once(
    '''    private void speed() {\n''',
    '''    private void configureVideoTimeline(MediaPlayer p) {\n        if (videoSeek == null || videoTime == null) return;\n        int duration = 0;\n        try { duration = Math.max(0, p.getDuration()); } catch (Exception ignored) {}\n        videoSeek.setMax(Math.max(1, duration));\n        videoSeek.setProgress(0);\n        videoTime.setText("0:00 / " + formatMediaTime(duration));\n    }\n\n    private void updateVideoTimeline() {\n        if (videoSeek == null || videoTime == null) return;\n        int duration = 0, position = 0;\n        try {\n            if (player != null && prepared) {\n                duration = Math.max(0, player.getDuration());\n                position = Math.max(0, player.getCurrentPosition());\n            }\n        } catch (Exception ignored) {\n        }\n        if (!seekingVideo) {\n            videoSeek.setMax(Math.max(1, duration));\n            videoSeek.setProgress(Math.min(position, videoSeek.getMax()));\n        }\n        videoTime.setText(formatMediaTime(seekingVideo ? videoSeek.getProgress() : position)\n                + " / " + formatMediaTime(duration));\n    }\n\n    private void startVideoProgress() {\n        main.removeCallbacks(videoProgressTick);\n        main.post(videoProgressTick);\n    }\n\n    private void stopVideoProgress() {\n        main.removeCallbacks(videoProgressTick);\n    }\n\n    private String formatMediaTime(int milliseconds) {\n        long total = Math.max(0, milliseconds) / 1000L;\n        long hours = total / 3600L;\n        long minutes = (total % 3600L) / 60L;\n        long seconds = total % 60L;\n        return hours > 0\n                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)\n                : String.format(Locale.US, "%d:%02d", minutes, seconds);\n    }\n\n    private void speed() {\n''',
    'video timeline helper insertion',
)

replace_once(
    '''    private void releasePlayer() {\n        pendingVideo = pendingGeneration = -1; prepared = finished = false; videoWidth = videoHeight = 0;\n''',
    '''    private void releasePlayer() {\n        stopVideoProgress();\n        seekingVideo = false;\n        pendingVideo = pendingGeneration = -1; prepared = finished = false; videoWidth = videoHeight = 0;\n''',
    'release timeline cleanup',
)

replace_once(
    '''        if (video != null) video.setAlpha(0); if (image != null) image.setAlpha(1); updateButtons();\n''',
    '''        if (videoSeek != null) videoSeek.setProgress(0);\n        if (videoTime != null) videoTime.setText("0:00 / 0:00");\n        if (video != null) video.setAlpha(0); if (image != null) image.setAlpha(1); updateButtons();\n''',
    'released timeline display reset',
)

replace_once(
    '''    private void stopSlideshow() { slideshow=false; main.removeCallbacks(nextTick); try { if(player!=null&&prepared&&player.isPlaying()) player.pause(); }catch(Exception ignored){} updateButtons(); }\n''',
    '''    private void stopSlideshowForTransform() {\n        if (currentVideo()) {\n            slideshow = false;\n            main.removeCallbacks(nextTick);\n            updateButtons();\n        } else {\n            stopSlideshow();\n        }\n    }\n    private void stopSlideshow() { slideshow=false; main.removeCallbacks(nextTick); try { if(player!=null&&prepared&&player.isPlaying()) player.pause(); }catch(Exception ignored){} updateButtons(); }\n''',
    'transform-safe slideshow stop',
)

replace_once(
    '''        boolean v=currentVideo(); if(videoButton!=null){videoButton.setEnabled(v);stopButton.setEnabled(v);boolean playing=false;try{playing=v&&player!=null&&prepared&&player.isPlaying();}catch(Exception ignored){}videoButton.setText(playing?"⏸ Pauza":"▶ Video");}\n        if(lockButton!=null) lockButton.setText(zoomLocked?"🔒 Pozice":"🔓 Pozice");\n''',
    '''        boolean v=currentVideo(); if(videoButton!=null){videoButton.setEnabled(v);stopButton.setEnabled(v);boolean playing=false;try{playing=v&&player!=null&&prepared&&player.isPlaying();}catch(Exception ignored){}videoButton.setText(playing?"⏸ Pauza":"▶ Video");}\n        if(videoTimeline!=null) videoTimeline.setVisibility(v ? View.VISIBLE : View.GONE);\n        if(v) updateVideoTimeline();\n        if(lockButton!=null) lockButton.setText(zoomLocked?"🔒 Pozice":"🔓 Pozice");\n''',
    'timeline visibility update',
)

viewer.write_text(v, encoding='utf-8')

g = gradle.read_text(encoding='utf-8')
old_version = "versionCode 9\n        versionName '1.3.5'"
new_version = "versionCode 10\n        versionName '1.3.6'"
if old_version not in g:
    raise SystemExit('Expected version 1.3.5 was not found')
gradle.write_text(g.replace(old_version, new_version, 1), encoding='utf-8')
