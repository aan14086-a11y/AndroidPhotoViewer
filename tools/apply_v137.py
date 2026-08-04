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
    '''            @Override public boolean onSingleTapConfirmed(MotionEvent e) {\n                if (scaling || moved || gestureMode != G_NONE) return false;\n                float position = e.getX() / Math.max(1f, stage.getWidth());\n                if (tapNextGesture && (position < .4f || position > .6f)) {\n                    stopSlideshow();\n                    next();\n                } else {\n                    controls.setVisibility(controls.getVisibility() == View.VISIBLE\n                            ? View.GONE : View.VISIBLE);\n                }\n                return true;\n            }\n''',
    '''            @Override public boolean onSingleTapConfirmed(MotionEvent e) {\n                if (scaling || moved || gestureMode != G_NONE) return false;\n                float position = e.getX() / Math.max(1f, stage.getWidth());\n                // Edge taps are handled immediately in ACTION_UP. Do not wait for\n                // GestureDetector's double-tap timeout and do not navigate twice.\n                if (tapNextGesture && (position < .35f || position > .65f)) return false;\n                controls.setVisibility(controls.getVisibility() == View.VISIBLE\n                        ? View.GONE : View.VISIBLE);\n                return true;\n            }\n''',
    'single tap edge handling',
)

replace_once(
    '''            case MotionEvent.ACTION_UP:\n                float ux = e.getX() - downX, uy = e.getY() - downY;\n                if (!scaling && gestureMode == G_NONE\n                        && zoom <= 1.001f && Math.abs(ux) > dp(60) && Math.abs(ux) > Math.abs(uy)) {\n                    stopSlideshow();\n                    if (ux < 0) next(); else previous();\n                }\n                gestureMode = G_NONE; scaling = false; hideGestureLater(); updateStatus(); return true;\n''',
    '''            case MotionEvent.ACTION_UP:\n                float ux = e.getX() - downX, uy = e.getY() - downY;\n                boolean navigatedByTap = false;\n                if (!scaling && gestureMode == G_NONE && !moved\n                        && Math.abs(ux) < dp(12) && Math.abs(uy) < dp(12) && tapNextGesture) {\n                    float position = e.getX() / Math.max(1f, v.getWidth());\n                    if (position < .35f) {\n                        stopSlideshow();\n                        previous();\n                        navigatedByTap = true;\n                    } else if (position > .65f) {\n                        stopSlideshow();\n                        next();\n                        navigatedByTap = true;\n                    }\n                }\n                if (!navigatedByTap && !scaling && gestureMode == G_NONE\n                        && zoom <= 1.001f && Math.abs(ux) > dp(60) && Math.abs(ux) > Math.abs(uy)) {\n                    stopSlideshow();\n                    if (ux < 0) next(); else previous();\n                }\n                gestureMode = G_NONE; scaling = false; hideGestureLater(); updateStatus(); return true;\n''',
    'immediate edge tap navigation',
)

replace_once(
    '''            mp.setOnCompletionListener(p -> { if (p == player) { finished = true; updateVideoTimeline(); updateButtons(); if (slideshow) advance(); } });\n''',
    '''            // Videos repeat continuously. setLooping is the normal path; the\n            // completion listener is retained as a fallback for device-specific players.\n            mp.setLooping(true);\n            mp.setOnCompletionListener(p -> {\n                if (p == player) {\n                    finished = false;\n                    try {\n                        p.seekTo(0);\n                        p.start();\n                    } catch (Exception ignored) {\n                    }\n                    updateVideoTimeline();\n                    updateButtons();\n                    startVideoProgress();\n                }\n            });\n''',
    'continuous video loop',
)

viewer.write_text(v, encoding='utf-8')

g = gradle.read_text(encoding='utf-8')
old_version = "versionCode 10\n        versionName '1.3.6'"
new_version = "versionCode 11\n        versionName '1.3.7'"
if old_version not in g:
    raise SystemExit('Expected version 1.3.6 was not found')
gradle.write_text(g.replace(old_version, new_version, 1), encoding='utf-8')
