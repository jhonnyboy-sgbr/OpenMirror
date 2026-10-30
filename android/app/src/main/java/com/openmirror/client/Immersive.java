package com.openmirror.client;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * Tela cheia imersiva usada pela tela H.264 e pelo modo compatível (WebView).
 * Cada chamada é protegida: se o aparelho recusar algo de janela, o app NÃO cai; o erro vai para
 * o CrashLog como "capturado".
 */
final class Immersive {
    private Immersive() {}

    /** Esconde barra de status e de navegação (reaparecem por um instante ao deslizar da borda). */
    static void apply(Activity activity) {
        try {
            Window w = activity.getWindow();
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false);
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (RuntimeException e) {
            CrashLog.report("Immersive.apply", e, false);
        }
    }

    /** Volta as barras do sistema (tela inicial do app). */
    static void clear(Activity activity) {
        try {
            Window w = activity.getWindow();
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(true);
                WindowInsetsController c = w.getInsetsController();
                if (c != null) c.show(WindowInsets.Type.systemBars());
            } else {
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        } catch (RuntimeException e) {
            CrashLog.report("Immersive.clear", e, false);
        }
    }

    /** Estende o conteúdo por baixo do recorte da tela (notch/furo) em vez de deixar tarja preta. */
    static void useCutout(Window w) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                        ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }
        } catch (RuntimeException e) {
            CrashLog.report("Immersive.useCutout", e, false);
        }
    }
}
