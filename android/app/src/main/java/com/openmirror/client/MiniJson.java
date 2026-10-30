package com.openmirror.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitor de JSON mínimo (objetos, listas, strings, números, true/false/null).
 * Existe para a camada de rede ficar em Java puro e ser testável no JVM (org.json só existe
 * dentro do Android). Números inteiros viram Long; os demais, Double.
 */
final class MiniJson {
    static final class JsonException extends Exception {
        private static final long serialVersionUID = 1L;
        JsonException(String message) { super(message); }
    }

    private final String s;
    private int i = 0;

    private MiniJson(String s) { this.s = s; }

    static Map<String, Object> parseObject(String text) throws JsonException {
        if (text == null) throw new JsonException("JSON vazio");
        MiniJson p = new MiniJson(text);
        p.ws();
        Object value = p.value(0);
        p.ws();
        if (p.i != p.s.length()) throw new JsonException("Texto extra depois do JSON");
        if (!(value instanceof Map)) throw new JsonException("Esperava um objeto JSON");
        @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    private void ws() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
    }

    private Object value(int depth) throws JsonException {
        if (depth > 20) throw new JsonException("JSON aninhado demais");
        if (i >= s.length()) throw new JsonException("JSON incompleto");
        char c = s.charAt(i);
        if (c == '{') return object(depth);
        if (c == '[') return array(depth);
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object(int depth) throws JsonException {
        Map<String, Object> map = new LinkedHashMap<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return map; }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw new JsonException("Chave esperada");
            String key = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw new JsonException("':' esperado");
            i++;
            ws();
            map.put(key, value(depth + 1));
            ws();
            if (i >= s.length()) throw new JsonException("JSON incompleto");
            char c = s.charAt(i++);
            if (c == '}') return map;
            if (c != ',') throw new JsonException("',' ou '}' esperado");
        }
    }

    private List<Object> array(int depth) throws JsonException {
        List<Object> list = new ArrayList<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return list; }
        while (true) {
            ws();
            list.add(value(depth + 1));
            ws();
            if (i >= s.length()) throw new JsonException("JSON incompleto");
            char c = s.charAt(i++);
            if (c == ']') return list;
            if (c != ',') throw new JsonException("',' ou ']' esperado");
        }
    }

    private String string() throws JsonException {
        StringBuilder out = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw new JsonException("String não terminada");
            char c = s.charAt(i++);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); continue; }
            if (i >= s.length()) throw new JsonException("Escape incompleto");
            char e = s.charAt(i++);
            switch (e) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw new JsonException("\\u incompleto");
                    try {
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw new JsonException("\\u inválido");
                    }
                    i += 4;
                    break;
                default: throw new JsonException("Escape inválido");
            }
        }
    }

    private Object number() throws JsonException {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw new JsonException("Valor inválido");
        String text = s.substring(start, i);
        try {
            if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
                return Long.parseLong(text);
            }
            return Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            throw new JsonException("Número inválido");
        }
    }

    // ---- ajudantes de leitura tolerantes ----
    static String str(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : fallback;
    }

    static long num(Map<String, Object> m, String key, long fallback) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : fallback;
    }

    static boolean bool(Map<String, Object> m, String key, boolean fallback) {
        Object v = m.get(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    static List<Object> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        @SuppressWarnings("unchecked") List<Object> l = v instanceof List ? (List<Object>) v : new ArrayList<>();
        return l;
    }
}
