package com.openmirror.client;

import java.util.Locale;

/** Monta o JSON dos comandos de entrada enviados ao Windows. */
final class InputMessages {
    private InputMessages() {}

    static String num(float v) {
        float c = Math.max(0f, Math.min(1f, v));
        return String.format(Locale.ROOT, "%.5f", c);
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static String pos(String action, float u, float v) {
        return "{\"action\":\"" + action + "\",\"x\":" + num(u) + ",\"y\":" + num(v) + "}";
    }

    static String move(float u, float v) { return pos("move", u, v); }
    static String tap(float u, float v) { return pos("tap", u, v); }
    static String right(float u, float v) { return pos("right", u, v); }

    static String down(float u, float v, int button) {
        return "{\"action\":\"down\",\"button\":\"" + (button == GestureEngine.RIGHT ? "right" : "left")
                + "\",\"x\":" + num(u) + ",\"y\":" + num(v) + "}";
    }

    static String up(float u, float v, int button) {
        return "{\"action\":\"up\",\"button\":\"" + (button == GestureEngine.RIGHT ? "right" : "left")
                + "\",\"x\":" + num(u) + ",\"y\":" + num(v) + "}";
    }

    static String scroll(int dx, int dy) {
        return "{\"action\":\"scroll\",\"dx\":" + dx + ",\"dy\":" + dy + "}";
    }

    static String scrollLegacy(boolean up) {
        return "{\"action\":\"scroll\",\"direction\":\"" + (up ? "up" : "down") + "\"}";
    }

    static String key(String name) { return "{\"action\":\"key\",\"key\":" + esc(name) + "}"; }
    static String keyDown(String name) { return "{\"action\":\"keydown\",\"key\":" + esc(name) + "}"; }
    static String keyUp(String name) { return "{\"action\":\"keyup\",\"key\":" + esc(name) + "}"; }
    static String text(String value) { return "{\"action\":\"text\",\"text\":" + esc(value) + "}"; }
    static String releaseAll() { return "{\"action\":\"release_all\"}"; }

    static String combo(String... keys) {
        StringBuilder b = new StringBuilder("{\"action\":\"combo\",\"keys\":[");
        for (int i = 0; i < keys.length; i++) b.append(i == 0 ? "" : ",").append(esc(keys[i]));
        return b.append("]}").toString();
    }

    static String ping(long id, long tUs) {
        return "{\"action\":\"ping\",\"id\":" + id + ",\"t\":" + tUs + "}";
    }
}
