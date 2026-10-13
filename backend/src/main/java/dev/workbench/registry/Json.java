package dev.workbench.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer — no external dependencies (old Jeeves pattern).
 * Supports objects, arrays, strings (with escapes), numbers, booleans, null.
 * Objects decode to LinkedHashMap (order-preserving), arrays to List.
 */
public final class Json {

    private Json() {}

    // ---------------------------------------------------------------- write

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(s, sb);
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                write(o, sb);
            }
            sb.append(']');
        } else if (v.getClass().isArray()) {
            sb.append('[');
            int n = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                write(java.lang.reflect.Array.get(v, i), sb);
            }
            sb.append(']');
        } else {
            quote(String.valueOf(v), sb);
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- read

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (!p.eof()) throw new IllegalArgumentException("trailing content at " + p.pos);
        return v;
    }

    /** Parse a request body expected to be a JSON object; empty body → empty map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected JSON object");
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int pos;
        private int depth;
        private static final int MAX_DEPTH = 512;   // guard the recursive descent: a deeply-nested body must throw (catchable), never StackOverflowError (review r-853c5f9 #1)

        Parser(String s) { this.s = s; }

        boolean eof() { return pos >= s.length(); }

        void ws() {
            while (!eof()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        char peek() {
            if (eof()) throw new IllegalArgumentException("unexpected end of JSON");
            return s.charAt(pos);
        }

        void expect(char c) {
            if (eof() || s.charAt(pos) != c)
                throw new IllegalArgumentException("expected '" + c + "' at " + pos);
            pos++;
        }

        Object value() {
            if (++depth > MAX_DEPTH) throw new IllegalArgumentException("JSON nesting too deep (> " + MAX_DEPTH + ")");
            try {
                char c = peek();
                return switch (c) {
                    case '{' -> object();
                    case '[' -> array();
                    case '"' -> string();
                    case 't' -> { literal("true"); yield Boolean.TRUE; }
                    case 'f' -> { literal("false"); yield Boolean.FALSE; }
                    case 'n' -> { literal("null"); yield null; }
                    default -> number();
                };
            } finally {
                depth--;
            }
        }

        void literal(String lit) {
            if (!s.startsWith(lit, pos)) throw new IllegalArgumentException("bad literal at " + pos);
            pos += lit.length();
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return m; }
                throw new IllegalArgumentException("expected ',' or '}' at " + pos);
            }
        }

        List<Object> array() {
            expect('[');
            List<Object> l = new ArrayList<>();
            ws();
            if (peek() == ']') { pos++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return l; }
                throw new IllegalArgumentException("expected ',' or ']' at " + pos);
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw new IllegalArgumentException("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Number number() {
            int start = pos;
            if (!eof() && s.charAt(pos) == '-') pos++;
            while (!eof() && "0123456789.eE+-".indexOf(s.charAt(pos)) >= 0) pos++;
            if (start == pos) throw new IllegalArgumentException("bad value at " + pos);
            String n = s.substring(start, pos);
            try {
                if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
                long l = Long.parseLong(n);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
                return l;
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("bad number '" + n + "' at " + start);
            }
        }
    }
}
