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
    '''        controls.setPadding(dp(7), dp(6), dp(7), dp(6));\n        controls.setBackground(new ColorDrawable(0xb8000000));\n''',
    '''        controls.setPadding(dp(8), dp(5), dp(8), dp(7));\n        controls.setBackground(panelBackground());\n        controls.setElevation(dp(10));\n''',
    'panel background and padding',
)

replace_once(
    '''        status.setTextColor(0xffffffff); status.setTextSize(14); status.setGravity(Gravity.CENTER);\n        controls.addView(status, new LinearLayout.LayoutParams(-1, -2));\n''',
    '''        status.setTextColor(0xffe8ebef); status.setTextSize(12.5f); status.setGravity(Gravity.CENTER);\n        status.setSingleLine(true);\n        status.setEllipsize(android.text.TextUtils.TruncateAt.END);\n        status.setPadding(dp(6), 0, dp(6), dp(3));\n        controls.addView(status, new LinearLayout.LayoutParams(-1, dp(24)));\n''',
    'compact status line',
)

replace_once(
    '''        videoTimeline = row();\n        videoTimeline.setVisibility(View.GONE);\n        videoSeek = new SeekBar(this);\n''',
    '''        videoTimeline = row();\n        videoTimeline.setVisibility(View.GONE);\n        videoTimeline.setPadding(dp(4), 0, dp(4), 0);\n        videoTimeline.setBackground(buttonBackground(false, true));\n        videoSeek = new SeekBar(this);\n''',
    'video timeline styling',
)

replace_once(
    '''        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(0, dp(38), 1f);\n        seekParams.setMargins(dp(4), 0, dp(4), 0);\n''',
    '''        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(0, dp(32), 1f);\n        seekParams.setMargins(dp(2), 0, dp(2), 0);\n''',
    'compact seekbar dimensions',
)

replace_once(
    '''        videoTimeline.addView(videoTime, new LinearLayout.LayoutParams(dp(112), dp(38)));\n''',
    '''        videoTimeline.addView(videoTime, new LinearLayout.LayoutParams(dp(104), dp(32)));\n''',
    'compact video time dimensions',
)

replace_once(
    '''        root.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));\n''',
    '''        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);\n        controlsParams.leftMargin = dp(6);\n        controlsParams.rightMargin = dp(6);\n        controlsParams.bottomMargin = dp(6);\n        root.addView(controls, controlsParams);\n''',
    'floating panel margins',
)

replace_once(
    '''        if(slideshowButton!=null) slideshowButton.setText(slideshow?"Zastavit":"Slideshow");\n''',
    '''        if(slideshowButton!=null) {\n            slideshowButton.setText(slideshow ? "Zastavit" : "Slideshow");\n            styleControlButton(slideshowButton, slideshow, true);\n        }\n''',
    'slideshow active styling',
)

replace_once(
    '''        boolean v=currentVideo(); if(videoButton!=null){videoButton.setEnabled(v);stopButton.setEnabled(v);boolean playing=false;try{playing=v&&player!=null&&prepared&&player.isPlaying();}catch(Exception ignored){}videoButton.setText(playing?"⏸ Pauza":"▶ Video");}\n''',
    '''        boolean v=currentVideo(); if(videoButton!=null){videoButton.setEnabled(v);stopButton.setEnabled(v);boolean playing=false;try{playing=v&&player!=null&&prepared&&player.isPlaying();}catch(Exception ignored){}videoButton.setText(playing?"⏸ Pauza":"▶ Video");styleControlButton(videoButton,playing,v);styleControlButton(stopButton,false,v);}\n''',
    'video button styling',
)

replace_once(
    '''            repeatButton.setEnabled(v);\n            repeatButton.setText(repeatVideo ? "🔁 Opakuje" : "🔁 Opakovat");\n''',
    '''            repeatButton.setEnabled(v);\n            repeatButton.setText(repeatVideo ? "🔁 Opakuje" : "🔁 Opakovat");\n            styleControlButton(repeatButton, repeatVideo, v);\n''',
    'repeat active styling',
)

replace_once(
    '''        if(lockButton!=null) lockButton.setText(zoomLocked?"🔒 Pozice":"🔓 Pozice");\n''',
    '''        if(lockButton!=null) {\n            lockButton.setText(zoomLocked ? "🔒 Pozice" : "🔓 Pozice");\n            styleControlButton(lockButton, zoomLocked, true);\n        }\n''',
    'lock active styling',
)

replace_once(
    '''    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER);return r;}\n    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(13);b.setPadding(dp(3),0,dp(3),0);return b;}\n    private LinearLayout.LayoutParams weight(float w){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),w);p.setMargins(dp(2),dp(1),dp(2),dp(1));return p;}\n''',
    '''    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER);return r;}\n    private Button button(String s){\n        Button b=new Button(this);\n        b.setText(s);\n        b.setAllCaps(false);\n        b.setTextSize(12);\n        b.setTextColor(0xfff4f6f8);\n        b.setGravity(Gravity.CENTER);\n        b.setIncludeFontPadding(false);\n        b.setMinWidth(0); b.setMinimumWidth(0);\n        b.setMinHeight(0); b.setMinimumHeight(0);\n        b.setPadding(dp(5),0,dp(5),0);\n        b.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);\n        b.setBackground(buttonBackground(false, true));\n        b.setElevation(dp(1));\n        return b;\n    }\n    private LinearLayout.LayoutParams weight(float w){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(38),w);p.setMargins(dp(2),dp(2),dp(2),dp(1));return p;}\n    private android.graphics.drawable.GradientDrawable panelBackground(){\n        android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();\n        g.setColor(0xee15191e);\n        g.setCornerRadius(dp(16));\n        g.setStroke(dp(1),0x66474e57);\n        return g;\n    }\n    private android.graphics.drawable.GradientDrawable buttonBackground(boolean active, boolean enabled){\n        android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();\n        g.setCornerRadius(dp(10));\n        if(!enabled){g.setColor(0x5530363d);g.setStroke(dp(1),0x333f464f);}\n        else if(active){g.setColor(0xff126b5d);g.setStroke(dp(1),0xff54cbb0);}\n        else{g.setColor(0xdd2a3037);g.setStroke(dp(1),0x665c6570);}\n        return g;\n    }\n    private void styleControlButton(Button b, boolean active, boolean enabled){\n        if(b==null)return;\n        b.setEnabled(enabled);\n        b.setAlpha(enabled?1f:.52f);\n        b.setTextColor(enabled?0xfff7f9fa:0xffa0a6ad);\n        b.setBackground(buttonBackground(active,enabled));\n    }\n''',
    'compact styled controls helpers',
)

viewer.write_text(v, encoding='utf-8')

g = gradle.read_text(encoding='utf-8')
old_version = "versionCode 13\n        versionName '1.3.9'"
new_version = "versionCode 14\n        versionName '1.4.0'"
if old_version not in g:
    raise SystemExit('Expected version 1.3.9 was not found')
gradle.write_text(g.replace(old_version, new_version, 1), encoding='utf-8')
