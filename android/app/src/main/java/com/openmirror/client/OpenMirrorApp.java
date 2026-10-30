package com.openmirror.client;

import android.app.Application;
import android.os.Build;

/** Instala o registro de erros antes de qualquer tela abrir. */
public class OpenMirrorApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        CrashLog.init(getFilesDir(), "OpenMirror " + AppInfo.VERSION + " · " + Build.MANUFACTURER + " "
                + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
    }
}
