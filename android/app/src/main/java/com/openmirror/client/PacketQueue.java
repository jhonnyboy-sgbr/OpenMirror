package com.openmirror.client;

import java.util.ArrayDeque;

/**
 * Fila entre a thread de rede e a do decoder. Se o decoder atrasar, o atraso NÃO se acumula:
 * passou do limite, descarta tudo e espera o próximo IDR (mesma política do servidor).
 */
final class PacketQueue {
    static final class Packet {
        final byte[] data;
        final boolean key;
        final long seq;          // -1 no protocolo v2 (sem número de sequência)
        final long serverTsUs;   // 0 no protocolo v2
        final long recvNanos;

        Packet(byte[] data, boolean key, long seq, long serverTsUs, long recvNanos) {
            this.data = data; this.key = key; this.seq = seq;
            this.serverTsUs = serverTsUs; this.recvNanos = recvNanos;
        }
    }

    private final ArrayDeque<Packet> items = new ArrayDeque<>();
    private final int limit;
    private boolean waitingKey = false;
    private boolean closed = false;
    private long dropped = 0;

    PacketQueue(int limit) { this.limit = Math.max(1, limit); }

    synchronized void put(Packet p) {
        if (closed) return;
        if (waitingKey) {
            if (!p.key) { dropped++; return; }
            waitingKey = false;
        }
        if (items.size() >= limit) {
            if (p.key) {
                dropped += items.size();
                items.clear();
            } else {
                dropped += items.size() + 1;
                items.clear();
                waitingKey = true;
                return;
            }
        }
        items.addLast(p);
        notifyAll();
    }

    /** null = nada chegou no prazo (ou a fila foi fechada e esvaziou). */
    synchronized Packet poll(long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (items.isEmpty() && !closed) {
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            if (left <= 0) return null;
            wait(left);
        }
        return items.pollFirst();
    }

    synchronized int size() { return items.size(); }
    synchronized long dropped() { return dropped; }
    synchronized boolean isWaitingKey() { return waitingKey; }
    synchronized void close() { closed = true; items.clear(); notifyAll(); }
}
