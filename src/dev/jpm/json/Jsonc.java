package dev.jpm.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSONC (JSON with comments and trailing commas) parser and writer.
 *
 * Parsed values are plain Java types: {@link Map} (ordered), {@link List}, {@link String},
 * {@link BigDecimal}, {@link Boolean} and {@code null}. Source positions of object members are
 * kept in {@link Node} so the manifest can report exact line numbers and be edited in place
 * without losing comments.
 */
public final class Jsonc {

    /** A parsed value with its source span. */
    public static final class Node {
        public final Object value;
        public final int start;
        public final int end;
        /** For objects: member name to node, in order. */
        public final Map<String, Member> members;
        /** For arrays. */
        public final List<Node> items;

        Node(Object value, int start, int end, Map<String, Member> members, List<Node> items) {
            this.value = value;
            this.start = start;
            this.end = end;
            this.members = members;
            this.items = items;
        }

        public Node get(String key) {
            if (members == null) return null;
            Member m = members.get(key);
            return m == null ? null : m.value;
        }

        public boolean isObject() { return members != null; }
        public boolean isArray() { return items != null; }
        public boolean isString() { return value instanceof String; }
    }

    /** An object member: key span plus value node. */
    public static final class Member {
        public final String key;
        public final int keyStart;
        public final Node value;
        Member(String key, int keyStart, Node value) {
            this.key = key;
            this.keyStart = keyStart;
            this.value = value;
        }
    }

    public static final class ParseException extends RuntimeException {
        public final int line;
        public final int column;
        public ParseException(String message, int line, int column) {
            super(message + " at line " + line + ", column " + column);
            this.line = line;
            this.column = column;
        }
    }

    private final String src;
    private int pos;

    private Jsonc(String src) {
        this.src = src;
    }

    public static Node parseNode(String src) {
        Jsonc p = new Jsonc(src);
        p.skipWs();
        Node n = p.parseValue();
        p.skipWs();
        if (p.pos < src.length()) throw p.error("Unexpected trailing content");
        return n;
    }

    public static Object parse(String src) {
        return parseNode(src).value;
    }

    /** 1-based line number of an offset. */
    public static int lineOf(String src, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < src.length(); i++) if (src.charAt(i) == '\n') line++;
        return line;
    }

    private ParseException error(String msg) {
        int line = 1, col = 1;
        for (int i = 0; i < pos && i < src.length(); i++) {
            if (src.charAt(i) == '\n') { line++; col = 1; } else col++;
        }
        return new ParseException(msg, line, col);
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') {
                pos++;
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                while (pos < src.length() && src.charAt(pos) != '\n') pos++;
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
                int close = src.indexOf("*/", pos + 2);
                if (close < 0) throw error("Unterminated comment");
                pos = close + 2;
            } else {
                break;
            }
        }
    }

    private Node parseValue() {
        if (pos >= src.length()) throw error("Unexpected end of input");
        char c = src.charAt(pos);
        int start = pos;
        switch (c) {
            case '{': return parseObject();
            case '[': return parseArray();
            case '"': {
                String s = parseString();
                return new Node(s, start, pos, null, null);
            }
            case 't': expect("true"); return new Node(Boolean.TRUE, start, pos, null, null);
            case 'f': expect("false"); return new Node(Boolean.FALSE, start, pos, null, null);
            case 'n': expect("null"); return new Node(null, start, pos, null, null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                throw error("Unexpected character '" + c + "'");
        }
    }

    private void expect(String word) {
        if (!src.startsWith(word, pos)) throw error("Expected " + word);
        pos += word.length();
    }

    private Node parseNumber() {
        int start = pos;
        if (src.charAt(pos) == '-') pos++;
        while (pos < src.length() && "0123456789.eE+-".indexOf(src.charAt(pos)) >= 0) pos++;
        try {
            return new Node(new BigDecimal(src.substring(start, pos)), start, pos, null, null);
        } catch (NumberFormatException e) {
            pos = start;
            throw error("Invalid number");
        }
    }

    private String parseString() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw error("Unterminated string");
            char c = src.charAt(pos++);
            if (c == '"') break;
            if (c == '\n') throw error("Newline in string");
            if (c == '\\') {
                if (pos >= src.length()) throw error("Unterminated string");
                char e = src.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > src.length()) throw error("Bad unicode escape");
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default: throw error("Bad escape \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Node parseObject() {
        int start = pos;
        pos++;
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Member> members = new LinkedHashMap<>();
        skipWs();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return new Node(map, start, pos, members, null);
        }
        while (true) {
            skipWs();
            if (pos < src.length() && src.charAt(pos) == '}') { pos++; break; }
            if (pos >= src.length() || src.charAt(pos) != '"') throw error("Expected property name");
            int keyStart = pos;
            String key = parseString();
            if (members.containsKey(key)) {
                pos = keyStart;
                throw error("Duplicate key \"" + key + "\"");
            }
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != ':') throw error("Expected ':'");
            pos++;
            skipWs();
            Node v = parseValue();
            map.put(key, v.value);
            members.put(key, new Member(key, keyStart, v));
            skipWs();
            if (pos >= src.length()) throw error("Unterminated object");
            char c = src.charAt(pos);
            if (c == ',') { pos++; continue; }
            if (c == '}') { pos++; break; }
            throw error("Expected ',' or '}'");
        }
        return new Node(map, start, pos, members, null);
    }

    private Node parseArray() {
        int start = pos;
        pos++;
        List<Object> list = new ArrayList<>();
        List<Node> items = new ArrayList<>();
        while (true) {
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ']') { pos++; break; }
            Node v = parseValue();
            list.add(v.value);
            items.add(v);
            skipWs();
            if (pos >= src.length()) throw error("Unterminated array");
            char c = src.charAt(pos);
            if (c == ',') { pos++; continue; }
            if (c == ']') { pos++; break; }
            throw error("Expected ',' or ']'");
        }
        return new Node(list, start, pos, null, items);
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** Pretty-prints a value with two-space indentation. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, "");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v, String indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            sb.append(quote(s));
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(v instanceof BigDecimal bd ? bd.toPlainString() : v.toString());
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            String inner = indent + "  ";
            int i = 0;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                sb.append(inner).append(quote(e.getKey())).append(": ");
                write(sb, e.getValue(), inner);
                if (++i < m.size()) sb.append(',');
                sb.append('\n');
            }
            sb.append(indent).append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) { sb.append("[]"); return; }
            boolean simple = l.stream().allMatch(x -> x instanceof String || x instanceof Number);
            if (simple) {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) sb.append(", ");
                    write(sb, l.get(i), indent);
                }
                sb.append(']');
                return;
            }
            sb.append("[\n");
            String inner = indent + "  ";
            for (int i = 0; i < l.size(); i++) {
                sb.append(inner);
                write(sb, l.get(i), inner);
                if (i + 1 < l.size()) sb.append(',');
                sb.append('\n');
            }
            sb.append(indent).append(']');
        } else {
            throw new IllegalArgumentException("Cannot write " + v.getClass());
        }
    }
}
