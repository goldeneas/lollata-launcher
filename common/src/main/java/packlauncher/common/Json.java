package packlauncher.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser/serializer JSON minimale, senza dipendenze.
 * Oggetti -> LinkedHashMap, array -> ArrayList, numeri -> Long o Double,
 * stringhe -> String, booleani -> Boolean, null -> null.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("caratteri in eccesso");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        return (Map<String, Object>) parse(text);
    }

    // ---------- helper di accesso ----------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o, String key) {
        return o instanceof Map ? (Map<String, Object>) ((Map<?, ?>) o).get(key) : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o, String key) {
        Object v = o instanceof Map ? ((Map<?, ?>) o).get(key) : null;
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    public static String str(Object o, String key) {
        Object v = o instanceof Map ? ((Map<?, ?>) o).get(key) : null;
        return v == null ? null : v.toString();
    }

    public static long num(Object o, String key, long def) {
        Object v = o instanceof Map ? ((Map<?, ?>) o).get(key) : null;
        return v instanceof Number n ? n.longValue() : def;
    }

    public static boolean bool(Object o, String key, boolean def) {
        Object v = o instanceof Map ? ((Map<?, ?>) o).get(key) : null;
        return v instanceof Boolean b ? b : def;
    }

    // ---------- parser ----------

    private RuntimeException err(String msg) {
        return new IllegalArgumentException("JSON non valido (" + msg + ") alla posizione " + i);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        if (i >= s.length()) throw err("fine inattesa");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) throw err("atteso " + word);
        i += word.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            if (s.charAt(i) != '"') throw err("attesa chiave");
            String k = string();
            ws();
            if (s.charAt(i++) != ':') throw err("atteso ':'");
            ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("atteso ',' o '}'");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw err("atteso ',' o ']'");
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw err("stringa non chiusa");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                default -> b.append(e);
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (n.isEmpty()) throw err("valore inatteso");
        if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
        return Long.parseLong(n);
    }

    // ---------- serializer ----------

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, 0);
        return b.append('\n').toString();
    }

    private static void write(StringBuilder b, Object v, int ind) {
        if (v == null) b.append("null");
        else if (v instanceof String str) quote(b, str);
        else if (v instanceof Number || v instanceof Boolean) b.append(v);
        else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            int n = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                indent(b, ind + 1);
                quote(b, e.getKey().toString());
                b.append(": ");
                write(b, e.getValue(), ind + 1);
                if (++n < m.size()) b.append(',');
                b.append('\n');
            }
            indent(b, ind);
            b.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) { b.append("[]"); return; }
            b.append("[\n");
            for (int k = 0; k < l.size(); k++) {
                indent(b, ind + 1);
                write(b, l.get(k), ind + 1);
                if (k < l.size() - 1) b.append(',');
                b.append('\n');
            }
            indent(b, ind);
            b.append(']');
        } else quote(b, v.toString());
    }

    private static void indent(StringBuilder b, int n) {
        b.append("  ".repeat(n));
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
