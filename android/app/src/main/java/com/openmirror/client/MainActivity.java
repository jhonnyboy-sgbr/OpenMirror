package com.openmirror.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.CheckBox;
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
    private static final int DARK_PAGE = Color.rgb(6, 11, 19);
    private static final int TXT = Color.rgb(239, 245, 255);
    private static final int BLUE = Color.rgb(105, 157, 255);
    private static final String GITHUB_URL = "https://github.com/jhonnyboy-sgbr/OpenMirror";
    private static final Pattern HOST = Pattern.compile("^(localhost|(?:[0-9]{1,3}\\.){3}[0-9]{1,3})(?::[0-9]{1,5})?$");
    private FrameLayout root;
    private WebView browser;
    private String webHost;
    private LinearLayout webToolbar;
    private boolean webFullscreen;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private boolean menuOpen;
    private FrameLayout drawer;

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        showLauncher();
        showLastCrash();
        if (!getPreferences(MODE_PRIVATE).getBoolean("welcome_dismissed", false)) {
            root.postDelayed(this::showWelcome, 350);
        }
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

    private void showWelcome() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(8);
        content.setPadding(padding, 0, padding, 0);

        TextView message = text(
                "O OpenMirror transforma o celular em uma tela de controle remoto para o Windows.\n\n"
                        + "Para começar:\n"
                        + "1. Inicie o OpenMirror no PC.\n"
                        + "2. Informe o endereço do PC ou procure na rede.\n"
                        + "3. Digite o PIN exibido no painel Windows.\n"
                        + "4. Toque em Conectar.",
                14, false);
        message.setTextColor(Color.rgb(182, 195, 218));
        content.addView(message);

        TextView repository = text("Visite o repositório no GitHub", 14, false);
        repository.setTextColor(BLUE);
        repository.setPaintFlags(repository.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        repository.setPadding(0, dp(16), 0, dp(10));
        repository.setOnClickListener(v -> openRepository());
        content.addView(repository);

        CheckBox dontShow = new CheckBox(this);
        dontShow.setText("Não exibir novamente");
        dontShow.setTextColor(TXT);
        dontShow.setTextSize(14);
        content.addView(dontShow);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Bem-vindo ao OpenMirror")
                .setView(content)
                .setPositiveButton("Começar", (d, w) -> getPreferences(MODE_PRIVATE).edit()
                        .putBoolean("welcome_dismissed", dontShow.isChecked()).apply())
                .create();
        dialog.show();
    }

    private void openRepository() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Não foi possível abrir o GitHub.", Toast.LENGTH_SHORT).show();
        }
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

    private void styleMenuOption(Button option) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setStroke(dp(1), DARK_PAGE);
        background.setCornerRadius(dp(6));
        option.setBackground(background);
        option.setTextColor(TXT);
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
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(25), dp(35), dp(25), dp(28));
        scroll.addView(panel);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button menu = button("☰");
        menu.setTextSize(20);
        menu.setMinHeight(dp(44));
        menu.setPadding(dp(8), 0, dp(8), 0);
        menu.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BG));
        menu.setTextColor(TXT);
        header.addView(menu, new LinearLayout.LayoutParams(dp(52), dp(48)));
        TextView title = text("◈  OpenMirror", 29, true);
        header.addView(title, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(header);
        menu.setOnClickListener(v -> toggleMenu());
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

        Button nativeButton = button("▶  CONECTAR");
        LinearLayout.LayoutParams btnSpace = new LinearLayout.LayoutParams(-1, dp(58));
        btnSpace.topMargin = dp(25); panel.addView(nativeButton, btnSpace);
        nativeButton.setOnClickListener(v -> {
            String h = validate(host);
            String code = pin.getText().toString().trim();
            if (h == null) return;
            if (!code.matches("[0-9]{4}")) { pin.setError("Digite os 4 dígitos do painel Windows"); return; }
            if (getPreferences(MODE_PRIVATE).getBoolean("use_mjpeg", false)) {
                showWeb("http://" + h);
            } else {
                Intent intent = new Intent(this, NativeStreamActivity.class);
                intent.putExtra("host", h);
                intent.putExtra("pin", code);
                startActivity(intent);
            }
            pin.setText("");
        });

        TextView foot = text("Modo de vídeo: " + (getPreferences(MODE_PRIVATE).getBoolean("use_mjpeg", false)
                ? "MJPEG compatível" : "H.264 nativo") + ". Use apenas na rede local privada.", 12, false);
        foot.setTextColor(Color.rgb(151, 170, 195));
        LinearLayout.LayoutParams footSpace = new LinearLayout.LayoutParams(-1, -2);
        footSpace.topMargin = dp(21); panel.addView(foot, footSpace);
    }

    private void toggleMenu() {
        if (menuOpen) {
            closeMenu();
            return;
        }
        menuOpen = true;
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(CARD);
        drawer.setElevation(dp(12));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(25), dp(35), dp(25), dp(28));
        drawer.addView(page, new FrameLayout.LayoutParams(dp(310), -1));
        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(
                dp(310), -1, Gravity.START);
        drawerParams.leftMargin = -dp(310);
        root.addView(drawer, drawerParams);
        drawer.post(() -> drawer.animate().translationX(dp(310)).setDuration(220).start());
        Button close = button("☰");
        close.setTextSize(20);
        close.setBackgroundTintList(android.content.res.ColorStateList.valueOf(CARD));
        close.setTextColor(TXT);
        page.addView(close, new LinearLayout.LayoutParams(dp(52), dp(48)));
        close.setOnClickListener(v -> closeMenu());
        TextView title = text("Menu", 28, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(18);
        page.addView(title, titleParams);
        TextView subtitle = text("OpenMirror", 14, false);
        subtitle.setTextColor(Color.rgb(158, 177, 206));
        page.addView(subtitle);
        Button dashboard = button("⌂  Painel principal");
        styleMenuOption(dashboard);
        LinearLayout.LayoutParams dashboardSpace = new LinearLayout.LayoutParams(-1, dp(50));
        dashboardSpace.topMargin = dp(28);
        page.addView(dashboard, dashboardSpace);
        dashboard.setOnClickListener(v -> {
            menuOpen = false;
            showLauncher();
        });
        Button settings = button("⚙  Configurações");
        styleMenuOption(settings);
        LinearLayout.LayoutParams settingsSpace = new LinearLayout.LayoutParams(-1, dp(50));
        settingsSpace.topMargin = dp(12);
        page.addView(settings, settingsSpace);
        settings.setOnClickListener(v -> showSettings());
        Button tutorial = button("📖  Tutorial");
        styleMenuOption(tutorial);
        LinearLayout.LayoutParams tutorialSpace = new LinearLayout.LayoutParams(-1, dp(50));
        tutorialSpace.topMargin = dp(12);
        page.addView(tutorial, tutorialSpace);
        tutorial.setOnClickListener(v -> showTutorial());
    }

    private void closeMenu() {
        if (drawer == null) {
            menuOpen = false;
            return;
        }
        menuOpen = false;
        drawer.animate().translationX(-dp(310)).setDuration(180)
                .withEndAction(() -> {
                    if (drawer != null) {
                        root.removeView(drawer);
                        drawer = null;
                    }
                }).start();
    }

    private void showSettings() {
        closeMenu();
        clear();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(DARK_PAGE);
        page.setPadding(dp(25), dp(35), dp(25), dp(28));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        Button back = button("←");
        back.setBackgroundTintList(android.content.res.ColorStateList.valueOf(DARK_PAGE));
        back.setTextColor(TXT);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(52), dp(50));
        backParams.gravity = Gravity.LEFT;
        page.addView(back, backParams);
        back.setOnClickListener(v -> toggleMenu());
        TextView title = text("Configurações", 28, true);
        LinearLayout.LayoutParams titleSpace = new LinearLayout.LayoutParams(-1, -2);
        titleSpace.topMargin = dp(15);
        page.addView(title, titleSpace);
        TextView hint = text("Escolha o modo de vídeo usado ao conectar ao PC.", 14, false);
        hint.setTextColor(Color.rgb(182, 195, 218));
        page.addView(hint);

        CheckBox mjpeg = new CheckBox(this);
        mjpeg.setText("Usar MJPEG compatível");
        mjpeg.setTextColor(TXT);
        mjpeg.setTextSize(16);
        mjpeg.setChecked(getPreferences(MODE_PRIVATE).getBoolean("use_mjpeg", false));
        LinearLayout.LayoutParams mjpegSpace = new LinearLayout.LayoutParams(-1, dp(58));
        mjpegSpace.topMargin = dp(25);
        page.addView(mjpeg, mjpegSpace);
        TextView details = text("Desmarcado: H.264 nativo, recomendado para melhor qualidade e menor uso de rede.\n"
                + "Marcado: MJPEG, útil quando o FFmpeg ou o decoder H.264 não estiver disponível.", 13, false);
        details.setTextColor(Color.rgb(182, 195, 218));
        page.addView(details);
        Button apply = button("Aplicar");
        LinearLayout.LayoutParams applySpace = new LinearLayout.LayoutParams(-1, dp(56));
        applySpace.topMargin = dp(25);
        page.addView(apply, applySpace);
        apply.setOnClickListener(v -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("use_mjpeg", mjpeg.isChecked()).apply();
            apply.setText("Aplicado");
            apply.setEnabled(false);
            Toast.makeText(this, "Configuração aplicada.", Toast.LENGTH_SHORT).show();
        });
        mjpeg.setOnCheckedChangeListener((buttonView, isChecked) -> {
            apply.setText("Aplicar");
            apply.setEnabled(true);
        });
        apply.setEnabled(false);
        apply.setText("Aplicar");
    }

    private void showTutorial() {
        closeMenu();
        clear();
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(DARK_PAGE);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(DARK_PAGE);
        page.setPadding(dp(25), dp(35), dp(25), dp(28));
        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        Button back = button("←");
        back.setBackgroundTintList(android.content.res.ColorStateList.valueOf(DARK_PAGE));
        back.setTextColor(TXT);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(52), dp(50));
        backParams.gravity = Gravity.LEFT;
        page.addView(back, backParams);
        back.setOnClickListener(v -> toggleMenu());

        TextView title = text("Tutorial", 28, true);
        LinearLayout.LayoutParams titleSpace = new LinearLayout.LayoutParams(-1, -2);
        titleSpace.topMargin = dp(15);
        page.addView(title, titleSpace);

        TextView instructions = text(
                "1. Prepare o PC\n\n"
                        + "Abra o OpenMirror.exe no Windows e clique em Iniciar transmissão. "
                        + "O painel mostrará o endereço do PC e um PIN de 4 dígitos.\n\n"
                        + "2. Conecte o celular\n\n"
                        + "O celular e o PC devem estar na mesma rede local. Digite o endereço "
                        + "do PC e o PIN, ou use Procurar PC na rede.\n\n"
                        + "3. Escolha o vídeo\n\n"
                        + "H.264 nativo é o modo recomendado. Se o FFmpeg não estiver disponível "
                        + "ou houver incompatibilidade, abra Menu > Configurações e ative MJPEG compatível.\n\n"
                        + "4. Controle o Windows\n\n"
                        + "Toque para clicar, arraste para mover janelas, use dois dedos para rolar "
                        + "e mantenha pressionado para clicar com o botão direito. No vídeo, três dedos "
                        + "mostram ou escondem os controles.\n\n"
                        + "Dicas\n\n"
                        + "Use Wi-Fi 5 GHz para reduzir atrasos. Para USB, ative a depuração USB e "
                        + "use adb reverse conforme as instruções do repositório. Nunca exponha a porta "
                        + "do OpenMirror diretamente à internet.",
                14, false);
        instructions.setTextColor(Color.rgb(182, 195, 218));
        LinearLayout.LayoutParams instructionsSpace = new LinearLayout.LayoutParams(-1, -2);
        instructionsSpace.topMargin = dp(20);
        page.addView(instructions, instructionsSpace);

        TextView repository = text("Abrir documentação no GitHub", 14, false);
        repository.setTextColor(BLUE);
        repository.setPaintFlags(repository.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        repository.setPadding(0, dp(22), 0, dp(12));
        repository.setOnClickListener(v -> openRepository());
        page.addView(repository);
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
        root.addView(toolbar, new FrameLayout.LayoutParams(-1, dp(54)));
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
        FrameLayout.LayoutParams browserParams = new FrameLayout.LayoutParams(-1, -1);
        browserParams.topMargin = dp(54);
        root.addView(browser, browserParams);
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
