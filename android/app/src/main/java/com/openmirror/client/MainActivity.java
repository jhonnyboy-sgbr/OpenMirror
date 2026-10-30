package com.openmirror.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Native launcher for H.264 (MediaCodec), with legacy MJPEG WebView fallback. */
public class MainActivity extends Activity {
    private static final int BG = Color.rgb(11, 17, 28);
    private static final int CARD = Color.rgb(23, 35, 53);
    private static final int TXT = Color.rgb(239, 245, 255);
    private static final int BLUE = Color.rgb(105, 157, 255);
    private static final Pattern HOST = Pattern.compile("^(localhost|(?:[0-9]{1,3}\\.){3}[0-9]{1,3})(?::[0-9]{1,5})?$");
    private LinearLayout root;
    private WebView browser;
    private String webHost;
    private LinearLayout webToolbar;
    private boolean webFullscreen;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);
        showLauncher();
        showLastCrash();
    }

    /** Se o app fechou por erro (ou capturou um erro) da última vez, mostra o texto e deixa copiar. */
    private void showLastCrash() {
        final String crash = CrashLog.read();
        if (crash == null) return;
        String shown = crash.length() > 3500 ? "…\n" + crash.substring(crash.length() - 3500) : crash;
        new AlertDialog.Builder(this).setTitle("Registro de erros do app")
                .setMessage("Mande este texto para quem está ajudando:\n\n" + shown)
                .setPositiveButton("Copiar e limpar", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("OpenMirror erros", crash));
                    CrashLog.clear();
                })
                .setNegativeButton("Só limpar", (d, w) -> CrashLog.clear())
                .show();
    }

    private TextView text(String text, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text); v.setTextSize(sp); v.setTextColor(TXT);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label); b.setTextColor(Color.rgb(9, 21, 39));
        b.setAllCaps(false); b.setTextSize(15);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BLUE));
        b.setMinHeight(dp(50));
        return b;
    }

    private void clear() {
        exitCustomView();
        webToolbar = null;
        webFullscreen = false;
        if (browser != null) {
            browser.stopLoading();
            browser.loadUrl("about:blank");
            root.removeView(browser);
            browser.destroy(); browser = null;
        }
        root.removeAllViews();
    }

    private String validate(EditText field) {
        String value = field.getText().toString().trim().replaceFirst("^https?://", "").replaceAll("/+$", "");
        if (!HOST.matcher(value).matches()) {
            field.setError("Use o IP local do PC, por exemplo 192.168.1.20:8765"); return null;
        }
        String[] parts = value.split(":");
        if (parts.length == 2) {
            try {
                int port = Integer.parseInt(parts[1]);
                if (port < 1 || port > 65535) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                field.setError("Porta inválida"); return null;
            }
        } else value += ":8765";
        getPreferences(MODE_PRIVATE).edit().putString("host", value).apply();
        return value;
    }

    private void showLauncher() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        clear();
        Immersive.clear(this);
        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(25), dp(35), dp(25), dp(28));
        scroll.addView(panel);
        TextView title = text("◈  OpenMirror", 29, true);
        panel.addView(title);
        TextView subtitle = text(AppInfo.VERSION + "  •  Seu Windows no celular", 14, false);
        subtitle.setTextColor(Color.rgb(158, 177, 206));
        LinearLayout.LayoutParams sub = new LinearLayout.LayoutParams(-1, -2);
        sub.topMargin = dp(9); panel.addView(subtitle, sub);
        TextView info = text("PC por cabo Ethernet + celular por Wi-Fi, ou USB com ADB reverse.", 14, false);
        info.setTextColor(Color.rgb(182, 195, 218));
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, -2);
        line.topMargin = dp(26); panel.addView(info, line);

        TextView ipLabel = text("ENDEREÇO DO PC", 12, true);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
        gap.topMargin = dp(27); panel.addView(ipLabel, gap);
        EditText host = new EditText(this);
        host.setSingleLine(true); host.setTextColor(TXT);
        host.setHintTextColor(Color.GRAY); host.setTextSize(17);
        host.setHint("192.168.1.20:8765");
        host.setText(getPreferences(MODE_PRIVATE).getString("host", ""));
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        panel.addView(host, new LinearLayout.LayoutParams(-1, dp(58)));

        Button find = button("Procurar PC na rede");
        find.setBackgroundTintList(android.content.res.ColorStateList.valueOf(CARD));
        find.setTextColor(TXT);
        LinearLayout.LayoutParams findSpace = new LinearLayout.LayoutParams(-1, dp(50));
        findSpace.topMargin = dp(10); panel.addView(find, findSpace);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        panel.addView(results, new LinearLayout.LayoutParams(-1, -2));
        find.setOnClickListener(v -> discover(find, results, host));

        TextView pinLabel = text("CÓDIGO DO PC (4 DÍGITOS)", 12, true);
        LinearLayout.LayoutParams pinGap = new LinearLayout.LayoutParams(-1, -2);
        pinGap.topMargin = dp(20); panel.addView(pinLabel, pinGap);
        EditText pin = new EditText(this);
        pin.setSingleLine(true); pin.setTextColor(TXT); pin.setTextSize(20);
        pin.setHint("0000"); pin.setHintTextColor(Color.GRAY);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        panel.addView(pin, new LinearLayout.LayoutParams(-1, dp(58)));

        Button nativeButton = button("▶  CONECTAR EM H.264 NATIVO");
        LinearLayout.LayoutParams btnSpace = new LinearLayout.LayoutParams(-1, dp(58));
        btnSpace.topMargin = dp(25); panel.addView(nativeButton, btnSpace);
        nativeButton.setOnClickListener(v -> {
            String h = validate(host);
            String code = pin.getText().toString().trim();
            if (h == null) return;
            if (!code.matches("[0-9]{4}")) { pin.setError("Digite os 4 dígitos do painel Windows"); return; }
            Intent intent = new Intent(this, NativeStreamActivity.class);
            intent.putExtra("host", h);
            intent.putExtra("pin", code);
            startActivity(intent);
            pin.setText("");
        });

        Button fallback = button("Abrir modo compatível (MJPEG)");
        fallback.setBackgroundTintList(android.content.res.ColorStateList.valueOf(CARD));
        fallback.setTextColor(TXT);
        LinearLayout.LayoutParams fallbackSpace = new LinearLayout.LayoutParams(-1, dp(56));
        fallbackSpace.topMargin = dp(13); panel.addView(fallback, fallbackSpace);
        fallback.setOnClickListener(v -> {
            String h = validate(host);
            if (h != null) showWeb("http://" + h);
        });
        TextView foot = text("H.264 requer FFmpeg no computador. O modo compatível funciona sem FFmpeg. A conexão NÃO é criptografada: use apenas na rede local privada, sem redirecionamento de portas.", 12, false);
        foot.setTextColor(Color.rgb(151, 170, 195));
        LinearLayout.LayoutParams footSpace = new LinearLayout.LayoutParams(-1, -2);
        footSpace.topMargin = dp(21); panel.addView(foot, footSpace);
    }

    /** Procura PCs com o OpenMirror aberto na mesma rede (UDP, sem nenhum segredo trafegando). */
    private void discover(Button trigger, LinearLayout results, EditText host) {
        trigger.setEnabled(false);
        trigger.setText("Procurando…");
        results.removeAllViews();
        new Thread(() -> {
            List<DiscoveryClient.Found> found = new ArrayList<>();
            String error = null;
            try {
                List<InetAddress> targets = new ArrayList<>();
                targets.add(InetAddress.getByName("255.255.255.255"));
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wifi != null) {
                    DhcpInfo dhcp = wifi.getDhcpInfo();
                    if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
                        targets.add(DiscoveryClient.broadcastFromDhcp(dhcp.ipAddress, dhcp.netmask));
                    }
                }
                found = DiscoveryClient.scan(targets, AppInfo.DISCOVERY_PORT, 1800);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final List<DiscoveryClient.Found> list = found;
            final String problem = error;
            runOnUiThread(() -> {
                trigger.setEnabled(true);
                trigger.setText("Procurar PC na rede");
                if (list.isEmpty()) {
                    TextView none = text(problem != null ? "Busca falhou: " + problem
                            : "Nenhum PC respondeu. Confira se o OpenMirror está transmitindo, se o celular está no mesmo Wi-Fi "
                              + "e se o Firewall do Windows permite a rede privada. Você ainda pode digitar o IP.", 13, false);
                    none.setTextColor(Color.rgb(182, 195, 218));
                    results.addView(none);
                    return;
                }
                for (DiscoveryClient.Found f : list) {
                    Button pick = button(f.name + "  ·  " + f.address() + "  (v" + f.version + ")");
                    pick.setTextSize(13);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
                    lp.topMargin = dp(8);
                    results.addView(pick, lp);
                    pick.setOnClickListener(v -> host.setText(f.address()));
                }
            });
        }, "OpenMirror-Discovery").start();
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void showWeb(String base) {
        clear();
        webHost = android.net.Uri.parse(base).getHost();
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(15), 0, dp(12), 0);
        toolbar.setBackgroundColor(CARD);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(54)));
        webToolbar = toolbar;
        TextView title = text("◈  OpenMirror · Compatível", 16, true);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button full = button("Tela cheia");
        full.setTextSize(12);
        LinearLayout.LayoutParams fullLp = new LinearLayout.LayoutParams(-2, dp(43));
        fullLp.rightMargin = dp(8);
        toolbar.addView(full, fullLp);
        full.setOnClickListener(v -> setWebFullscreen(true));
        Button back = button("Voltar");
        back.setTextSize(12); toolbar.addView(back, new LinearLayout.LayoutParams(-2, dp(43)));
        back.setOnClickListener(v -> showLauncher());
        browser = new WebView(this);
        browser.setBackgroundColor(BG);
        browser.getSettings().setJavaScriptEnabled(true);
        browser.getSettings().setDomStorageEnabled(true);
        browser.getSettings().setAllowFileAccess(false);
        browser.getSettings().setAllowContentAccess(false);
        browser.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                android.net.Uri url = request.getUrl();
                return !"http".equals(url.getScheme()) || !webHost.equals(url.getHost());
            }
        });
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
                showCustomView(view, callback);
            }

            @Override public void onHideCustomView() { exitCustomView(); }
        });
        root.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        browser.loadUrl(base + "/");
        Immersive.useCutout(getWindow());
        Immersive.apply(this);   // modo compatível já abre sem barras do sistema
    }

    /** Esconde também a barra do app. Sair: botão Voltar do Android. */
    private void setWebFullscreen(boolean on) {
        webFullscreen = on;
        if (webToolbar != null) webToolbar.setVisibility(on ? View.GONE : View.VISIBLE);
        Immersive.apply(this);
        if (on) Toast.makeText(this, "Use o Voltar do Android para sair da tela cheia", Toast.LENGTH_LONG).show();
    }

    /** A página pediu tela cheia (requestFullscreen): a WebView só entrega a vista se tratarmos isto. */
    private void showCustomView(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) { callback.onCustomViewHidden(); return; }
        customView = view;
        customCallback = callback;
        view.setBackgroundColor(Color.BLACK);
        addContentView(view, new FrameLayout.LayoutParams(-1, -1));
        if (browser != null) browser.setVisibility(View.GONE);
        Immersive.apply(this);
    }

    private void exitCustomView() {
        if (customView == null) return;
        View v = customView;
        customView = null;
        ViewParent parent = v.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(v);
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        if (browser != null) browser.setVisibility(View.VISIBLE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && browser != null) Immersive.apply(this);   // volta a esconder as barras depois de teclado/diálogo
    }

    @Override public void onBackPressed() {
        if (customView != null) exitCustomView();
        else if (webFullscreen) setWebFullscreen(false);
        else if (browser != null) showLauncher();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() { clear(); super.onDestroy(); }
}
