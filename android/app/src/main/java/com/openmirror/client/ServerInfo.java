package com.openmirror.client;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** O que o servidor Windows disse em /api/hello (negociação de versão e capacidades). */
final class ServerInfo {
    final String version;
    final Set<Integer> videoProtocols = new HashSet<>();
    final Set<String> capabilities = new HashSet<>();
    final boolean h264Available;
    /** true = servidor sem /api/hello (0.2.0 ou anterior): usamos o modo antigo. */
    final boolean legacy;

    private ServerInfo(String version, boolean h264, boolean legacy) {
        this.version = version; this.h264Available = h264; this.legacy = legacy;
    }

    /** Servidor 0.2.0: H.264 v2, WebSocket de entrada, sem arrastar de verdade nem atalhos. */
    static ServerInfo legacy() {
        ServerInfo s = new ServerInfo("0.2.0 (ou anterior)", true, true);
        s.videoProtocols.add(2);
        s.capabilities.add("mjpeg"); s.capabilities.add("h264"); s.capabilities.add("ws-input");
        return s;
    }

    static ServerInfo parse(String json) throws MiniJson.JsonException {
        Map<String, Object> m = MiniJson.parseObject(json);
        if (!"OpenMirror".equals(MiniJson.str(m, "app", ""))) {
            throw new MiniJson.JsonException("Isto não é um servidor OpenMirror");
        }
        ServerInfo s = new ServerInfo(MiniJson.str(m, "version", "?"),
                MiniJson.bool(m, "h264_available", false), false);
        for (Object o : MiniJson.list(m, "video_protocols")) {
            if (o instanceof Number) s.videoProtocols.add(((Number) o).intValue());
        }
        for (Object o : MiniJson.list(m, "capabilities")) {
            if (o instanceof String) s.capabilities.add((String) o);
        }
        return s;
    }

    boolean has(String capability) { return capabilities.contains(capability); }

    /** Maior versão de protocolo de vídeo que os dois lados falam; -1 se não há nenhuma. */
    int bestVideoProtocol(int[] supported) {
        int best = -1;
        for (int p : supported) if (videoProtocols.contains(p) && p > best) best = p;
        return best;
    }

    static String describeIncompatibility(ServerInfo s, int[] supported) {
        StringBuilder mine = new StringBuilder();
        for (int p : supported) mine.append(mine.length() == 0 ? "" : ", ").append(p);
        return "Versão incompatível: o servidor (" + s.version + ") fala o vídeo " + s.videoProtocols
                + " e este app fala [" + mine + "]. Atualize o OpenMirror no Windows ou o app no celular.";
    }

}
