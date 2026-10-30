package com.openmirror.client;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Estatísticas leves para os indicadores de desempenho. */
final class Stats {
    private Stats() {}

    /** Últimas N amostras: média, p95 e máximo. */
    static final class Rolling {
        private final double[] buf;
        private int n = 0, next = 0;

        Rolling(int capacity) { buf = new double[Math.max(1, capacity)]; }

        synchronized void add(double v) {
            buf[next] = v;
            next = (next + 1) % buf.length;
            if (n < buf.length) n++;
        }

        synchronized int count() { return n; }

        synchronized double avg() {
            if (n == 0) return Double.NaN;
            double s = 0;
            for (int i = 0; i < n; i++) s += buf[i];
            return s / n;
        }

        synchronized double p95() {
            if (n == 0) return Double.NaN;
            double[] copy = Arrays.copyOf(buf, n);
            Arrays.sort(copy);
            return copy[Math.min(n - 1, (int) Math.ceil(n * 0.95) - 1)];
        }

        synchronized double max() {
            if (n == 0) return Double.NaN;
            double m = buf[0];
            for (int i = 1; i < n; i++) m = Math.max(m, buf[i]);
            return m;
        }

        synchronized void clear() { n = 0; next = 0; }
    }

    /** Eventos por segundo numa janela deslizante (FPS decodificado/exibido, bytes/s). */
    static final class Rate {
        private final long windowMs;
        private final ArrayDeque<long[]> events = new ArrayDeque<>();   // {tempoMs, quantidade}

        Rate(long windowMs) { this.windowMs = windowMs; }

        synchronized void tick(long nowMs, long amount) {
            events.addLast(new long[]{nowMs, amount});
            trim(nowMs);
        }

        private void trim(long nowMs) {
            while (!events.isEmpty() && nowMs - events.peekFirst()[0] > windowMs) events.pollFirst();
        }

        synchronized double perSecond(long nowMs) {
            trim(nowMs);
            if (events.size() < 2) return 0;
            long first = events.peekFirst()[0], last = events.peekLast()[0];
            long sum = 0;
            boolean skip = true;
            for (long[] e : events) { if (skip) { skip = false; continue; } sum += e[1]; }
            return sum * 1000.0 / Math.max(1, last - first);
        }
    }
}
