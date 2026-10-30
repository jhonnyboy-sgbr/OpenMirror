package com.openmirror.client;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Testes de rede do Android contra servidores escritos em Python (outra implementação). */
public final class NetTests {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name + ": " + detail); }
    }

    static String repeat(char c, int n) { char[] a = new char[n]; java.util.Arrays.fill(a, c); return new String(a); }

    static void ws(String host, int port) throws Exception {
        WsClient.Listener none = new WsClient.Listener() {
            public void onText(String t) { }
            public void onClosed(String r) { }
        };
        try {
            new WsClient(host, port, "/ws/input", Collections.emptyMap(), none).connect(2000);
            check("ws: sem sessão o servidor recusa", false, "conectou sem token");
        } catch (IOException e) {
            check("ws: sem sessão o servidor recusa (401 chega na exceção)", e.getMessage().contains("401"), e.getMessage());
        }

        final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        final AtomicReference<String> closed = new AtomicReference<>();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-OpenMirror-Session", "tok");
        WsClient c = new WsClient(host, port, "/ws/input", headers, new WsClient.Listener() {
            public void onText(String t) { inbox.add(t); }
            public void onClosed(String r) { closed.set(r); }
        });
        c.connect(2000);
        check("ws: conectou com sessão", c.isOpen(), "");

        c.sendText("hello");
        check("ws: eco de texto curto", "echo:5:hello".equals(inbox.poll(2, TimeUnit.SECONDS)), "");
        c.sendText(repeat('x', 3000));
        check("ws: cliente->servidor 3000 bytes (comprimento de 16 bits, mascarado)", "echo:3000:xxxxxxxxxxxxxxxxxxxx".equals(inbox.poll(2, TimeUnit.SECONDS)), "");
        c.sendText(repeat('z', 70000));
        check("ws: cliente->servidor 70000 bytes (comprimento de 64 bits)", "echo:70000:zzzzzzzzzzzzzzzzzzzz".equals(inbox.poll(3, TimeUnit.SECONDS)), "");
        c.sendText("big");
        String big = inbox.poll(3, TimeUnit.SECONDS);
        check("ws: servidor->cliente 70000 bytes", big != null && big.length() == 70000 && big.charAt(69999) == 'y', big == null ? "null" : "len=" + big.length());
        c.sendText("frag");
        check("ws: mensagem fragmentada é remontada", "abcd".equals(inbox.poll(2, TimeUnit.SECONDS)), "");
        c.sendText("unicode");
        check("ws: UTF-8 com emoji", "olá 😀".equals(inbox.poll(2, TimeUnit.SECONDS)), "");
        c.sendText("pongs");
        String pongs = inbox.poll(2, TimeUnit.SECONDS);
        check("ws: o cliente respondeu o ping do servidor com pong", pongs != null && !pongs.equals("pongs:0"), String.valueOf(pongs));
        c.sendText("close");
        long end = System.currentTimeMillis() + 2000;
        while (closed.get() == null && System.currentTimeMillis() < end) Thread.sleep(10);
        check("ws: fechamento pelo servidor é notificado", closed.get() != null, "");
        check("ws: depois de fechar, isOpen é false e sendText devolve false", !c.isOpen() && !c.sendText("x"), "");

        // WS que o servidor derruba (porta fechada): connect falha rápido com IOException
        try {
            new WsClient(host, 1, "/ws/input", headers, none).connect(500);
            check("ws: porta fechada gera IOException", false, "");
        } catch (IOException e) {
            check("ws: porta fechada gera IOException", true, "");
        }
    }

    static void discovery(int port) throws Exception {
        List<DiscoveryClient.Found> found = DiscoveryClient.scan(
                Collections.singletonList(InetAddress.getByName("127.0.0.1")), port, 1500);
        check("descoberta: achou exatamente 1 PC", found.size() == 1, String.valueOf(found.size()));
        if (!found.isEmpty()) {
            DiscoveryClient.Found f = found.get(0);
            check("descoberta: nome e porta do servidor Python", f.name.equals("PC-DO-JOÃO") && f.port == 8765, f.name + ":" + f.port);
            check("descoberta: IP vem do remetente do datagrama", f.host.equals("127.0.0.1"), f.host);
        }
        List<DiscoveryClient.Found> none = DiscoveryClient.scan(
                Collections.singletonList(InetAddress.getByName("127.0.0.1")), 9 /* nada escutando */, 500);
        check("descoberta: sem servidor, lista vazia (sem travar)", none.isEmpty(), "");
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("ws")) ws(args[1], Integer.parseInt(args[2]));
        else discovery(Integer.parseInt(args[1]));
        System.out.println("TOTAL pass=" + pass + " fail=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
