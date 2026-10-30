package com.openmirror.client;

import java.io.IOException;
import java.util.ArrayDeque;

/**
 * Fila de saída dos comandos para o PC, com uma thread própria.
 * Movimentos do cursor são COALESCIDOS (só o mais recente vale: enviar 120 movimentos por
 * segundo atrasados é pior que enviar menos e atuais), mas a ORDEM em relação a cliques,
 * teclas e botões é preservada: qualquer comando que não seja movimento "empurra" antes de si
 * o último movimento pendente.
 */
final class OutboundQueue {
    interface Transport { boolean send(String json) throws IOException; }

    /** Estrutura de dados pura (testável sem thread). */
    static final class Mailbox {
        private static final int MAX = 512;
        private final ArrayDeque<String> queue = new ArrayDeque<>();
        private String pendingMove;
        private boolean closed;
        long coalesced = 0;

        synchronized void offer(String json, boolean coalescable) {
            if (closed) return;
            if (coalescable) {
                if (pendingMove != null) coalesced++;
                pendingMove = json;
            } else {
                if (pendingMove != null) { queue.addLast(pendingMove); pendingMove = null; }
                if (queue.size() >= MAX) queue.pollFirst();   // proteção: nunca crescer sem limite
                queue.addLast(json);
            }
            notifyAll();
        }

        /** Próxima mensagem; null se passou o tempo (ou se foi fechada e esvaziada). */
        synchronized String take(long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (queue.isEmpty() && pendingMove == null && !closed) {
                long left = (deadline - System.nanoTime()) / 1_000_000L;
                if (left <= 0) return null;
                wait(left);
            }
            if (!queue.isEmpty()) return queue.pollFirst();
            String m = pendingMove;
            pendingMove = null;
            return m;
        }

        synchronized void close() { closed = true; queue.clear(); pendingMove = null; notifyAll(); }
        synchronized int size() { return queue.size() + (pendingMove != null ? 1 : 0); }
    }

    private final Mailbox box = new Mailbox();
    private final Transport transport;
    private Thread worker;
    private volatile boolean running;
    volatile long sent = 0, failed = 0;

    OutboundQueue(Transport transport) { this.transport = transport; }

    void start() {
        running = true;
        worker = new Thread(this::pump, "OpenMirror-Input");
        worker.setDaemon(true);
        worker.start();
    }

    void send(String json) { box.offer(json, false); }
    void sendMove(String json) { box.offer(json, true); }
    long coalesced() { return box.coalesced; }

    private void pump() {
        while (running) {
            try {
                String msg = box.take(500);
                if (msg == null) continue;
                if (transport.send(msg)) sent++; else failed++;
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                failed++;    // comando de cursor perdido nunca pode travar o vídeo
            } catch (RuntimeException e) {
                failed++;
            }
        }
    }

    void stop() {
        running = false;
        box.close();
        if (worker != null) worker.interrupt();
    }
}
