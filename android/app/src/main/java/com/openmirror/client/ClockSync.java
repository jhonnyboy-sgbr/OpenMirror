package com.openmirror.client;

/**
 * Estima a diferença entre o relógio do servidor e o do celular (estilo NTP) usando o
 * ping/pong do WebSocket. Fica com a amostra de MENOR RTT entre as últimas, porque é a menos
 * afetada por fila. O erro da estimativa é de até ~RTT/2 se a ida e a volta forem assimétricas:
 * por isso a "idade do quadro" que sai daqui é ESTIMATIVA, não medição direta.
 */
final class ClockSync {
    private static final int KEEP = 8;
    private final long[] offsets = new long[KEEP];
    private final long[] rtts = new long[KEEP];
    private int count = 0, next = 0;
    private long lastRttUs = -1;

    /**
     * @param t0Us     relógio local ao enviar o ping
     * @param serverUs relógio do servidor ao responder
     * @param t3Us     relógio local ao receber o pong
     */
    synchronized void addSample(long t0Us, long serverUs, long t3Us) {
        long rtt = t3Us - t0Us;
        if (rtt < 0) return;
        long offset = serverUs - (t0Us + rtt / 2);
        offsets[next] = offset;
        rtts[next] = rtt;
        next = (next + 1) % KEEP;
        if (count < KEEP) count++;
        lastRttUs = rtt;
    }

    synchronized boolean ready() { return count > 0; }

    private int best() {
        int idx = 0;
        for (int i = 1; i < count; i++) if (rtts[i] < rtts[idx]) idx = i;
        return idx;
    }

    /** offset = relógio_servidor - relógio_local. */
    synchronized long offsetUs() { return count == 0 ? 0 : offsets[best()]; }
    synchronized long lastRttUs() { return lastRttUs; }
    synchronized long bestRttUs() { return count == 0 ? -1 : rtts[best()]; }

    synchronized long serverToLocalUs(long serverTsUs) { return serverTsUs - offsetUs(); }
}
