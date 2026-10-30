#!/usr/bin/env python3
"""Compila TODO o Java do app Android contra "stubs" do SDK (só as assinaturas usadas).

O QUE ISTO PROVA: sintaxe, imports, tipos, lambdas e chamadas entre as nossas classes estão
coerentes. O QUE NÃO PROVA: que as assinaturas do Android escritas aqui batem 100% com o SDK real
(foram escritas à mão), nem nada sobre comportamento em aparelho. O build de verdade é no
Android Studio / Gradle (`gradlew assembleDebug`). Uso: python scripts/check_android_java.py
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'android' / 'app' / 'src' / 'main' / 'java'

# pacote.Classe -> corpo (o 'package' e a declaração da classe vêm do nome)
STUBS = {
 'android.os.Bundle': 'public class Bundle {}',
 'android.os.Looper': 'public class Looper { public static Looper getMainLooper() { return null; } }',
 'android.os.Handler': '''public class Handler { public Handler(Looper l) {}
   public boolean post(Runnable r) { return true; } public boolean postDelayed(Runnable r, long d) { return true; }
   public void removeCallbacks(Runnable r) {} public void removeCallbacksAndMessages(Object o) {} }''',
 'android.os.Build': '''public class Build { public static final String MANUFACTURER = "", MODEL = "";
   public static class VERSION { public static int SDK_INT = 34; public static final String RELEASE = ""; } }''',
 'android.graphics.Color': '''public class Color { public static final int BLACK = 0xff000000, GRAY = 0xff888888;
   public static int rgb(int r, int g, int b) { return 0; } public static int argb(int a, int r, int g, int b) { return 0; } }''',
 'android.graphics.Typeface': '''public class Typeface { public static final Typeface DEFAULT = null, MONOSPACE = null; public static final int BOLD = 1; }''',
 'android.graphics.Rect': 'public class Rect { public Rect(int l, int t, int r, int b) {} }',
 'android.content.res.ColorStateList': 'public class ColorStateList { public static ColorStateList valueOf(int c) { return null; } }',
 'android.content.res.Resources': 'public class Resources { public android.util.DisplayMetrics getDisplayMetrics() { return null; } }',
 'android.util.DisplayMetrics': 'public class DisplayMetrics { public float density; }',
 'android.content.Context': '''public class Context { public static final int MODE_PRIVATE = 0; public static final String WIFI_SERVICE = "wifi";
   public android.content.res.Resources getResources() { return null; }
   public android.content.SharedPreferences getSharedPreferences(String n, int m) { return null; }
   public Context getApplicationContext() { return this; } public Object getSystemService(String n) { return null; }
   public void startActivity(android.content.Intent i) {} public java.io.File getFilesDir() { return null; }
   public static final String CLIPBOARD_SERVICE = "clipboard"; }''',
 'android.app.Application': 'public class Application extends android.content.Context { public void onCreate() {} }',
 'android.content.ClipData': 'public class ClipData { public static ClipData newPlainText(CharSequence label, CharSequence text) { return null; } }',
 'android.content.ClipboardManager': 'public class ClipboardManager { public void setPrimaryClip(ClipData c) {} }',
 'android.widget.Toast': 'public class Toast { public static final int LENGTH_LONG = 1; public static Toast makeText(android.content.Context c, CharSequence t, int d) { return null; } public void show() {} }',
 'android.webkit.WebChromeClient': '''public class WebChromeClient { public interface CustomViewCallback { void onCustomViewHidden(); }
   public void onShowCustomView(android.view.View v, CustomViewCallback cb) {} public void onHideCustomView() {} }''',
 'android.view.ViewParent': 'public interface ViewParent {}',
 'android.content.Intent': '''public class Intent { public Intent(Context c, Class<?> k) {}
   public Intent putExtra(String k, String v) { return this; } public String getStringExtra(String k) { return null; } }''',
 'android.content.DialogInterface': '''public interface DialogInterface { interface OnClickListener { void onClick(DialogInterface dialog, int which); } }''',
 'android.content.SharedPreferences': '''public interface SharedPreferences {
   int getInt(String k, int d); boolean getBoolean(String k, boolean d); float getFloat(String k, float d); String getString(String k, String d);
   Editor edit();
   interface Editor { Editor putInt(String k, int v); Editor putBoolean(String k, boolean v); Editor putFloat(String k, float v);
                      Editor putString(String k, String v); void apply(); } }''',
 'android.content.pm.ActivityInfo': '''public class ActivityInfo { public static final int SCREEN_ORIENTATION_UNSPECIFIED = -1,
   SCREEN_ORIENTATION_SENSOR_LANDSCAPE = 6, SCREEN_ORIENTATION_SENSOR_PORTRAIT = 7, SCREEN_ORIENTATION_FULL_SENSOR = 10; }''',
 'android.app.Activity': '''public class Activity extends android.content.Context {
   protected void onCreate(android.os.Bundle b) {} protected void onPause() {} protected void onDestroy() {}
   public void onWindowFocusChanged(boolean f) {} public void onBackPressed() {}
   public void setContentView(android.view.View v) {} public android.view.Window getWindow() { return null; }
   public void addContentView(android.view.View v, android.view.ViewGroup.LayoutParams p) {}
   public void finish() {} public android.content.Intent getIntent() { return null; }
   public void setRequestedOrientation(int o) {} public void runOnUiThread(Runnable r) {}
   public android.content.SharedPreferences getPreferences(int m) { return null; } }''',
 'android.app.AlertDialog': '''public class AlertDialog { public static class Builder { public Builder(android.content.Context c) {}
   public Builder setTitle(CharSequence t) { return this; } public Builder setMessage(CharSequence t) { return this; }
   public Builder setView(android.view.View v) { return this; }
   public Builder setItems(CharSequence[] items, android.content.DialogInterface.OnClickListener l) { return this; }
   public Builder setPositiveButton(CharSequence t, android.content.DialogInterface.OnClickListener l) { return this; }
   public Builder setNegativeButton(CharSequence t, android.content.DialogInterface.OnClickListener l) { return this; }
   public AlertDialog show() { return null; } } }''',
 'android.net.Uri': '''public class Uri { public static Uri parse(String s) { return null; } public String getHost() { return null; } public String getScheme() { return null; } }''',
 'android.net.DhcpInfo': 'public class DhcpInfo { public int ipAddress, netmask; }',
 'android.net.wifi.WifiManager': 'public class WifiManager { public android.net.DhcpInfo getDhcpInfo() { return null; } }',
 'android.text.InputType': '''public class InputType { public static final int TYPE_CLASS_TEXT = 1, TYPE_TEXT_VARIATION_URI = 16, TYPE_CLASS_NUMBER = 2, TYPE_NUMBER_VARIATION_PASSWORD = 16; }''',
 'android.media.MediaCrypto': 'public class MediaCrypto {}',
 'android.media.MediaFormat': '''public class MediaFormat { public static final String MIMETYPE_VIDEO_AVC = "video/avc", KEY_LOW_LATENCY = "low-latency", KEY_PRIORITY = "priority";
   public static MediaFormat createVideoFormat(String m, int w, int h) { return null; }
   public void setByteBuffer(String k, java.nio.ByteBuffer b) {} public void setInteger(String k, int v) {} }''',
 'android.media.MediaCodec': '''public class MediaCodec { public static final int INFO_OUTPUT_FORMAT_CHANGED = -2;
   public static class BufferInfo { public long presentationTimeUs; public int size; }
   public static MediaCodec createDecoderByType(String t) throws java.io.IOException { return null; }
   public void configure(MediaFormat f, android.view.Surface s, MediaCrypto c, int flags) {}
   public void start() {} public void stop() {} public void release() {} public String getName() { return ""; }
   public int dequeueInputBuffer(long us) { return 0; } public java.nio.ByteBuffer getInputBuffer(int i) { return null; }
   public void queueInputBuffer(int i, int off, int size, long pts, int flags) {}
   public int dequeueOutputBuffer(BufferInfo info, long us) { return 0; } public void releaseOutputBuffer(int i, boolean render) {} }''',
 'android.view.Gravity': '''public class Gravity { public static final int TOP = 48, BOTTOM = 80, LEFT = 3, RIGHT = 5, CENTER = 17, CENTER_VERTICAL = 16; }''',
 'android.view.Surface': 'public class Surface { public boolean isValid() { return true; } }',
 'android.view.SurfaceHolder': '''public interface SurfaceHolder { Surface getSurface(); void addCallback(Callback c);
   interface Callback { void surfaceCreated(SurfaceHolder h); void surfaceChanged(SurfaceHolder h, int f, int w, int hh); void surfaceDestroyed(SurfaceHolder h); } }''',
 'android.view.SurfaceView': '''public class SurfaceView extends View { public SurfaceView(android.content.Context c) {} public SurfaceHolder getHolder() { return null; } }''',
 'android.view.MotionEvent': '''public class MotionEvent { public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2, ACTION_CANCEL = 3, ACTION_POINTER_DOWN = 5, ACTION_POINTER_UP = 6;
   public int getPointerCount() { return 1; } public float getX(int i) { return 0; } public float getY(int i) { return 0; }
   public int getActionMasked() { return 0; } public int getActionIndex() { return 0; } public long getEventTime() { return 0; } }''',
 'android.view.DisplayCutout': 'public class DisplayCutout { public int getSafeInsetLeft() { return 0; } public int getSafeInsetRight() { return 0; } }',
 'android.view.WindowInsets': '''public class WindowInsets { public DisplayCutout getDisplayCutout() { return null; } public static final class Type { public static int systemBars() { return 0; } } }''',
 'android.view.WindowInsetsController': '''public interface WindowInsetsController { int BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2; void hide(int types); void show(int types); void setSystemBarsBehavior(int b); }''',
 'android.view.WindowManager': '''public interface WindowManager { class LayoutParams extends ViewGroup.LayoutParams { public static final int FLAG_KEEP_SCREEN_ON = 128,
   LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES = 1, LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3; public int layoutInDisplayCutoutMode; public LayoutParams() { super(0, 0); } } }''',
 'android.view.Window': '''public class Window { public void setDecorFitsSystemWindows(boolean b) {} public WindowInsetsController getInsetsController() { return null; }
   public View getDecorView() { return null; } public void addFlags(int f) {} public void clearFlags(int f) {}
   public WindowManager.LayoutParams getAttributes() { return null; } public void setAttributes(WindowManager.LayoutParams p) {}
   public void setStatusBarColor(int c) {} public void setNavigationBarColor(int c) {} }''',
 'android.view.View': '''public class View { public static final int VISIBLE = 0, GONE = 8, SYSTEM_UI_FLAG_VISIBLE = 0, SYSTEM_UI_FLAG_LAYOUT_STABLE = 256, SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION = 512,
   SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN = 1024, SYSTEM_UI_FLAG_FULLSCREEN = 4, SYSTEM_UI_FLAG_HIDE_NAVIGATION = 2, SYSTEM_UI_FLAG_IMMERSIVE_STICKY = 4096;
   public interface OnClickListener { void onClick(View v); }
   public interface OnTouchListener { boolean onTouch(View v, MotionEvent e); }
   public interface OnLayoutChangeListener { void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob); }
   public interface OnApplyWindowInsetsListener { WindowInsets onApplyWindowInsets(View v, WindowInsets i); }
   public View() {} public View(android.content.Context c) {}
   public void setOnClickListener(OnClickListener l) {} public void setOnTouchListener(OnTouchListener l) {}
   public void addOnLayoutChangeListener(OnLayoutChangeListener l) {} public void setOnApplyWindowInsetsListener(OnApplyWindowInsetsListener l) {}
   public void setSystemGestureExclusionRects(java.util.List<android.graphics.Rect> r) {}
   public void setBackgroundColor(int c) {} public void setBackgroundTintList(android.content.res.ColorStateList l) {}
   public void setVisibility(int v) {} public int getVisibility() { return 0; } public void setPadding(int l, int t, int r, int b) {}
   public void setLayoutParams(ViewGroup.LayoutParams p) {} public void setEnabled(boolean e) {}
   public void setHorizontalScrollBarEnabled(boolean b) {} public void setSystemUiVisibility(int v) {}
   public int getWidth() { return 0; } public int getHeight() { return 0; } public ViewParent getParent() { return null; } }''',
 'android.view.ViewGroup': '''public class ViewGroup extends View { public ViewGroup(android.content.Context c) {}
   public static class LayoutParams { public LayoutParams(int w, int h) {} }
   public static class MarginLayoutParams extends LayoutParams { public int leftMargin, topMargin, rightMargin, bottomMargin; public MarginLayoutParams(int w, int h) { super(w, h); } }
   public void addView(View v) {} public void addView(View v, LayoutParams p) {} public void removeView(View v) {} public void removeAllViews() {}
   public void setClipToPadding(boolean b) {} }''',
 'android.widget.FrameLayout': '''public class FrameLayout extends android.view.ViewGroup { public FrameLayout(android.content.Context c) { super(c); }
   public static class LayoutParams extends android.view.ViewGroup.MarginLayoutParams { public int gravity;
     public LayoutParams(int w, int h) { super(w, h); } public LayoutParams(int w, int h, int g) { super(w, h); gravity = g; } } }''',
 'android.widget.HorizontalScrollView': '''public class HorizontalScrollView extends FrameLayout { public HorizontalScrollView(android.content.Context c) { super(c); } }''',
 'android.widget.ScrollView': '''public class ScrollView extends FrameLayout { public ScrollView(android.content.Context c) { super(c); } }''',
 'android.widget.LinearLayout': '''public class LinearLayout extends android.view.ViewGroup { public static final int VERTICAL = 1; public LinearLayout(android.content.Context c) { super(c); }
   public static class LayoutParams extends android.view.ViewGroup.MarginLayoutParams { public LayoutParams(int w, int h) { super(w, h); } public LayoutParams(int w, int h, float wt) { super(w, h); } }
   public void setOrientation(int o) {} public void setGravity(int g) {} }''',
 'android.widget.TextView': '''public class TextView extends android.view.View { public TextView(android.content.Context c) { super(c); }
   public void setText(CharSequence t) {} public CharSequence getText() { return ""; } public void setTextSize(float s) {} public void setTextColor(int c) {}
   public void setTypeface(android.graphics.Typeface t) {} public void setTypeface(android.graphics.Typeface t, int style) {} public void setGravity(int g) {}
   public void setAllCaps(boolean b) {} public void setError(CharSequence e) {} public void setHint(CharSequence h) {} public void setHintTextColor(int c) {}
   public void setSingleLine(boolean b) {} public void setInputType(int t) {} public void setMinHeight(int h) {} }''',
 'android.widget.Button': 'public class Button extends TextView { public Button(android.content.Context c) { super(c); } }',
 'android.widget.EditText': 'public class EditText extends TextView { public EditText(android.content.Context c) { super(c); } }',
 'android.webkit.WebSettings': '''public class WebSettings { public static final int MIXED_CONTENT_NEVER_ALLOW = 1; public void setJavaScriptEnabled(boolean b) {} public void setDomStorageEnabled(boolean b) {}
   public void setAllowFileAccess(boolean b) {} public void setAllowContentAccess(boolean b) {} public void setMixedContentMode(int m) {} }''',
 'android.webkit.WebResourceRequest': 'public interface WebResourceRequest { android.net.Uri getUrl(); }',
 'android.webkit.WebViewClient': '''public class WebViewClient { public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; } }''',
 'android.webkit.WebView': '''public class WebView extends android.view.View { public WebView(android.content.Context c) { super(c); }
   public WebSettings getSettings() { return null; } public void loadUrl(String u) {} public void stopLoading() {} public void destroy() {}
   public void setWebViewClient(WebViewClient c) {} public void setWebChromeClient(WebChromeClient c) {} }''',
}


def write_stubs(out: Path) -> None:
    for name, body in STUBS.items():
        pkg, cls = name.rsplit('.', 1)
        directory = out.joinpath(*pkg.split('.'))
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f'{cls}.java').write_text(f'package {pkg};\n{"import android.view.*;" if pkg.startswith("android.widget") else ""}\n{body}\n', encoding='utf-8')


def javac_cmd():
    if shutil.which('javac'):
        return ['javac']
    if shutil.which('java'):
        probe = subprocess.run(['java', '--list-modules'], capture_output=True, text=True)
        if 'jdk.compiler' in probe.stdout:
            return ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    return None


def main() -> int:
    javac = javac_cmd()
    if not javac:
        print('JDK não encontrado: nada a verificar.')
        return 2
    tmp = Path(tempfile.mkdtemp(prefix='om_stubs_'))
    try:
        stubs, classes = tmp / 'stubs', tmp / 'classes'
        stubs.mkdir(); classes.mkdir()
        write_stubs(stubs)
        files = [str(p) for p in sorted(stubs.rglob('*.java'))] + [str(p) for p in sorted(SRC.rglob('*.java'))]
        done = subprocess.run(javac + ['-Xlint:all', '-Xlint:-serial', '-d', str(classes)] + files, capture_output=True, text=True)
        print(done.stdout + done.stderr)
        n_app = len(list(SRC.rglob('*.java')))
        print(f'{"OK" if done.returncode == 0 else "FALHOU"}: {n_app} arquivos do app compilados contra stubs do SDK '
              '(isto NÃO substitui o build no Android Studio).')
        return done.returncode
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    sys.exit(main())
