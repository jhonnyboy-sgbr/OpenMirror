package com.openmirror.client;

import java.util.ArrayList;

/** Utilidades de bitstream H.264 Annex-B (Java puro). */
final class H264 {
    private H264() {}

    /** Início do NAL em `i` (tamanho do start code: 3 ou 4) ou 0 se não há start code ali. */
    private static int startCode(byte[] d, int i) {
        if (i + 3 < d.length && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) return 3;
        if (i + 4 < d.length && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1) return 4;
        return 0;
    }

    /** true se a AU contém um slice IDR (NAL 5): serve de ponto de recuperação. */
    static boolean isKeyframe(byte[] au) {
        for (int i = 0; i + 3 < au.length; i++) {
            int n = startCode(au, i);
            if (n == 0) continue;
            if ((au[i + n] & 0x1f) == 5) return true;
            i += n - 1;
        }
        return false;
    }

    /** {SPS, PPS} com start code (formato de csd-0/csd-1 do MediaCodec); null onde faltar. */
    static byte[][] parameterSets(byte[] au) {
        byte[][] found = new byte[2][];
        for (int i = 0; i + 3 < au.length; i++) {
            int n = startCode(au, i);
            if (n == 0) continue;
            int type = au[i + n] & 0x1f;
            int end = i + n + 1;
            while (end < au.length && startCode(au, end) == 0) end++;
            if (type == 7 || type == 8) {
                byte[] nal = new byte[end - i];
                System.arraycopy(au, i, nal, 0, nal.length);
                found[type == 7 ? 0 : 1] = nal;
            }
            i = end - 1;
        }
        return found;
    }

    static ArrayList<Integer> nalTypes(byte[] au) {
        ArrayList<Integer> types = new ArrayList<>();
        for (int i = 0; i + 3 < au.length; i++) {
            int n = startCode(au, i);
            if (n == 0) continue;
            types.add(au[i + n] & 0x1f);
            i += n - 1;
        }
        return types;
    }
}
