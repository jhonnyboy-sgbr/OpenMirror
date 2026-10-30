package com.openmirror.client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Registro de erros em arquivo, em Java puro (testável no JVM).
 *
 * - Erro FATAL (exceção que derrubaria o app): o handler global grava o erro e só então deixa
 *   o Android encerrar o processo. Na próxima abertura a MainActivity mostra o texto, com botão
 *   de copiar, em vez de o app "fechar mudo".
 * - Erro CAPTURADO (não fatal): trechos defensivos gravam a exceção e o app continua.
 * O arquivo guarda só os últimos MAX_BYTES; erros idênticos seguidos são agrupados.
 */
final class CrashLog {
    static final int MAX_BYTES = 12 * 1024;
    private static final long DEDUPE_MS = 30_000;

    private static File file;
    private static String info = "";
    private static boolean installed;
    private static String lastKey = "";
    private static long lastKeyAt;

    private CrashLog() {}

    /** Define a pasta do log e instala o handler global (uma vez). `deviceInfo`: versão/aparelho. */
    static synchronized void init(File dir, String deviceInfo) {
        file = new File(dir, "last_crash.txt");
        info = deviceInfo == null ? "" : deviceInfo;
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                report("thread " + (thread == null ? "?" : thread.getName()), error, true);
            } catch (Throwable ignored) {
                // gravar o log nunca pode impedir o encerramento normal
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static String format(String deviceInfo, String where, Throwable error, boolean fatal, long timeMs) {
        StringWriter trace = new StringWriter();
        if (error != null) error.printStackTrace(new PrintWriter(trace));
        return "=== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(timeMs))
                + (fatal ? " · ERRO FATAL (o app fechou)" : " · erro capturado (o app continuou)") + " ===\n"
                + (deviceInfo.isEmpty() ? "" : deviceInfo + "\n")
                + "Onde: " + where + "\n" + trace + "\n";
    }

    /** Mantém só o final do texto (o mais recente), sem cortar uma linha pela metade. */
    static String tail(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;
        String cut = new String(bytes, bytes.length - maxBytes, maxBytes, StandardCharsets.UTF_8);
        int newline = cut.indexOf('\n');
        return newline >= 0 ? cut.substring(newline + 1) : cut;
    }

    static synchronized void report(String where, Throwable error, boolean fatal) {
        if (file == null) return;
        String key = where + "|" + (error == null ? "" : error.getClass().getName() + ":" + error.getMessage());
        long now = System.currentTimeMillis();
        if (!fatal && key.equals(lastKey) && now - lastKeyAt < DEDUPE_MS) return;
        lastKey = key;
        lastKeyAt = now;
        try {
            String old = file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
            String updated = tail(old + format(info, where, error, fatal, now), MAX_BYTES);
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(updated.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();   // no crash fatal o processo morre logo depois
            }
        } catch (IOException ignored) {
            // sem espaço/permissão: nada a fazer
        }
    }

    /** Texto gravado, ou null se não há nada. */
    static synchronized String read() {
        if (file == null || !file.exists()) return null;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            return null;
        }
    }

    static synchronized void clear() {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        lastKey = "";
    }
}
