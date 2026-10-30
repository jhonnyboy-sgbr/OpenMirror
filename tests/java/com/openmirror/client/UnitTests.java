package com.openmirror.client;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Testes da lógica Android que não depende de android.* (roda no JVM; sem JUnit). */
public final class UnitTests {
    static int pass = 0, fail = 0;
    static Path fixtures;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name + ": " + detail); }
    }
    static void eq(String name, Object expected, Object actual) {
        check(name, expected == null ? actual == null : expected.equals(actual),
              "esperado <" + expected + "> obtido <" + actual + ">");
    }
    static void near(String name, double expected, double actual, double tol) {
        check(name, Math.abs(expected - actual) <= tol, "esperado " + expected + " ± " + tol + " obtido " + actual);
    }
    static String f(float v) { return String.format(Locale.ROOT, "%.4f", v); }

    // ------------------------------------------------------------------ falsos
    static final class FakeTimers implements GestureEngine.Timers {
        long now = 0;
        final List<Object[]> tasks = new ArrayList<>();
        public void post(Runnable r, long delayMs) { tasks.add(new Object[]{now + delayMs, r}); }
        public void cancel(Runnable r) { tasks.removeIf(t -> t[1] == r); }
        void advance(long ms) {
            now += ms;
            for (Object[] t : new ArrayList<>(tasks)) {
                if ((Long) t[0] <= now && tasks.remove(t)) ((Runnable) t[1]).run();
            }
        }
    }

    static final class RecSink implements GestureEngine.Sink {
        final List<String> log = new ArrayList<>();
        public void move(float u, float v) { log.add("move " + f(u) + " " + f(v)); }
        public void down(float u, float v, int b) { log.add("down " + f(u) + " " + f(v)); }
        public void up(float u, float v, int b) { log.add("up " + f(u) + " " + f(v)); }
        public void tap(float u, float v) { log.add("tap " + f(u) + " " + f(v)); }
        public void rightClick(float u, float v) { log.add("right " + f(u) + " " + f(v)); }
        public void scroll(int dx, int dy) { log.add("scroll " + dx + " " + dy); }
        public void releaseAll() { log.add("releaseAll"); }
        String last() { return log.isEmpty() ? "" : log.get(log.size() - 1); }
    }

    static final class Rig {
        final RecSink sink = new RecSink();
        final FakeTimers timers = new FakeTimers();
        final ViewportMapper map = new ViewportMapper();
        int viewportChanges = 0;
        final GestureEngine engine;
        long t = 0;

        Rig() {
            map.setVideo(1280, 720);
            map.setView(2400, 1080);                 // tela 20:9; vídeo 16:9 -> tarjas laterais
            engine = new GestureEngine(sink, () -> viewportChanges++, map, timers, 1f);
        }
        void touch(GestureEngine.Action a, int n, float x0, float y0, float x1, float y1, long dt) {
            t += dt; timers.advance(dt);
            engine.onTouch(a, n, x0, y0, x1, y1, t);
        }
        void down(float x, float y, long dt) { touch(GestureEngine.Action.DOWN, 1, x, y, x, y, dt); }
        void move(float x, float y, long dt) { touch(GestureEngine.Action.MOVE, 1, x, y, x, y, dt); }
        void up(float x, float y, long dt) { touch(GestureEngine.Action.UP, 1, x, y, x, y, dt); }
        void second(float x0, float y0, float x1, float y1, long dt) { touch(GestureEngine.Action.POINTER_DOWN, 2, x0, y0, x1, y1, dt); }
        void move2(float x0, float y0, float x1, float y1, long dt) { touch(GestureEngine.Action.MOVE, 2, x0, y0, x1, y1, dt); }
        void pointerUp(float x, float y, long dt) { touch(GestureEngine.Action.POINTER_UP, 1, x, y, x, y, dt); }
    }

    // --------------------------------------------------------------- viewport
    static void viewportTests() {
        ViewportMapper m = new ViewportMapper();
        float[] o = new float[2], r = new float[4];
        m.setVideo(1280, 720); m.setView(2400, 1080);
        m.rect(r);
        near("fit: largura", 1920, r[2], .01); near("fit: altura", 1080, r[3], .01);
        near("fit: tarja esquerda", 240, r[0], .01);
        check("fit: centro dentro", m.map(1200, 540, o), "");
        near("fit: centro u", .5, o[0], 1e-5); near("fit: centro v", .5, o[1], 1e-5);
        check("fit: toque na tarja preta é 'fora'", !m.map(100, 500, o), "");
        near("fit: tarja é limitada a u=0", 0, o[0], 1e-5);
        check("fit: canto superior esquerdo do vídeo", m.map(240, 0, o) && o[0] < 1e-5 && o[1] < 1e-5, "");

        m.setMode(ViewportMapper.Mode.FILL); m.rect(r);
        near("fill: largura", 2400, r[2], .01); near("fill: altura", 1350, r[3], .01);
        near("fill: topo cortado", -135, r[1], .01);
        check("fill: (0,0) da tela é v=0.1 do vídeo", m.map(0, 0, o) && Math.abs(o[1] - .1) < 1e-4, f(o[1]));

        m.setMode(ViewportMapper.Mode.STRETCH); m.rect(r);
        near("stretch: ocupa a tela toda (w)", 2400, r[2], .01); near("stretch: (h)", 1080, r[3], .01);
        m.map(600, 270, o);
        near("stretch: u proporcional", .25, o[0], 1e-5); near("stretch: v proporcional", .25, o[1], 1e-5);

        m.setMode(ViewportMapper.Mode.FIT);
        m.map(900, 300, o);
        float u0 = o[0], v0 = o[1];
        m.setZoomAround(2.5f, 900, 300);
        m.map(900, 300, o);
        near("zoom: ponto focal não anda (u)", u0, o[0], 1e-4); near("zoom: ponto focal não anda (v)", v0, o[1], 1e-4);
        check("zoom ligado", m.isZoomed() && Math.abs(m.zoom() - 2.5f) < 1e-5, "");
        m.setZoomAround(99f, 900, 300);
        near("zoom máximo", ViewportMapper.MAX_ZOOM, m.zoom(), 1e-5);
        m.setZoomAround(0.1f, 900, 300);
        near("zoom mínimo é 1x", 1, m.zoom(), 1e-5);
        m.setZoomAround(3f, 1200, 540);
        m.panBy(100000, 100000); m.rect(r);
        check("pan limitado: borda esquerda não passa de 0", r[0] <= 1e-3 && r[1] <= 1e-3, r[0] + "," + r[1]);
        m.panBy(-200000, -200000); m.rect(r);
        check("pan limitado: borda direita cobre a tela", r[0] + r[2] >= 2400 - 1e-2 && r[1] + r[3] >= 1080 - 1e-2, "");
        m.reset(); m.rect(r);
        near("reset volta ao ajuste", 240, r[0], .01);

        m.setView(1080, 2400);   // girou para retrato
        m.rect(r);
        near("retrato: largura", 1080, r[2], .01); near("retrato: altura", 607.5, r[3], .01);
        near("retrato: topo", (2400 - 607.5) / 2, r[1], .01);
        check("retrato: centro continua u=v=.5", m.map(540, 1200, o) && Math.abs(o[0] - .5) < 1e-5 && Math.abs(o[1] - .5) < 1e-5, "");
    }

    // ----------------------------------------------------------------- gestos
    static void gestureTests() {
        Rig g = new Rig();
        g.down(1200, 540, 0); g.up(1200, 540, 50);
        eq("toque = clique", "tap 0.5000 0.5000", g.sink.last());
        eq("toque: um único evento", 1, g.sink.log.size());

        g = new Rig();
        g.down(100, 500, 0); g.up(100, 500, 40);
        eq("toque na tarja preta é ignorado", 0, g.sink.log.size());

        g = new Rig();
        g.down(1200, 540, 0); g.up(1200, 540, 40); g.down(1200, 540, 80); g.up(1200, 540, 40);
        eq("duas batidas rápidas = dois cliques (o Windows faz o duplo clique)", 2, g.sink.log.size());

        g = new Rig();
        g.down(1200, 540, 0);
        g.timers.advance(0); g.t += 0;
        g.t += 600; g.timers.advance(600);
        eq("toque longo = clique direito", "right 0.5000 0.5000", g.sink.last());
        g.up(1200, 540, 10);
        eq("toque longo: soltar não gera clique", 1, g.sink.log.size());

        g = new Rig();
        g.down(840, 540, 0);
        g.move(843, 540, 10);
        eq("dentro da folga (slop): nada acontece", 0, g.sink.log.size());
        g.move(900, 540, 10);
        eq("arrasto: PRIMEIRO pressiona o botão onde o dedo tocou", "down 0.3125 0.5000", g.sink.log.get(0));
        eq("arrasto: depois move até o dedo atual", "move 0.3438 0.5000", g.sink.log.get(1));
        g.move(1200, 540, 10);
        g.up(1200, 540, 10);
        eq("arrasto: termina soltando o botão", "up 0.5000 0.5000", g.sink.last());
        check("arrasto: nenhum clique extra", !g.sink.log.contains("tap 0.3125 0.5000"), g.sink.log.toString());
        g.timers.advance(2000);
        eq("arrasto cancela o toque longo", 4, g.sink.log.size());

        g = new Rig();
        g.down(840, 540, 0); g.move(900, 540, 10); g.move(2390, 540, 10);
        eq("arrasto além da borda é limitado a u=1", "move 1.0000 0.5000", g.sink.last());

        g = new Rig();
        g.down(100, 500, 0); g.move(300, 500, 10); g.up(300, 500, 10);
        eq("arrasto começado na tarja preta não faz nada", 0, g.sink.log.size());

        g = new Rig();
        g.down(1000, 500, 0);
        g.second(1000, 500, 1100, 500, 10);
        g.move2(1000, 540, 1100, 540, 10);                 // decide: rolagem
        eq("rolagem: decidir não emite", 0, g.sink.log.size());
        g.move2(1000, 580, 1100, 580, 10);
        eq("rolagem: dedos descem -> roda para cima", "scroll 0 120", g.sink.last());
        g.move2(1000, 540, 1100, 540, 10);
        eq("rolagem: dedos sobem -> roda para baixo", "scroll 0 -120", g.sink.last());
        g.pointerUp(1000, 540, 10); g.up(1000, 540, 10);
        eq("rolagem: levantar os dedos não gera clique", 2, g.sink.log.size());

        g = new Rig();
        g.down(1000, 500, 0); g.second(1000, 500, 1100, 500, 10);
        g.move2(1000, 500, 1100, 500, 10);
        g.move2(1000, 520, 1100, 520, 10);                 // 20 px: abaixo do mínimo de 24 unidades? 20*3=60 -> envia
        check("rolagem fina também sai (60 unidades)", g.sink.log.isEmpty() || g.sink.last().startsWith("scroll 0 "), g.sink.log.toString());

        g = new Rig();
        g.down(1000, 500, 0); g.second(1000, 500, 1100, 500, 10);
        g.move2(900, 500, 1200, 500, 10);                  // abre os dedos: decide pinça
        g.move2(800, 500, 1300, 500, 10);
        check("pinça: aproxima a imagem", g.map.zoom() > 1.5f, "zoom=" + g.map.zoom());
        check("pinça: avisa a tela para redesenhar", g.viewportChanges >= 1, "");
        eq("pinça: não manda NADA ao PC", 0, g.sink.log.size());
        g.pointerUp(800, 500, 10); g.up(800, 500, 10);
        eq("pinça: soltar não gera clique", 0, g.sink.log.size());

        g = new Rig();
        g.down(1100, 500, 0); g.second(1100, 500, 1300, 500, 100);
        g.pointerUp(1100, 500, 100);
        eq("toque de 2 dedos = clique direito", "right 0.5000 0.4630", g.sink.last());
        g.up(1100, 500, 10);
        eq("toque de 2 dedos: o dedo que sobra não clica", 1, g.sink.log.size());

        g = new Rig();
        g.down(1100, 500, 0); g.second(1100, 500, 1300, 500, 10);
        g.pointerUp(1100, 500, 500);
        eq("2 dedos parados por muito tempo não clicam", 0, g.sink.log.size());

        g = new Rig();
        g.down(840, 540, 0); g.move(900, 540, 10);
        g.second(900, 540, 1000, 540, 10);
        eq("segundo dedo no meio do arrasto SOLTA o botão", "up 0.3438 0.5000", g.sink.last());
        g.pointerUp(900, 540, 10); g.up(900, 540, 10);
        eq("…não solta de novo e o dedo extra NÃO vira clique direito", 3, g.sink.log.size());

        g = new Rig();
        g.down(840, 540, 0); g.move(900, 540, 10);
        g.touch(GestureEngine.Action.CANCEL, 1, 900, 540, 900, 540, 10);
        eq("cancelamento (ex.: app minimizado) solta tudo", "releaseAll", g.sink.last());

        g = new Rig();
        g.down(1000, 500, 0); g.second(1000, 500, 1100, 500, 10);
        g.touch(GestureEngine.Action.POINTER_DOWN, 3, 1000, 500, 1100, 500, 10);
        g.move2(1000, 600, 1100, 600, 10);
        g.touch(GestureEngine.Action.UP, 1, 1000, 600, 1100, 600, 10);
        eq("3 dedos são ignorados", 0, g.sink.log.size());

        // ---- trackpad
        g = new Rig();
        g.engine.setPointerMode(GestureEngine.PointerMode.TRACKPAD);
        g.engine.setSensitivity(1.0f);
        g.down(500, 500, 0); g.move(740, 500, 10);
        eq("trackpad: 240 px de 2400 = 10% da tela", "move 0.6000 0.5000", g.sink.last());
        g.up(740, 500, 10);
        eq("trackpad: arrastar o dedo não clica", 1, g.sink.log.size());
        g.down(300, 300, 50); g.up(300, 300, 40);
        eq("trackpad: toque clica NO CURSOR (não onde o dedo tocou)", "tap 0.6000 0.5000", g.sink.last());
        g.down(300, 300, 50);
        g.t += 600; g.timers.advance(600);
        eq("trackpad: toque longo segura o botão", "down 0.6000 0.5000", g.sink.last());
        g.move(540, 300, 10);
        eq("trackpad: arrasta com o botão preso", "move 0.7000 0.5000", g.sink.last());
        g.up(540, 300, 10);
        eq("trackpad: soltar libera o botão", "up 0.7000 0.5000", g.sink.last());
        g.engine.setSensitivity(100f);
        near("sensibilidade tem limite", 6, g.engine.sensitivity(), 1e-6);
        g.down(100, 100, 10); g.move(2000, 100, 10);
        near("cursor nunca passa da borda", 1.0, g.engine.cursorU(), 1e-6);
    }

    // ------------------------------------------------------------ mensagens
    static void messageTests() throws Exception {
        Locale old = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("pt-BR"));          // vírgula decimal NÃO pode vazar para o JSON
        try {
            eq("número ignora a localidade do celular", "0.50000", InputMessages.num(.5f));
        } finally { Locale.setDefault(old); }
        eq("número é limitado a 0..1", "1.00000", InputMessages.num(7f));
        eq("número negativo vira 0", "0.00000", InputMessages.num(-3f));

        Map<String, Object> m = MiniJson.parseObject(InputMessages.down(.25f, .75f, GestureEngine.RIGHT));
        eq("down: ação", "down", m.get("action")); eq("down: botão", "right", m.get("button"));
        near("down: x", .25, ((Number) m.get("x")).doubleValue(), 1e-6);
        m = MiniJson.parseObject(InputMessages.scroll(-30, 120));
        eq("scroll: dy", 120L, m.get("dy")); eq("scroll: dx", -30L, m.get("dx"));
        m = MiniJson.parseObject(InputMessages.combo("ctrl", "shift", "esc"));
        eq("combo: teclas", "[ctrl, shift, esc]", String.valueOf(m.get("keys")));
        String nasty = "aspas \" barra \\ nova\nlinha tab\t ctrl\u0001 acentos olá 😀";
        m = MiniJson.parseObject(InputMessages.text(nasty));
        eq("texto com aspas, controles e emoji sobrevive ao JSON", nasty, m.get("text"));
        m = MiniJson.parseObject(InputMessages.ping(7, 123456789012L));
        eq("ping: id", 7L, m.get("id")); eq("ping: t", 123456789012L, m.get("t"));
        eq("release_all", "release_all", MiniJson.parseObject(InputMessages.releaseAll()).get("action"));
        eq("scroll legado", "up", MiniJson.parseObject(InputMessages.scrollLegacy(true)).get("direction"));
    }

    static void jsonTests() throws Exception {
        Map<String, Object> m = MiniJson.parseObject(" {\"a\": [1, 2.5, \"x\", true, null, {\"b\": -3}], \"s\": \"\\u00e1\\n\\\"\", \"e\": 1e3 } ");
        eq("lista", "[1, 2.5, x, true, null, {b=-3}]", String.valueOf(m.get("a")));
        eq("escape unicode", "á\n\"", m.get("s"));
        eq("expoente vira double", 1000.0, m.get("e"));
        for (String bad : new String[]{"", "[]", "{", "{\"a\":}", "{\"a\":1,}", "{\"a\" 1}", "{\"a\":\"x}", "{} extra", "{\"a\":\"\\q\"}", "nulo"}) {
            boolean threw = false;
            try { MiniJson.parseObject(bad); } catch (MiniJson.JsonException e) { threw = true; }
            check("JSON inválido recusado: " + bad, threw, "não lançou");
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) deep.append("[");
        boolean threw = false;
        try { MiniJson.parseObject("{\"x\":" + deep + "}"); } catch (MiniJson.JsonException e) { threw = true; }
        check("aninhamento absurdo é recusado (sem estourar a pilha)", threw, "");
    }

    // ---------------------------------------------------------------- fila
    static void queueTests() throws Exception {
        OutboundQueue.Mailbox box = new OutboundQueue.Mailbox();
        box.offer("m1", true); box.offer("m2", true); box.offer("click", false); box.offer("m3", true);
        eq("fila: 1º é o clique precedido do último movimento", "m2", box.take(10));
        eq("fila: depois o clique", "click", box.take(10));
        eq("fila: por fim o movimento mais novo", "m3", box.take(10));
        eq("fila: vazia -> null no prazo", null, box.take(5));
        eq("fila: m1 foi coalescido", 1L, box.coalesced);
        for (int i = 0; i < 2000; i++) box.offer("k" + i, false);
        check("fila: nunca cresce sem limite", box.size() <= 512, "tamanho " + box.size());
        box.close();
        eq("fila fechada devolve null", null, box.take(5));

        final List<String> got = new ArrayList<>();
        OutboundQueue q = new OutboundQueue(json -> {
            synchronized (got) { got.add(json); }
            if (json.equals("boom")) throw new IOException("rede caiu");
            return true;
        });
        q.start();
        q.send("a"); q.send("boom"); q.send("b"); q.sendMove("c");
        long end = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < end) { synchronized (got) { if (got.size() >= 4) break; } Thread.sleep(5); }
        q.stop();
        synchronized (got) { eq("pump: ordem preservada e sobrevive a falha de rede", "[a, boom, b, c]", got.toString()); }
        eq("pump: falha contada", 1L, q.failed);
    }

    static void remoteInputTests() {
        for (int legacy = 0; legacy < 2; legacy++) {
            final List<String> sent = new ArrayList<>();
            OutboundQueue q = new OutboundQueue(j -> true);
            OutboundQueue.Mailbox spy = new OutboundQueue.Mailbox();
            // usa a própria Mailbox para observar a ordem sem thread
            ServerInfo s = legacy == 1 ? ServerInfo.legacy() : modernServer();
            RemoteInput in = new RemoteInput(s, q);
            in.down(.1f, .1f, 0); in.up(.2f, .2f, 0); in.scroll(0, 60); in.scroll(0, 60);
            boolean ok = in.combo("alt", "tab");
            check((legacy == 1 ? "legado" : "moderno") + ": combo " + (legacy == 1 ? "recusado" : "aceito"), ok == (legacy == 0), "ok=" + ok);
            check((legacy == 1 ? "legado" : "moderno") + ": supportsDrag", in.supportsDrag() == (legacy == 0), "");
        }
        // verificação do conteúdo: transporte que registra
        final List<String> wire = new ArrayList<>();
        OutboundQueue q = new OutboundQueue(j -> { synchronized (wire) { wire.add(j); } return true; });
        q.start();
        RemoteInput legacy = new RemoteInput(ServerInfo.legacy(), q);
        legacy.down(.1f, .1f, 0); legacy.up(.2f, .2f, 0); legacy.scroll(0, 60); legacy.scroll(0, 60); legacy.releaseAll();
        sleep(150);
        q.stop();
        synchronized (wire) {
            String all = wire.toString();
            check("legado: down/up viram 'move' (não manda ação desconhecida)", !all.contains("\"action\":\"down\"") && !all.contains("\"action\":\"up\"") && !all.contains("release_all"), all);
            check("legado: 2x60 unidades de rolagem = 1 clique de roda", all.contains("\"direction\":\"up\""), all);
        }
        final List<String> wire2 = new ArrayList<>();
        OutboundQueue q2 = new OutboundQueue(j -> { synchronized (wire2) { wire2.add(j); } return true; });
        q2.start();
        RemoteInput modern = new RemoteInput(modernServer(), q2);
        modern.down(.1f, .1f, 0); modern.up(.2f, .2f, 0); modern.scroll(5, -30); modern.releaseAll(); modern.combo("win", "d");
        sleep(150);
        q2.stop();
        synchronized (wire2) {
            String all = wire2.toString();
            check("moderno: usa down/up/scroll/combo/release_all", all.contains("\"down\"") && all.contains("\"up\"") && all.contains("\"dy\":-30") && all.contains("release_all") && all.contains("combo"), all);
        }
    }

    static ServerInfo modernServer() {
        try {
            return ServerInfo.parse("{\"app\":\"OpenMirror\",\"version\":\"0.3.0\",\"video_protocols\":[2,3],"
                    + "\"capabilities\":[\"drag\",\"wheel\",\"combo\",\"ws-input\",\"timesync\"],\"h264_available\":true}");
        } catch (MiniJson.JsonException e) { throw new IllegalStateException(e); }
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    static void serverInfoTests() throws Exception {
        ServerInfo s = modernServer();
        eq("hello: versão", "0.3.0", s.version);
        eq("hello: melhor protocolo é o 3", 3, s.bestVideoProtocol(AppInfo.VIDEO_PROTOCOLS));
        eq("hello: app que só fala v2 também negocia", 2, s.bestVideoProtocol(new int[]{2}));
        eq("hello: sem protocolo em comum", -1, s.bestVideoProtocol(new int[]{9}));
        check("hello: mensagem de incompatibilidade orienta a atualizar",
              ServerInfo.describeIncompatibility(s, new int[]{9}).contains("Atualize"), "");
        eq("legado só fala v2", 2, ServerInfo.legacy().bestVideoProtocol(AppInfo.VIDEO_PROTOCOLS));
        check("legado não tem drag", !ServerInfo.legacy().has("drag"), "");
        boolean threw = false;
        try { ServerInfo.parse("{\"app\":\"Outro\"}"); } catch (MiniJson.JsonException e) { threw = true; }
        check("hello de outro programa é recusado", threw, "");
    }

    // ------------------------------------------------------- H.264 e filas
    static void h264Tests() throws Exception {
        byte[] key = {0, 0, 0, 1, 9, (byte) 0xf0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 0, 0, 1, 0x65, 3};
        byte[] p = {0, 0, 1, 9, (byte) 0xf0, 0, 0, 1, 0x41, 0x11};
        check("IDR detectado", H264.isKeyframe(key), "");
        check("quadro P não é chave", !H264.isKeyframe(p), "");
        byte[][] sets = H264.parameterSets(key);
        check("SPS achado com start code", sets[0] != null && sets[0].length == 5 && sets[0][3] == 0x67, sets[0] == null ? "null" : String.valueOf(sets[0].length));
        check("PPS achado com start code", sets[1] != null && sets[1].length >= 4 && sets[1][3] == 0x68, "");
        check("P não tem SPS/PPS", H264.parameterSets(p)[0] == null && H264.parameterSets(p)[1] == null, "");
        eq("tipos de NAL", "[9, 7, 8, 5]", H264.nalTypes(key).toString());
        check("lixo/curto não explode", !H264.isKeyframe(new byte[0]) && !H264.isKeyframe(new byte[]{0, 0}), "");

        if (fixtures != null && Files.exists(fixtures.resolve("au_key.bin"))) {
            byte[] realKey = Files.readAllBytes(fixtures.resolve("au_key.bin"));
            byte[] realP = Files.readAllBytes(fixtures.resolve("au_p.bin"));
            String expectedKey = new String(Files.readAllBytes(fixtures.resolve("au_key.nals")), "UTF-8").trim();
            String expectedP = new String(Files.readAllBytes(fixtures.resolve("au_p.nals")), "UTF-8").trim();
            eq("FFmpeg real: NALs da AU-chave batem com o Python", expectedKey, H264.nalTypes(realKey).toString());
            eq("FFmpeg real: NALs da AU-P batem com o Python", expectedP, H264.nalTypes(realP).toString());
            byte[][] rs = H264.parameterSets(realKey);
            check("FFmpeg real: SPS+PPS extraídos para o MediaCodec", rs[0] != null && rs[1] != null && rs[0].length > 4, "");
            check("FFmpeg real: IDR/P classificados", H264.isKeyframe(realKey) && !H264.isKeyframe(realP), "");
        } else {
            System.out.println("SKIP fixtures de FFmpeg real não geradas");
        }

        PacketQueue q = new PacketQueue(2);
        q.put(new PacketQueue.Packet(key, true, 0, 0, 0));
        q.put(new PacketQueue.Packet(p, false, 1, 0, 0));
        q.put(new PacketQueue.Packet(p, false, 2, 0, 0));
        check("fila de pacotes: estouro descarta e espera IDR", q.size() == 0 && q.isWaitingKey(), "size=" + q.size());
        q.put(new PacketQueue.Packet(p, false, 3, 0, 0));
        eq("fila de pacotes: P é ignorado enquanto espera IDR", 0, q.size());
        q.put(new PacketQueue.Packet(key, true, 4, 0, 0));
        eq("fila de pacotes: IDR recomeça", 1, q.size());
        eq("fila de pacotes: contagem de descartes", 4L, q.dropped());
        PacketQueue.Packet got = q.poll(10);
        check("fila de pacotes: entrega o IDR", got != null && got.key && got.seq == 4, "");
        eq("fila de pacotes: vazia -> null", null, q.poll(5));
        q.close();
        eq("fila fechada -> null", null, q.poll(5));
    }

    static void statsTests() {
        Stats.Rolling r = new Stats.Rolling(100);
        for (int i = 1; i <= 100; i++) r.add(i);
        near("média", 50.5, r.avg(), 1e-9); near("p95", 95, r.p95(), 1e-9); near("máx", 100, r.max(), 1e-9);
        check("vazio dá NaN", Double.isNaN(new Stats.Rolling(5).avg()), "");
        Stats.Rolling small = new Stats.Rolling(3);
        for (int i = 1; i <= 10; i++) small.add(i);
        near("janela circular guarda só as últimas", 9.0, small.avg(), 1e-9);

        Stats.Rate rate = new Stats.Rate(2000);
        for (int i = 0; i <= 60; i++) rate.tick(i * 1000L / 30, 1);   // 30 eventos/s
        near("taxa de eventos", 30, rate.perSecond(2000), 1.0);
        eq("taxa expira sem eventos", 0.0, rate.perSecond(60_000));

        // relógio do servidor 5 s à frente; RTT simétrico de 10 ms
        ClockSync c = new ClockSync();
        check("sem amostras não está pronto", !c.ready(), "");
        long off = 5_000_000L;
        long t0 = 1_000_000;
        c.addSample(t0, t0 + 5_000 + off, t0 + 10_000);
        near("offset do relógio (RTT simétrico)", off, c.offsetUs(), 1);
        // amostra ruim (RTT 200 ms, assimétrico) não pode estragar a boa
        c.addSample(2_000_000, 2_000_000 + 180_000 + off, 2_200_000);
        near("fica com a amostra de MENOR RTT", off, c.offsetUs(), 1);
        near("RTT da melhor amostra", 10_000, c.bestRttUs(), 1);
        near("último RTT", 200_000, c.lastRttUs(), 1);
        near("idade estimada do quadro = agora - (ts_servidor - offset)", 12_000,
             3_012_000 - c.serverToLocalUs(3_000_000 + off), 1);
        c.addSample(5, 5, 1);    // relógio andando para trás: descartado
        near("amostra inválida é ignorada", 10_000, c.bestRttUs(), 1);
    }

    // --------------------------------------------------------- descoberta
    static void discoveryTests() throws Exception {
        InetAddress b = DiscoveryClient.broadcastFromDhcp(0x1401A8C0, 0x00FFFFFF);   // 192.168.1.20 /24
        eq("broadcast da sub-rede", "192.168.1.255", b.getHostAddress());
        b = DiscoveryClient.broadcastFromDhcp(0x0A02A8C0 /*192.168.2.10*/, 0x0000FFFF /*255.255.0.0*/);
        eq("broadcast com máscara /16", "192.168.255.255", b.getHostAddress());
        DiscoveryClient.Found f = DiscoveryClient.parseReply("10.0.0.7", "{\"app\":\"OpenMirror\",\"name\":\"PC\",\"port\":8765,\"version\":\"0.3.0\"}");
        check("resposta válida", f != null && f.address().equals("10.0.0.7:8765") && f.name.equals("PC"), "");
        check("porta inválida recusada", DiscoveryClient.parseReply("x", "{\"app\":\"OpenMirror\",\"port\":0}") == null, "");
        check("outro app recusado", DiscoveryClient.parseReply("x", "{\"app\":\"X\",\"port\":80}") == null, "");
        check("lixo recusado", DiscoveryClient.parseReply("x", "\u0000\u0001") == null, "");

        if (fixtures != null && Files.exists(fixtures.resolve("hello.json"))) {
            ServerInfo s = ServerInfo.parse(new String(Files.readAllBytes(fixtures.resolve("hello.json")), "UTF-8"));
            check("hello REAL do servidor Python é entendido", s.bestVideoProtocol(AppInfo.VIDEO_PROTOCOLS) == 3 && s.has("drag") && s.has("timesync"), s.capabilities.toString());
            DiscoveryClient.Found real = DiscoveryClient.parseReply("192.168.0.9", new String(Files.readAllBytes(fixtures.resolve("discovery.json")), "UTF-8"));
            check("resposta de descoberta REAL do servidor Python", real != null && real.port == 8765 && real.name.equals("PC-DO-JOÃO"), String.valueOf(real));
            Map<String, Object> pong = MiniJson.parseObject(new String(Files.readAllBytes(fixtures.resolve("pong.json")), "UTF-8"));
            eq("pong REAL: id", 7L, pong.get("id")); eq("pong REAL: t", 123456789012L, pong.get("t"));
            check("pong REAL: tem o relógio do servidor", pong.get("s") instanceof Long, "");
        }
    }


    // -------------------------------------------------------------- registro de erros
    static int count(String text, String part) {
        int n = 0, i = 0;
        while ((i = text.indexOf(part, i)) >= 0) { n++; i += part.length(); }
        return n;
    }

    static void crashLogTests() throws Exception {
        Path dir = Files.createTempDirectory("om_crash");
        final List<Throwable> previousSaw = new ArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> previousSaw.add(e));   // o "handler do Android"
        CrashLog.init(dir.toFile(), "OpenMirror 0.3.1 · Aparelho de teste");
        eq("registro: vazio no começo", null, CrashLog.read());

        CrashLog.report("onde-1", new IllegalStateException("falha-1"), false);
        String text = CrashLog.read();
        check("registro: guarda onde, tipo, mensagem, aparelho e que o app continuou",
              text != null && text.contains("onde-1") && text.contains("IllegalStateException")
              && text.contains("falha-1") && text.contains("erro capturado") && text.contains("Aparelho de teste"), String.valueOf(text));
        check("registro: inclui a pilha de chamadas", text != null && text.contains("at com.openmirror.client.UnitTests"), "");

        CrashLog.report("onde-1", new IllegalStateException("falha-1"), false);
        eq("registro: erro idêntico em sequência é agrupado", 1, count(CrashLog.read(), "Onde: onde-1"));

        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), new RuntimeException("explodiu"));
        text = CrashLog.read();
        check("registro: handler global grava o erro FATAL", text.contains("ERRO FATAL") && text.contains("explodiu"), text);
        check("registro: handler global ainda repassa ao handler anterior (o app fecha como sempre)",
              previousSaw.size() == 1 && "explodiu".equals(previousSaw.get(0).getMessage()), String.valueOf(previousSaw));

        for (int i = 0; i < 200; i++) CrashLog.report("onde-" + (100 + i), new RuntimeException("erro " + i), false);
        text = CrashLog.read();
        check("registro: nunca passa do limite de bytes", text.getBytes("UTF-8").length <= CrashLog.MAX_BYTES, "bytes=" + text.getBytes("UTF-8").length);
        check("registro: mantém o MAIS RECENTE e descarta o antigo", text.contains("onde-299") && !text.contains("Onde: onde-1\n"), "");
        check("registro: primeira linha não fica cortada pela metade", text.startsWith("=== ") || text.startsWith("Onde:") || text.contains("\n"), text.substring(0, Math.min(60, text.length())));

        eq("tail: corta na fronteira de linha", "c", CrashLog.tail("a\nb\nc", 3));
        eq("tail: texto curto fica igual", "abc", CrashLog.tail("abc", 100));
        CrashLog.clear();
        eq("registro: clear apaga", null, CrashLog.read());
        CrashLog.report("depois-do-clear", new RuntimeException("x"), false);
        check("registro: volta a gravar depois do clear", CrashLog.read() != null, "");
        CrashLog.clear();
    }

    public static void main(String[] args) throws Exception {
        fixtures = args.length > 0 ? Paths.get(args[0]) : null;
        viewportTests();
        gestureTests();
        messageTests();
        jsonTests();
        queueTests();
        remoteInputTests();
        serverInfoTests();
        h264Tests();
        statsTests();
        discoveryTests();
        crashLogTests();
        System.out.println("TOTAL pass=" + pass + " fail=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
