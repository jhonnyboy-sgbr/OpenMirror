package com.openmirror.client;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Procura PCs com OpenMirror na rede local (UDP broadcast). Não transmite nenhum segredo. */
final class DiscoveryClient {
    static final String MAGIC = "OPENMIRROR_DISCOVER_V1";

    static final class Found {
        final String host, name, version;
        final int port;

        Found(String host, int port, String name, String version) {
            this.host = host; this.port = port; this.name = name; this.version = version;
        }

        String address() { return host + ":" + port; }
    }

    private DiscoveryClient() {}

    /** Endereço de broadcast a partir do IP e da máscara do Wi-Fi (ints little-endian do DhcpInfo). */
    static InetAddress broadcastFromDhcp(int ip, int mask) throws IOException {
        int b = (ip & mask) | ~mask;
        return InetAddress.getByAddress(new byte[]{(byte) b, (byte) (b >> 8), (byte) (b >> 16), (byte) (b >> 24)});
    }

    static List<Found> scan(List<InetAddress> targets, int udpPort, int timeoutMs) throws IOException {
        Map<String, Found> found = new LinkedHashMap<>();
        byte[] request = MAGIC.getBytes(StandardCharsets.US_ASCII);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(120);
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            long[] resendAt = {0, 250, 600};   // UDP perde pacote: repete algumas vezes
            long start = System.nanoTime();
            int sent = 0;
            byte[] buffer = new byte[1024];
            while (System.nanoTime() < deadline) {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                if (sent < resendAt.length && elapsedMs >= resendAt[sent]) {
                    for (InetAddress target : targets) {
                        try {
                            socket.send(new DatagramPacket(request, request.length, target, udpPort));
                        } catch (IOException ignored) { /* alvo inalcançável: tenta os outros */ }
                    }
                    sent++;
                }
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(reply);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                Found f = parseReply(reply.getAddress().getHostAddress(),
                        new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8));
                if (f != null) found.put(f.host, f);
            }
        }
        return new ArrayList<>(found.values());
    }

    static Found parseReply(String fromHost, String json) {
        try {
            Map<String, Object> m = MiniJson.parseObject(json);
            if (!"OpenMirror".equals(MiniJson.str(m, "app", ""))) return null;
            long port = MiniJson.num(m, "port", -1);
            if (port < 1 || port > 65535) return null;
            return new Found(fromHost, (int) port, MiniJson.str(m, "name", fromHost),
                    MiniJson.str(m, "version", "?"));
        } catch (MiniJson.JsonException e) {
            return null;
        }
    }
}
