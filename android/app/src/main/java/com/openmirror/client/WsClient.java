package com.openmirror.client;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cliente WebSocket (RFC 6455) mínimo, só texto, sem dependências: o Android não traz um e
 * assim o projeto continua compilando sem baixar bibliotecas. Mantém UMA conexão aberta para
 * os comandos (a 0.2.0 abria uma conexão HTTP nova a cada movimento do dedo).
 */
final class WsClient {
    interface Listener {
        void onText(String text);
        void onClosed(String reason);
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_FRAME = 1 << 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String host, path;
    private final int port;
    private final Map<String, String> headers;
    private final Listener listener;
    private final Object writeLock = new Object();
    private final AtomicBoolean closedNotified = new AtomicBoolean(false);
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean open = false;

    WsClient(String host, int port, String path, Map<String, String> headers, Listener listener) {
        this.host = host; this.port = port; this.path = path;
        this.headers = headers; this.listener = listener;
    }

    boolean isOpen() { return open; }

    /** Faz o handshake (bloqueia). Lança IOException com o status HTTP se o servidor recusar. */
    void connect(int timeoutMs) throws IOException {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setSoTimeout(timeoutMs);
        in = new BufferedInputStream(socket.getInputStream(), 8192);
        out = socket.getOutputStream();

        byte[] keyBytes = new byte[16];
        RANDOM.nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n")
           .append("Host: ").append(host).append(':').append(port).append("\r\n")
           .append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
           .append("Sec-WebSocket-Key: ").append(key).append("\r\n")
           .append("Sec-WebSocket-Version: 13\r\n");
        for (Map.Entry<String, String> h : headers.entrySet()) {
            req.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        req.append("\r\n");
        out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        String status = readLine();
        if (!status.startsWith("HTTP/1.1 101") && !status.startsWith("HTTP/1.0 101")) {
            closeQuietly();
            throw new IOException("WebSocket recusado: " + status);
        }
        Map<String, String> response = new HashMap<>();
        String line;
        while (!(line = readLine()).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                response.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                             line.substring(colon + 1).trim());
            }
        }
        if (!accept(key).equals(response.get("sec-websocket-accept"))) {
            closeQuietly();
            throw new IOException("Resposta de WebSocket inválida (Accept não confere)");
        }
        socket.setSoTimeout(45_000);   // o servidor manda ping a cada 15 s: silêncio longo = caiu
        open = true;
        Thread reader = new Thread(this::readLoop, "OpenMirror-WS");
        reader.setDaemon(true);
        reader.start();
    }

    static String accept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String readLine() throws IOException {
        StringBuilder b = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) throw new EOFException("Conexão encerrada no handshake");
            if (c == '\n') break;
            if (c != '\r') b.append((char) c);
            if (b.length() > 8192) throw new IOException("Cabeçalho HTTP grande demais");
        }
        return b.toString();
    }

    private byte[] readFully(int n) throws IOException {
        byte[] data = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(data, got, n - got);
            if (r < 0) throw new EOFException("Conexão encerrada");
            got += r;
        }
        return data;
    }

    private void readLoop() {
        String reason = "conexão encerrada";
        ByteArrayOutputStream fragment = null;
        try {
            while (open) {
                int b0 = in.read();
                if (b0 < 0) break;
                int b1 = in.read();
                if (b1 < 0) break;
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0f;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7f;
                if (len == 126) {
                    byte[] ext = readFully(2);
                    len = ((ext[0] & 0xffL) << 8) | (ext[1] & 0xffL);
                } else if (len == 127) {
                    byte[] ext = readFully(8);
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | (ext[i] & 0xffL);
                }
                if (len < 0 || len > MAX_FRAME) throw new IOException("Quadro WebSocket grande demais");
                byte[] mask = masked ? readFully(4) : null;
                byte[] payload = readFully((int) len);
                if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

                if (op == 0x8) {                    // close
                    reason = "fechado pelo servidor";
                    writeFrame(0x8, payload.length >= 2 ? new byte[]{payload[0], payload[1]} : new byte[0]);
                    break;
                } else if (op == 0x9) {             // ping -> pong com o mesmo conteúdo
                    writeFrame(0xA, payload);
                } else if (op == 0x1 || op == 0x0) {
                    if (op == 0x1) fragment = new ByteArrayOutputStream();
                    if (fragment == null) throw new IOException("Fragmento sem início");
                    fragment.write(payload);
                    if (fragment.size() > MAX_FRAME) throw new IOException("Mensagem grande demais");
                    if (fin) {
                        String text = new String(fragment.toByteArray(), StandardCharsets.UTF_8);
                        fragment = null;
                        listener.onText(text);
                    }
                }                                    // 0x2 (binário) e 0xA (pong): ignorados
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof RuntimeException) CrashLog.report("WsClient.readLoop", e, false);
            reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            open = false;
            closeQuietly();
            if (closedNotified.compareAndSet(false, true)) listener.onClosed(reason);
        }
    }

    /** Envia texto. false = a conexão não está aberta ou a escrita falhou. */
    boolean sendText(String text) {
        if (!open) return false;
        try {
            writeFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            open = false;
            closeQuietly();
            return false;
        }
    }

    private void writeFrame(int op, byte[] payload) throws IOException {
        int len = payload.length;
        int header = len < 126 ? 2 : (len <= 0xffff ? 4 : 10);
        byte[] frame = new byte[header + 4 + len];
        frame[0] = (byte) (0x80 | op);
        if (len < 126) {
            frame[1] = (byte) (0x80 | len);
        } else if (len <= 0xffff) {
            frame[1] = (byte) (0x80 | 126);
            frame[2] = (byte) (len >> 8);
            frame[3] = (byte) len;
        } else {
            frame[1] = (byte) (0x80 | 127);
            for (int i = 0; i < 8; i++) frame[2 + i] = (byte) ((long) len >> (8 * (7 - i)));
        }
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        System.arraycopy(mask, 0, frame, header, 4);
        for (int i = 0; i < len; i++) frame[header + 4 + i] = (byte) (payload[i] ^ mask[i & 3]);
        synchronized (writeLock) {
            out.write(frame);
            out.flush();
        }
    }

    /** Fecha o socket sem escrever nada: seguro na thread principal (o Android proíbe escrita de rede lá). */
    void abort() {
        open = false;
        closeQuietly();
    }

    /** Fechamento educado (envia o quadro Close). Só chamar fora da thread principal. */
    void close() {
        if (open) {
            try { writeFrame(0x8, new byte[]{0x03, (byte) 0xe8}); } catch (IOException ignored) { }
        }
        open = false;
        closeQuietly();
    }

    private void closeQuietly() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
    }
}
