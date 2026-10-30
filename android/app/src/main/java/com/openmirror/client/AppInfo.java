package com.openmirror.client;

/** Versão do app e do protocolo que ele sabe falar. */
final class AppInfo {
    static final String VERSION = "0.3.1";
    /** Versões do protocolo de vídeo H.264 que este cliente decodifica (2 = OMH2, 3 = OMH3). */
    static final int[] VIDEO_PROTOCOLS = {2, 3};
    static final int DISCOVERY_PORT = 8766;

    private AppInfo() {}
}
