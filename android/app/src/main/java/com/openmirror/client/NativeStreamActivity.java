package com.openmirror.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Tela de transmissão H.264 nativa (SurfaceView + MediaCodec). A rede/decoder ficam em
 * {@link StreamSession}; a geometria em {@link ViewportMapper}; os gestos em {@link GestureEngine}.
 */
public class NativeStreamActivity extends Activity implements SurfaceHolder.Callback {
    private static final int BG = Color.rgb(8, 13, 21);
    private static final int TEXT = Color.rgb(238, 246, 255);
    private static final int BTN = Color.rgb(34, 55, 83);
    private static final long TOOLBAR_HIDE_MS = 6000;

    private static final int[] ORIENTATIONS = {ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR};
    private static final String[] ORIENTATION_NAMES = {"Paisagem", "Retrato", "Automático"};
    private static final String[] MODE_NAMES = {"Ajustar", "Preencher", "Esticar"};

    /** {rótulo, teclas...} dos atalhos do Windows. */
    private static final String[][] SHORTCUTS = {
            {"Esc", "escape"}, {"Enter", "enter"}, {"Tab", "tab"}, {"Backspace", "backspace"},
            {"Delete", "delete"}, {"Tecla Windows", "win"}, {"Alt + Tab (trocar janela)", "alt", "tab"},
            {"Win + D (mostrar área de trabalho)", "win", "d"}, {"Ctrl + C", "ctrl", "c"},
            {"Ctrl + V", "ctrl", "v"}, {"Ctrl + X", "ctrl", "x"}, {"Ctrl + Z", "ctrl", "z"},
            {"Ctrl + A", "ctrl", "a"}, {"Ctrl + S", "ctrl", "s"}, {"Alt + F4 (fechar)", "alt", "f4"},
            {"Ctrl + Shift + Esc (gerenciador de tarefas)", "ctrl", "shift", "escape"},
            {"Win + L (bloquear o PC)", "win", "l"}, {"F5", "f5"}, {"F11 (tela cheia)", "f11"},
            {"Print Screen", "printscreen"}, {"←", "left"}, {"↑", "up"}, {"↓", "down"}, {"→", "right"}};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ViewportMapper mapper = new ViewportMapper();
    private GestureEngine engine;
    private StreamSession session;
    private SharedPreferences prefs;
    private volatile RemoteInput input;
    private SessionEvents events;
    private String host, pin;
    private boolean initFailed;
    private final List<String> startupNotes = new ArrayList<>();

    private FrameLayout root, videoContainer;
    private SurfaceView surface;
    private HorizontalScrollView toolbar;
    private TextView stats, banner;
    private Button fitButton, modeButton, rotateButton, infoButton;
    private boolean statsVisible, toolbarVisible = true, threeFingerHandled;
    private String lastStatsText = "";
    private StreamSession.Snapshot lastSnapshot;

    private final Runnable hideToolbar = () -> setToolbarVisible(false);

    private final GestureEngine.Timers timers = new GestureEngine.Timers() {
        @Override public void post(Runnable r, long delayMs) { ui.postDelayed(r, delayMs); }
        @Override public void cancel(Runnable r) { ui.removeCallbacks(r); }
    };

    /** Encaminha os gestos para a entrada ATUAL (ela é recriada a cada reconexão). */
    private final GestureEngine.Sink sinkProxy = new GestureEngine.Sink() {
        @Override public void move(float u, float v) { RemoteInput i = input; if (i != null) i.move(u, v); }
        @Override public void down(float u, float v, int b) { RemoteInput i = input; if (i != null) i.down(u, v, b); }
        @Override public void up(float u, float v, int b) { RemoteInput i = input; if (i != null) i.up(u, v, b); }
        @Override public void tap(float u, float v) { RemoteInput i = input; if (i != null) i.tap(u, v); }
        @Override public void rightClick(float u, float v) { RemoteInput i = input; if (i != null) i.rightClick(u, v); }
        @Override public void scroll(int dx, int dy) { RemoteInput i = input; if (i != null) i.scroll(dx, dy); }
        @Override public void releaseAll() { RemoteInput i = input; if (i != null) i.releaseAll(); }
    };

    private int dp(int n) { return Math.round(getResources().getDisplayMetrics().density * n); }

    private Button action(String text, Runnable callback) {
        Button b = new Button(this);
        b.setAllCaps(false); b.setText(text); b.setTextSize(12); b.setTextColor(TEXT);
        b.setBackgroundTintList(ColorStateList.valueOf(BTN));
        b.setOnClickListener(v -> {
            try {
                callback.run();
            } catch (RuntimeException e) {
                CrashLog.report("botão " + text, e, false);
                banner("Erro no botão «" + text + "»: " + e);
            }
            scheduleToolbarHide();
        });
        return b;
    }

    // ===================================================================== ciclo
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            initUi();
        } catch (Throwable t) {
            // Em vez de o app fechar sem explicação, mostramos o erro e gravamos no registro.
            initFailed = true;
            CrashLog.report("NativeStreamActivity.onCreate", t, false);
            showStartupError(t);
        }
    }

    /** Roda uma etapa NÃO essencial (janela, insets…): se falhar, anota e segue em frente. */
    private void guard(String label, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            startupNotes.add(label + ": " + e);
            CrashLog.report("NativeStreamActivity." + label, e, false);
        }
    }

    private void showStartupError(Throwable t) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(30), dp(20), dp(20));
        TextView title = new TextView(this);
        title.setText("A tela de vídeo não conseguiu abrir");
        title.setTextColor(TEXT); title.setTextSize(20);
        TextView body = new TextView(this);
        java.io.StringWriter trace = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(trace));
        body.setText("Mande este texto para quem está ajudando (também fica salvo e aparece na tela inicial):\n\n" + trace);
        body.setTextColor(TEXT); body.setTextSize(12); body.setTypeface(Typeface.MONOSPACE);
        Button back = new Button(this);
        back.setText("Voltar");
        back.setOnClickListener(v -> finish());
        box.addView(title);
        box.addView(body);
        box.addView(back);
        scroll.addView(box);
        setContentView(scroll);
    }

    private void initUi() {
        prefs = getSharedPreferences("openmirror_stream", MODE_PRIVATE);
        guard("orientação", () -> setRequestedOrientation(ORIENTATIONS[orientationIndex()]));
        guard("tela ligada", () -> getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        guard("recorte", this::useDisplayCutout);
        guard("tela cheia", this::applyImmersive);

        host = getIntent().getStringExtra("host");
        pin = getIntent().getStringExtra("pin");
        if (host == null || pin == null) { finish(); return; }

        mapper.setMode(ViewportMapper.Mode.values()[Math.max(0, Math.min(2, prefs.getInt("fit", 0)))]);
        statsVisible = prefs.getBoolean("stats", true);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        videoContainer = new FrameLayout(this);
        root.addView(videoContainer, new FrameLayout.LayoutParams(-1, -1));
        surface = new SurfaceView(this);
        videoContainer.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        surface.getHolder().addCallback(this);
        videoContainer.setOnTouchListener((v, e) -> onVideoTouch(e));
        videoContainer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l > 0 && b - t > 0) { mapper.setView(r - l, b - t); applyViewport(); }
        });

        engine = new GestureEngine(sinkProxy, this::applyViewport, mapper, timers,
                getResources().getDisplayMetrics().density);
        engine.setPointerMode(prefs.getBoolean("trackpad", false)
                ? GestureEngine.PointerMode.TRACKPAD : GestureEngine.PointerMode.TOUCH);
        engine.setSensitivity(prefs.getFloat("sens", 1.6f));

        buildOverlay();

        if (Build.VERSION.SDK_INT >= 29) {   // o gesto de voltar do Android não pode roubar arrastos nas bordas
            root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> guard("gestos de borda", () ->
                    v.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, r - l, b - t)))));
        }
        scheduleToolbarHide();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        // Uma sessão NOVA por Surface; só o token de pareamento é reaproveitado.
        if (initFailed) return;
        try {
            if (events != null) events.active = false;
            String token = session != null ? session.token() : null;
            events = new SessionEvents();
            session = new StreamSession(host, pin, events, token);
            session.start(holder.getSurface());
        } catch (RuntimeException e) {
            CrashLog.report("surfaceCreated", e, false);
            banner("Não foi possível iniciar a sessão: " + e);
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        // A Surface morre quando o app vai para segundo plano. O decoder PRECISA largá-la antes
        // de esta função retornar; ao voltar, surfaceCreated reconecta sozinho.
        try {
            if (engine != null) engine.cancelAll();
            if (events != null) events.active = false;   // callbacks atrasados da sessão antiga são ignorados
            input = null;
            if (session != null) session.stop();
        } catch (RuntimeException e) {
            CrashLog.report("surfaceDestroyed", e, false);
        }
    }

    @Override protected void onPause() {
        if (engine != null) engine.cancelAll();   // nada de botão preso no PC
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !initFailed) applyImmersive();   // restaura a tela cheia depois de diálogo/notificação/teclado
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (session != null) session.stop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }

    // ================================================================ tela cheia
    private void applyImmersive() { Immersive.apply(this); }

    /** Estende o vídeo por baixo do recorte (notch/furo) em vez de deixar tarja preta. */
    private void useDisplayCutout() { Immersive.useCutout(getWindow()); }

    // ===================================================================== layout
    private void buildOverlay() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(9), 0, dp(9), 0);
        row.addView(action("‹ Voltar", this::finish));
        row.addView(action("Teclado", this::showKeyboard));
        row.addView(action("Atalhos", this::showShortcuts));
        fitButton = action("", this::cycleFit);
        row.addView(fitButton);
        modeButton = action("", this::showPointerModes);
        row.addView(modeButton);
        row.addView(action("Zoom 1×", () -> { mapper.reset(); applyViewport(); }));
        rotateButton = action("", this::cycleOrientation);
        row.addView(rotateButton);
        infoButton = action("", this::toggleStats);
        row.addView(infoButton);
        row.addView(action("Diagnóstico", this::showDiagnostics));
        refreshButtonLabels();

        toolbar = new HorizontalScrollView(this);
        toolbar.setHorizontalScrollBarEnabled(false);
        toolbar.setBackgroundColor(Color.argb(232, 17, 27, 43));
        toolbar.addView(row);
        toolbar.setPadding(0, 0, dp(64), 0);
        toolbar.setClipToPadding(false);
        root.addView(toolbar, new FrameLayout.LayoutParams(-1, dp(57), Gravity.TOP));
        root.setOnApplyWindowInsetsListener((v, insets) -> {          // controles fora do recorte da tela
            guard("recorte nos controles", () -> {
                if (Build.VERSION.SDK_INT >= 28) {
                    DisplayCutout dc = insets.getDisplayCutout();
                    int left = dc == null ? 0 : dc.getSafeInsetLeft();
                    int right = dc == null ? 0 : dc.getSafeInsetRight();
                    toolbar.setPadding(left, 0, dp(64) + right, 0);
                }
            });
            return insets;
        });

        Button menu = new Button(this);
        menu.setAllCaps(false); menu.setText("☰"); menu.setTextSize(16); menu.setTextColor(TEXT);
        menu.setBackgroundTintList(ColorStateList.valueOf(Color.argb(190, 34, 55, 83)));
        menu.setOnClickListener(v -> setToolbarVisible(!toolbarVisible));
        root.addView(menu, new FrameLayout.LayoutParams(dp(54), dp(50), Gravity.TOP | Gravity.RIGHT));

        stats = new TextView(this);
        stats.setTextColor(TEXT); stats.setTextSize(11); stats.setTypeface(Typeface.MONOSPACE);
        stats.setBackgroundColor(Color.argb(160, 0, 0, 0));
        stats.setPadding(dp(8), dp(4), dp(8), dp(4));
        stats.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        sp.leftMargin = dp(8); sp.bottomMargin = dp(8);
        root.addView(stats, sp);

        banner = new TextView(this);
        banner.setTextColor(TEXT); banner.setTextSize(15); banner.setGravity(Gravity.CENTER);
        banner.setBackgroundColor(Color.argb(205, 17, 27, 43));
        banner.setPadding(dp(18), dp(12), dp(18), dp(12));
        banner.setText("Conectando ao PC…");
        root.addView(banner, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    }

    /** Posiciona a SurfaceView no retângulo calculado (pode ser maior que a tela: zoom/Preencher). */
    private void applyViewport() {
        if (surface == null) return;
        float[] r = new float[4];
        mapper.rect(r);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                Math.max(1, Math.round(r[2])), Math.max(1, Math.round(r[3])), Gravity.TOP | Gravity.LEFT);
        p.leftMargin = Math.round(r[0]);
        p.topMargin = Math.round(r[1]);
        surface.setLayoutParams(p);
    }

    private void refreshButtonLabels() {
        fitButton.setText("Imagem: " + MODE_NAMES[mapper.mode().ordinal()]);
        modeButton.setText(engine != null && engine.pointerMode() == GestureEngine.PointerMode.TRACKPAD
                ? "Modo: Trackpad" : "Modo: Toque");
        rotateButton.setText("Tela: " + ORIENTATION_NAMES[orientationIndex()]);
        infoButton.setText(statsVisible ? "Indicadores: sim" : "Indicadores: não");
    }

    private void setToolbarVisible(boolean visible) {
        toolbarVisible = visible;
        toolbar.setVisibility(visible ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(hideToolbar);
        if (visible) scheduleToolbarHide();
    }

    private void scheduleToolbarHide() {
        ui.removeCallbacks(hideToolbar);
        ui.postDelayed(hideToolbar, TOOLBAR_HIDE_MS);
    }

    // ====================================================================== toque
    private boolean onVideoTouch(MotionEvent e) {
        try {
            return handleTouch(e);
        } catch (RuntimeException ex) {
            CrashLog.report("toque", ex, false);
            return true;
        }
    }

    private boolean handleTouch(MotionEvent e) {
        int n = e.getPointerCount();
        float x0 = e.getX(0), y0 = e.getY(0);
        float x1 = n > 1 ? e.getX(1) : x0, y1 = n > 1 ? e.getY(1) : y0;
        long t = e.getEventTime();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                threeFingerHandled = false;
                engine.onTouch(GestureEngine.Action.DOWN, 1, x0, y0, x0, y0, t);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (n >= 3 && !threeFingerHandled) {          // 3 dedos: mostra/esconde os controles
                    threeFingerHandled = true;
                    setToolbarVisible(!toolbarVisible);
                }
                engine.onTouch(GestureEngine.Action.POINTER_DOWN, n, x0, y0, x1, y1, t);
                break;
            case MotionEvent.ACTION_MOVE:
                engine.onTouch(GestureEngine.Action.MOVE, n, x0, y0, x1, y1, t);
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                int leaving = e.getActionIndex();
                int keep = leaving == 0 ? 1 : 0;
                float kx = e.getX(Math.min(keep, n - 1)), ky = e.getY(Math.min(keep, n - 1));
                engine.onTouch(GestureEngine.Action.POINTER_UP, n - 1, kx, ky, kx, ky, t);
                break;
            }
            case MotionEvent.ACTION_UP:
                engine.onTouch(GestureEngine.Action.UP, 1, x0, y0, x0, y0, t);
                break;
            case MotionEvent.ACTION_CANCEL:
                engine.onTouch(GestureEngine.Action.CANCEL, 1, x0, y0, x0, y0, t);
                break;
            default:
                break;
        }
        return true;
    }

    // ===================================================================== ações
    private void cycleFit() {
        ViewportMapper.Mode[] all = ViewportMapper.Mode.values();
        mapper.setMode(all[(mapper.mode().ordinal() + 1) % all.length]);
        prefs.edit().putInt("fit", mapper.mode().ordinal()).apply();
        applyViewport();
        refreshButtonLabels();
    }

    private void cycleOrientation() {
        int next = (orientationIndex() + 1) % ORIENTATIONS.length;
        prefs.edit().putInt("orient", next).apply();
        setRequestedOrientation(ORIENTATIONS[next]);
        refreshButtonLabels();
    }

    private int orientationIndex() {
        return Math.max(0, Math.min(ORIENTATIONS.length - 1, prefs.getInt("orient", 0)));
    }

    private void toggleStats() {
        statsVisible = !statsVisible;
        prefs.edit().putBoolean("stats", statsVisible).apply();
        stats.setVisibility(statsVisible && !lastStatsText.isEmpty() ? View.VISIBLE : View.GONE);
        refreshButtonLabels();
    }

    private void showPointerModes() {
        String[] items = {"Toque direto (o cursor vai onde o dedo toca)", "Trackpad lento",
                "Trackpad normal", "Trackpad rápido"};
        new AlertDialog.Builder(this).setTitle("Modo do ponteiro")
                .setItems(items, (d, which) -> {
                    boolean trackpad = which > 0;
                    float sens = which == 1 ? 0.9f : which == 2 ? 1.6f : 2.6f;
                    engine.setPointerMode(trackpad ? GestureEngine.PointerMode.TRACKPAD : GestureEngine.PointerMode.TOUCH);
                    if (trackpad) engine.setSensitivity(sens);
                    prefs.edit().putBoolean("trackpad", trackpad).putFloat("sens", engine.sensitivity()).apply();
                    refreshButtonLabels();
                }).show();
    }

    private void showShortcuts() {
        String[] labels = new String[SHORTCUTS.length];
        for (int i = 0; i < SHORTCUTS.length; i++) labels[i] = SHORTCUTS[i][0];
        new AlertDialog.Builder(this).setTitle("Atalhos do Windows")
                .setItems(labels, (d, which) -> {
                    RemoteInput in = input;
                    if (in == null) return;
                    String[] keys = new String[SHORTCUTS[which].length - 1];
                    System.arraycopy(SHORTCUTS[which], 1, keys, 0, keys.length);
                    if (!in.combo(keys)) {
                        banner("Esse atalho precisa do OpenMirror 0.3.0 ou mais novo no Windows.");
                    }
                }).show();
    }

    private void showKeyboard() {
        EditText edit = new EditText(this);
        edit.setTextColor(Color.BLACK); edit.setTextSize(17);
        edit.setHint("Digite o texto para enviar ao PC");
        new AlertDialog.Builder(this).setTitle("Teclado remoto").setView(edit)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Enviar", (dialog, which) -> {
                    String value = edit.getText().toString();
                    RemoteInput in = input;
                    if (in != null && !value.isEmpty()) in.text(value.length() > 250 ? value.substring(0, 250) : value);
                }).show();
    }

    private void showDiagnostics() {
        StringBuilder b = new StringBuilder();
        b.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ")
                .append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        b.append(session != null ? session.diagnostics() : "");
        if (!startupNotes.isEmpty()) {
            b.append("Avisos na abertura da tela (não fatais):\n");
            for (String note : startupNotes) b.append(" - ").append(note).append('\n');
        }
        if (lastSnapshot != null) b.append('\n').append(formatStats(lastSnapshot));
        new AlertDialog.Builder(this).setTitle("Diagnóstico").setMessage(b.toString())
                .setPositiveButton("Fechar", null).show();
    }

    private void banner(String text) {
        banner.setText(text);
        banner.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ================================================================ indicadores
    private static String ms(double v) { return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.0f ms", v); }
    private static String ms1(double v) { return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.1f ms", v); }
    private static String fps(double v) { return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.1f", v); }

    static String formatStats(StreamSession.Snapshot s) {
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "H.264 v%d · %d×%d · %s · %s\n", s.proto, s.width, s.height, s.transport, s.codec));
        b.append("Medido no celular: decod ").append(fps(s.decodeFps)).append(" · exib ").append(fps(s.renderFps))
          .append(" FPS · decodif. ").append(ms1(s.decodeMsAvg)).append(" (p95 ").append(ms1(s.decodeMsP95)).append(")\n");
        b.append("Fila ").append(s.queueDepth).append(" (espera ").append(ms1(s.queueWaitMsAvg)).append(") · ")
          .append(String.format(Locale.ROOT, "%.1f Mbps", s.bitrateKbps / 1000.0))
          .append(" · perdidos: ").append(s.lostBySeq).append(" no PC, ").append(s.droppedByClient).append(" aqui\n");
        b.append("Estimado*: RTT ").append(ms1(s.rttMs)).append(" · idade do quadro ").append(ms(s.ageMsAvg)).append('\n');
        b.append("Medido no PC (").append(s.encoderActive).append("): codif ").append(fps(s.serverEncodeFps))
          .append(" · env ").append(fps(s.serverSentFps)).append(" FPS · ")
          .append(Double.isNaN(s.serverBitrateKbps) ? "—" : String.format(Locale.ROOT, "%.1f Mbps", s.serverBitrateKbps / 1000.0))
          .append(s.serverDropped >= 0 ? " · descartados " + s.serverDropped : "").append('\n');
        b.append("*estimativa por ping (relógios do PC e do celular não são idênticos)");
        return b.toString();
    }

    // ==================================================================== sessão
    private final class SessionEvents implements StreamSession.Callbacks {
        volatile boolean active = true;

        @Override public void onStatus(String text) { ui.post(() -> { if (active) banner(text); }); }

        @Override public void onVideoSize(int w, int h) {
            ui.post(() -> { if (active) { mapper.setVideo(w, h); applyViewport(); } });
        }

        @Override public void onReady(ServerInfo info, RemoteInput in) {
            if (!active) return;
            input = in;
            if (info.legacy) ui.post(() -> { if (active) banner("PC com OpenMirror antigo: arrastar e atalhos ficam limitados. Atualize o Windows."); });
        }

        @Override public void onStats(StreamSession.Snapshot s) {
            ui.post(() -> {
                if (!active) return;
                lastSnapshot = s;
                lastStatsText = formatStats(s);
                if (statsVisible) { stats.setText(lastStatsText); stats.setVisibility(View.VISIBLE); }
                if (s.decodeFps > 0 && banner.getVisibility() == View.VISIBLE
                        && banner.getText().toString().startsWith("Conect")) banner("");
            });
        }

        @Override public void onFatal(String message) {
            ui.post(() -> { if (active) { banner(message); setToolbarVisible(true); ui.removeCallbacks(hideToolbar); } });
        }
    }
}
