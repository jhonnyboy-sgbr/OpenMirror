package com.openmirror.client;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Uma sessão com o PC: descobre a versão do servidor (/api/hello), pareia, abre o canal de
 * entrada (WebSocket, com HTTP de reserva), recebe o vídeo H.264 e decodifica com MediaCodec.
 * Se qualquer coisa cair (rede, decoder, servidor reiniciou) ela reconecta sozinha com espera
 * crescente, até a tela ser fechada. Estrutura de threads:
 *   Session  : negociação + laço do decoder      Reader: lê pacotes da rede -> PacketQueue
 *   Stats    : 1x/s (ping, status do PC, indicadores)    Input: OutboundQueue -> WS/HTTP
 */
final class StreamSession {
    interface Callbacks {
        void onStatus(String text);
        void onVideoSize(int w, int h);
        void onReady(ServerInfo info, RemoteInput input);
        void onStats(Snapshot s);
        /** Erro que repetir não resolve (PIN errado, versão incompatível). */
        void onFatal(String message);
    }

    /** Fotografia dos indicadores. NaN = ainda sem medição. */
    static final class Snapshot {
        int proto, width, height;
        String transport = "—", codec = "—", encoderActive = "—";
        double decodeFps, renderFps, decodeMsAvg = Double.NaN, decodeMsP95 = Double.NaN,
               queueWaitMsAvg = Double.NaN, ageMsAvg = Double.NaN, rttMs = Double.NaN, bitrateKbps;
        long lostBySeq, droppedByClient;
        int queueDepth;
        double serverEncodeFps = Double.NaN, serverSentFps = Double.NaN, serverBitrateKbps = Double.NaN;
        long serverDropped = -1;
    }

    private static final class FatalException extends Exception {
        private static final long serialVersionUID = 1L;
        FatalException(String m) { super(m); }
    }

    private static final int MAX_PACKET = 4 * 1024 * 1024;
    private static final int CLIENT_BACKLOG = 30;
    private static final long V3_META = 13;

    private final String host, pin, base;
    private final int port;
    private final Callbacks cb;

    private volatile boolean stopped = true;
    private volatile String token;
    private boolean started;
    private volatile Surface surface;
    private volatile HttpURLConnection activeStream;
    private volatile WsClient ws;
    private volatile OutboundQueue outbound;
    private volatile ServerInfo server;
    private volatile String lastError = "";
    private volatile String lastInputError = "";
    private volatile String serverStatusNote = "";
    private volatile boolean streamedFrames;
    private Thread thread;

    // indicadores (escritos pelo decoder/reader, lidos pela thread de stats)
    private final Stats.Rolling decodeMs = new Stats.Rolling(120);
    private final Stats.Rolling queueWaitMs = new Stats.Rolling(120);
    private final Stats.Rolling ageMs = new Stats.Rolling(120);
    private final Stats.Rate decodedRate = new Stats.Rate(2000);
    private final Stats.Rate renderedRate = new Stats.Rate(2000);
    private final Stats.Rate byteRate = new Stats.Rate(2000);
    private final ClockSync clock = new ClockSync();
    private final AtomicLong lostBySeq = new AtomicLong();
    private final AtomicLong pingId = new AtomicLong();
    private volatile PacketQueue queue;
    private volatile int proto, videoW, videoH;
    private volatile String codecName = "—";
    private volatile Snapshot lastServerStatus = new Snapshot();
    private volatile String readerError;
    private volatile boolean readerDone;

    /** `initialToken`: sessão de pareamento reaproveitada de uma instância anterior (ou null). */
    StreamSession(String hostPort, String pin, Callbacks cb, String initialToken) {
        this.pin = pin; this.cb = cb; this.token = initialToken;
        String[] parts = hostPort.split(":");
        this.host = parts[0];
        this.port = parts.length > 1 ? Integer.parseInt(parts[1]) : 8765;
        this.base = "http://" + host + ":" + port;
    }

    // ------------------------------------------------------------------ ciclo
    /** Uso único: depois de stop() cria-se OUTRA instância (uma thread antiga que demore a morrer
     *  nunca "ressuscita", porque a instância dela continua parada para sempre). */
    synchronized void start(Surface s) {
        if (started) return;
        started = true;
        surface = s;
        stopped = false;
        thread = new Thread(this::run, "OpenMirror-Session");
        thread.start();
    }

    String token() { return token; }

    /**
     * Para tudo e SÓ devolve depois de o decoder ter largado a Surface (exigência do
     * surfaceDestroyed). Pode ser chamado na thread principal: não escreve na rede.
     */
    void stop() {
        stopped = true;
        HttpURLConnection c = activeStream;
        if (c != null) c.disconnect();
        WsClient w = ws;
        if (w != null) w.abort();
        OutboundQueue q = outbound;
        if (q != null) q.stop();
        PacketQueue pq = queue;
        if (pq != null) pq.close();
        Thread t;
        synchronized (this) { t = thread; thread = null; }
        if (t != null) {
            t.interrupt();
            try { t.join(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ------------------------------------------------------------ laço principal
    private void run() {
        int attempt = 0;
        while (!stopped) {
            streamedFrames = false;
            try {
                connectAndStream();
                lastError = "o PC encerrou a transmissão";
            } catch (FatalException e) {
                lastError = e.getMessage();
                if (!stopped) cb.onFatal(e.getMessage());
                return;
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {          // inclusive Error: nada pode derrubar o app a partir daqui
                if (stopped) return;
                if (!(e instanceof java.io.IOException)) CrashLog.report("StreamSession.run", e, false);
                lastError = describe(e);
            }
            if (stopped) return;
            attempt = streamedFrames ? 1 : attempt + 1;
            long wait = Math.min(8000L, 1000L << Math.min(attempt - 1, 3));
            cb.onStatus("Reconectando em " + (wait / 1000) + " s… (" + lastError + ")");
            try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
        }
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.UnknownHostException || e instanceof java.net.NoRouteToHostException) {
            return "sem resposta do PC (" + (m == null ? e.getClass().getSimpleName() : m) + ")";
        }
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private void connectAndStream() throws Exception {
        cb.onStatus("Procurando o servidor…");
        ServerInfo info = fetchHello();
        server = info;
        int chosen = info.bestVideoProtocol(AppInfo.VIDEO_PROTOCOLS);
        if (chosen < 0) throw new FatalException(ServerInfo.describeIncompatibility(info, AppInfo.VIDEO_PROTOCOLS));
        if (!info.h264Available) {
            throw new FatalException("O PC não encontrou o FFmpeg, então não há H.264. "
                    + "Coloque o ffmpeg.exe em windows\\tools\\ ou use o modo compatível (MJPEG).");
        }
        if (token == null) {
            cb.onStatus("Pareando com o PC…");
            token = pair();
        }
        proto = chosen;
        resetCounters();

        OutboundQueue q = new OutboundQueue(new InputTransport());
        outbound = q;
        q.start();
        ensureWs(info);
        cb.onReady(info, new RemoteInput(info, q));

        Thread statsThread = null;
        try {
            statsThread = new Thread(() -> statsLoop(info), "OpenMirror-Stats");
            statsThread.setDaemon(true);
            statsThread.start();
            cb.onStatus("Iniciando H.264 (protocolo v" + chosen + ")…");
            streamVideo(chosen);
        } finally {
            if (statsThread != null) statsThread.interrupt();
            q.stop();
            WsClient w = ws;
            if (w != null) w.abort();
            ws = null;
        }
    }

    private void resetCounters() {
        decodeMs.clear(); queueWaitMs.clear(); ageMs.clear();
        lostBySeq.set(0);
        readerError = null; readerDone = false;
        lastInputError = "";
    }

    // ------------------------------------------------------------------- HTTP
    private HttpURLConnection open(String path, String method, int connectMs, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) java.net.URI.create(base + path).toURL().openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Cache-Control", "no-store");
        String t = token;
        if (t != null) c.setRequestProperty("X-OpenMirror-Session", t);
        return c;
    }

    private static String readText(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < 4096) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private static void drain(InputStream in) {
        if (in == null) return;
        try {
            byte[] buf = new byte[512];
            while (in.read(buf) >= 0) { /* só esvazia: permite reaproveitar a conexão (keep-alive) */ }
        } catch (IOException ignored) { }
    }

    private ServerInfo fetchHello() throws Exception {
        HttpURLConnection c = open("/api/hello", "GET", 4000, 4000);
        try {
            int code = c.getResponseCode();
            if (code == 404) { drain(c.getErrorStream()); return ServerInfo.legacy(); }
            if (code != 200) throw new IOException("O PC respondeu HTTP " + code);
            try {
                return ServerInfo.parse(readText(c.getInputStream()));
            } catch (MiniJson.JsonException e) {
                throw new FatalException("Esse endereço não é um servidor OpenMirror (" + e.getMessage() + ").");
            }
        } finally { c.disconnect(); }
    }

    private String pair() throws Exception {
        HttpURLConnection c = open("/api/pair", "POST", 4000, 6000);
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(("{\"pin\":" + InputMessages.esc(pin) + "}").getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code == 401) throw new FatalException("Código incorreto. Confira os 4 dígitos no painel do Windows.");
            if (code == 429) throw new FatalException("Muitas tentativas de pareamento. Aguarde 1 minuto.");
            if (code != 200) throw new IOException("Pareamento falhou (HTTP " + code + ")");
            Map<String, Object> m = MiniJson.parseObject(readText(c.getInputStream()));
            String session = MiniJson.str(m, "session", "");
            if (session.isEmpty()) throw new IOException("O PC não devolveu a sessão");
            return session;
        } finally { c.disconnect(); }
    }

    private boolean postInput(String json) throws IOException {
        HttpURLConnection c = open("/api/input", "POST", 1500, 1500);   // sem disconnect(): reaproveita a conexão
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        drain(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code == 401) token = null;     // sessão expirou: o próximo ciclo pareia de novo
        return code == 200;
    }

    /** Entrada: WebSocket quando aberto; senão HTTP (nunca fica sem entrada só porque o WS caiu). */
    private final class InputTransport implements OutboundQueue.Transport {
        @Override public boolean send(String json) throws IOException {
            WsClient w = ws;
            if (w != null && w.isOpen() && w.sendText(json)) return true;
            return postInput(json);
        }
    }

    private void ensureWs(ServerInfo info) {
        if (!info.has("ws-input") || stopped) return;
        WsClient current = ws;
        if (current != null && current.isOpen()) return;
        String t = token;
        if (t == null) return;
        Map<String, String> headers = new HashMap<>();
        headers.put("X-OpenMirror-Session", t);     // sessão no header, não na URL
        WsClient fresh = new WsClient(host, port, "/ws/input", headers, new WsClient.Listener() {
            @Override public void onText(String text) { onWsText(text); }
            @Override public void onClosed(String reason) { /* o laço de stats reabre */ }
        });
        try {
            fresh.connect(2500);
            ws = fresh;
        } catch (IOException | RuntimeException e) {
            ws = null;                                // continua por HTTP
        }
    }

    private void onWsText(String text) {
        try {
            Map<String, Object> m = MiniJson.parseObject(text);
            String type = MiniJson.str(m, "type", "");
            if (type.equals("pong")) {
                long t0 = MiniJson.num(m, "t", -1), s = MiniJson.num(m, "s", -1);
                if (t0 >= 0 && s >= 0) clock.addSample(t0, s, nowUs());
            } else if (type.equals("error")) {
                lastInputError = MiniJson.str(m, "message", "");
            }
        } catch (MiniJson.JsonException ignored) { }
    }

    private static long nowUs() { return System.nanoTime() / 1000L; }

    // ------------------------------------------------------------------ vídeo
    private void streamVideo(int protocol) throws Exception {
        String path = "/stream/h264" + (protocol == 3 ? "?proto=3" : "");
        HttpURLConnection stream = open(path, "GET", 5000, 12000);
        activeStream = stream;
        MediaCodec codec = null;
        Thread reader = null;
        try {
            int code = stream.getResponseCode();
            if (code == 401) { token = null; throw new IOException("Sessão expirada: pareando de novo"); }
            if (code != 200) {
                String err = readText(stream.getErrorStream());
                throw new IOException("Vídeo indisponível (HTTP " + code + ")" + (err.isEmpty() ? "" : ": " + err));
            }
            DataInputStream in = new DataInputStream(new BufferedInputStream(stream.getInputStream(), 64 * 1024));
            byte[] magic = new byte[4];
            in.readFully(magic);
            String m = new String(magic, StandardCharsets.US_ASCII);
            if (!m.equals(protocol == 3 ? "OMH3" : "OMH2")) throw new IOException("Protocolo de vídeo incompatível (" + m + ")");
            int width = in.readUnsignedShort(), height = in.readUnsignedShort();
            if (protocol == 3) { in.readUnsignedByte(); in.readUnsignedByte(); }   // fps alvo, reservado
            if (width <= 0 || height <= 0 || width > 3840 || height > 2160) throw new IOException("Dimensões de vídeo inválidas");
            videoW = width; videoH = height;
            cb.onVideoSize(width, height);

            PacketQueue q = new PacketQueue(CLIENT_BACKLOG);
            queue = q;
            readerError = null; readerDone = false;
            final DataInputStream source = in;
            reader = new Thread(() -> readPackets(source, q, protocol), "OpenMirror-Reader");
            reader.start();

            PacketQueue.Packet first = null;
            long giveUp = System.nanoTime() + 10_000_000_000L;
            while (first == null) {
                if (stopped) return;
                first = q.poll(200);
                if (first == null && (readerDone || System.nanoTime() > giveUp)) {
                    throw new IOException(readerError != null ? readerError : "O PC não enviou o primeiro quadro");
                }
            }
            byte[][] sets = H264.parameterSets(first.data);
            if (sets[0] == null || sets[1] == null) throw new IOException("Quadro inicial sem SPS/PPS: confira o encoder H.264 do PC");
            Surface target = surface;
            if (target == null || !target.isValid()) throw new IOException("Superfície de vídeo indisponível");

            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sets[0]));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(sets[1]));
            if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            if (Build.VERSION.SDK_INT >= 23) format.setInteger(MediaFormat.KEY_PRIORITY, 0);   // tempo real
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(format, target, null, 0);
            codec.start();
            codecName = codec.getName();
            cb.onStatus("");
            decodeLoop(codec, q, first);
        } finally {
            activeStream = null;
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                try { codec.release(); } catch (Exception ignored) { }
            }
            PacketQueue q = queue;
            if (q != null) q.close();
            stream.disconnect();
            if (reader != null) reader.interrupt();
        }
    }

    private void readPackets(DataInputStream in, PacketQueue q, int protocol) {
        long lastSeq = -1;
        try {
            while (!stopped) {
                int length = in.readInt();
                long seq = -1, ts = 0;
                boolean key;
                byte[] data;
                if (protocol == 3) {
                    if (length <= V3_META || length > MAX_PACKET + V3_META) throw new IOException("Tamanho de pacote inválido: " + length);
                    int flags = in.readUnsignedByte();
                    seq = in.readInt() & 0xffffffffL;
                    ts = in.readLong();
                    data = new byte[(int) (length - V3_META)];
                    in.readFully(data);
                    key = (flags & 1) != 0;
                    if (lastSeq >= 0 && seq != ((lastSeq + 1) & 0xffffffffL)) {
                        long gap = (seq - lastSeq - 1) & 0xffffffffL;
                        if (gap > 0 && gap < 1_000_000L) lostBySeq.addAndGet(gap);
                    }
                    lastSeq = seq;
                } else {
                    if (length <= 0 || length > MAX_PACKET) throw new IOException("Tamanho de quadro inválido: " + length);
                    data = new byte[length];
                    in.readFully(data);
                    key = H264.isKeyframe(data);
                }
                long nowMs = System.nanoTime() / 1_000_000L;
                byteRate.tick(nowMs, length + 4L);
                q.put(new PacketQueue.Packet(data, key, seq, ts, System.nanoTime()));
            }
        } catch (IOException e) {
            if (!stopped) readerError = "Conexão de vídeo interrompida (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")";
        } catch (Throwable t) {
            CrashLog.report("StreamSession.readPackets", t, false);
            readerError = "Falha ao ler o vídeo: " + t;
        } finally {
            readerDone = true;
        }
    }

    private void decodeLoop(MediaCodec codec, PacketQueue q, PacketQueue.Packet first) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long[] queuedAtNs = new long[256];
        long counter = 0, decoded = 0;
        PacketQueue.Packet pending = first;
        while (!stopped) {
            if (pending == null) {
                long inFlight = counter - decoded;
                pending = q.poll(inFlight > 0 ? 1 : 8);            // 1 ms enquanto há quadro no decoder
                if (pending == null && readerDone && q.size() == 0 && inFlight == 0) {
                    throw new IOException(readerError != null ? readerError : "O PC encerrou o vídeo");
                }
                if (pending != null) queueWaitMs.add((System.nanoTime() - pending.recvNanos) / 1e6);
            }
            if (pending != null) {
                int slot = codec.dequeueInputBuffer(2000);
                if (slot >= 0) {
                    ByteBuffer dest = codec.getInputBuffer(slot);
                    if (dest == null || pending.data.length > dest.capacity()) throw new IOException("Quadro maior que o buffer do decoder");
                    dest.clear();
                    dest.put(pending.data);
                    queuedAtNs[(int) (counter & 255)] = System.nanoTime();
                    if (pending.serverTsUs != 0 && clock.ready()) {
                        double age = (nowUs() - clock.serverToLocalUs(pending.serverTsUs)) / 1000.0;
                        ageMs.add(Math.max(0.0, age));
                    }
                    codec.queueInputBuffer(slot, 0, pending.data.length, counter * 1000L, 0);
                    counter++;
                    pending = null;
                }
            }
            while (true) {
                int index = codec.dequeueOutputBuffer(info, 0);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
                if (index < 0) break;
                long n = info.presentationTimeUs / 1000L;
                decodeMs.add((System.nanoTime() - queuedAtNs[(int) (n & 255)]) / 1e6);
                // Se ainda há quadros esperando, este já está velho: não desenha, só avança.
                boolean render = q.size() == 0 && pending == null;
                codec.releaseOutputBuffer(index, render);
                long nowMs = System.nanoTime() / 1_000_000L;
                decoded++;
                decodedRate.tick(nowMs, 1);
                if (render) renderedRate.tick(nowMs, 1);
                streamedFrames = true;
            }
        }
    }

    // -------------------------------------------------------------- indicadores
    private void statsLoop(ServerInfo info) {
        int tick = 0;
        while (!stopped) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            if (stopped) return;
            try {
                ensureWs(info);
                WsClient w = ws;
                if (w != null && w.isOpen()) w.sendText(InputMessages.ping(pingId.incrementAndGet(), nowUs()));
                if (tick++ % 3 == 0) fetchServerStatus();
                cb.onStats(snapshot(info));
            } catch (Throwable t) {
                CrashLog.report("StreamSession.statsLoop", t, false);
            }
        }
    }

    private void fetchServerStatus() {
        HttpURLConnection c = null;
        try {
            c = open("/api/status", "GET", 1500, 1500);
            if (c.getResponseCode() == 200) {
                Map<String, Object> m = MiniJson.parseObject(readText(c.getInputStream()));
                Snapshot s = new Snapshot();
                s.encoderActive = MiniJson.str(m, "encoder_active", "—");
                s.serverEncodeFps = toDouble(m.get("encode_fps"));
                s.serverSentFps = toDouble(m.get("sent_fps"));
                s.serverBitrateKbps = toDouble(m.get("bitrate_kbps"));
                s.serverDropped = MiniJson.num(m, "dropped_frames", -1);
                lastServerStatus = s;
                String err = MiniJson.str(m, "last_error", "");
                serverStatusNote = err.isEmpty() ? "" : err;
            } else if (c.getResponseCode() == 401) {
                token = null;
            }
        } catch (Exception ignored) {
            // status é só informativo
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static double toDouble(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : Double.NaN; }

    private Snapshot snapshot(ServerInfo info) {
        long nowMs = System.nanoTime() / 1_000_000L;
        Snapshot s = new Snapshot();
        s.proto = proto; s.width = videoW; s.height = videoH; s.codec = codecName;
        WsClient w = ws;
        s.transport = (w != null && w.isOpen()) ? "WebSocket" : "HTTP";
        s.decodeFps = decodedRate.perSecond(nowMs);
        s.renderFps = renderedRate.perSecond(nowMs);
        s.decodeMsAvg = decodeMs.avg(); s.decodeMsP95 = decodeMs.p95();
        s.queueWaitMsAvg = queueWaitMs.avg();
        s.ageMsAvg = clock.ready() ? ageMs.avg() : Double.NaN;
        s.rttMs = clock.ready() ? clock.lastRttUs() / 1000.0 : Double.NaN;
        s.bitrateKbps = byteRate.perSecond(nowMs) * 8 / 1000.0;
        s.lostBySeq = lostBySeq.get();
        PacketQueue q = queue;
        s.droppedByClient = q == null ? 0 : q.dropped();
        s.queueDepth = q == null ? 0 : q.size();
        Snapshot srv = lastServerStatus;
        s.encoderActive = srv.encoderActive; s.serverEncodeFps = srv.serverEncodeFps;
        s.serverSentFps = srv.serverSentFps; s.serverBitrateKbps = srv.serverBitrateKbps;
        s.serverDropped = srv.serverDropped;
        return s;
    }

    String diagnostics() {
        ServerInfo si = server;
        StringBuilder b = new StringBuilder();
        b.append("App ").append(AppInfo.VERSION).append(" · fala vídeo ").append(java.util.Arrays.toString(AppInfo.VIDEO_PROTOCOLS)).append('\n');
        if (si != null) {
            b.append("PC: OpenMirror ").append(si.version).append(si.legacy ? " (modo de compatibilidade)" : "").append('\n');
            b.append("Vídeo do PC: ").append(si.videoProtocols).append(" · usando v").append(proto).append('\n');
            b.append("Recursos do PC: ").append(si.capabilities).append('\n');
        } else {
            b.append("PC: ainda não respondeu\n");
        }
        b.append("Endereço: ").append(host).append(':').append(port).append('\n');
        b.append("Decoder: ").append(codecName).append(" · ").append(videoW).append('×').append(videoH).append('\n');
        WsClient w = ws;
        b.append("Entrada: ").append(w != null && w.isOpen() ? "WebSocket" : "HTTP (reserva)");
        OutboundQueue o = outbound;
        if (o != null) b.append(String.format(Locale.ROOT, " · enviados %d · falhas %d · movimentos agrupados %d", o.sent, o.failed, o.coalesced()));
        b.append('\n');
        if (!lastInputError.isEmpty()) b.append("Último erro de entrada: ").append(lastInputError).append('\n');
        if (!lastError.isEmpty()) b.append("Último erro da conexão: ").append(lastError).append('\n');
        if (!serverStatusNote.isEmpty()) b.append("Último erro no PC: ").append(serverStatusNote).append('\n');
        return b.toString();
    }
}
