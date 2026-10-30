package com.openmirror.client;

/**
 * Traduz os gestos (GestureEngine.Sink) e as teclas em mensagens JSON, respeitando o que o
 * servidor sabe fazer: com um servidor antigo (0.2.0) cai para o comportamento antigo em vez
 * de mandar comandos que seriam recusados.
 */
final class RemoteInput implements GestureEngine.Sink {
    private final ServerInfo server;
    private final OutboundQueue out;
    private int legacyScrollAcc = 0;

    RemoteInput(ServerInfo server, OutboundQueue out) { this.server = server; this.out = out; }

    boolean supportsDrag() { return server.has("drag"); }

    @Override public void move(float u, float v) { out.sendMove(InputMessages.move(u, v)); }

    @Override public void down(float u, float v, int button) {
        if (supportsDrag()) out.send(InputMessages.down(u, v, button));
        else out.sendMove(InputMessages.move(u, v));            // 0.2.0: só movia o cursor
    }

    @Override public void up(float u, float v, int button) {
        if (supportsDrag()) out.send(InputMessages.up(u, v, button));
        else out.send(InputMessages.move(u, v));
    }

    @Override public void tap(float u, float v) { out.send(InputMessages.tap(u, v)); }
    @Override public void rightClick(float u, float v) { out.send(InputMessages.right(u, v)); }

    @Override public void scroll(int dx, int dy) {
        if (server.has("wheel")) {
            out.send(InputMessages.scroll(dx, dy));
            return;
        }
        legacyScrollAcc += dy;                                   // 0.2.0 só entende "um clique"
        while (legacyScrollAcc >= 120) { out.send(InputMessages.scrollLegacy(true)); legacyScrollAcc -= 120; }
        while (legacyScrollAcc <= -120) { out.send(InputMessages.scrollLegacy(false)); legacyScrollAcc += 120; }
    }

    @Override public void releaseAll() {
        if (server.has("drag")) out.send(InputMessages.releaseAll());
    }

    void key(String name) { out.send(InputMessages.key(name)); }
    void text(String value) { out.send(InputMessages.text(value)); }

    /** Atalho com várias teclas. Retorna false se o servidor é antigo demais para isso. */
    boolean combo(String... keys) {
        if (!server.has("combo")) {
            if (keys.length == 1) { key(keys[0]); return true; }
            return false;
        }
        out.send(InputMessages.combo(keys));
        return true;
    }
}
