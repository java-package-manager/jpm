package dev.jpm.json;

import dev.jpm.api.json.Jsonc;

import java.util.List;

/**
 * Text-level edits to a JSONC document that keep comments and formatting intact.
 * Each method returns the new document text.
 */
public final class JsoncEdit {
    private JsoncEdit() {
    }

    /**
     * Sets {@code path... -> key} to the given raw JSON value, creating parent objects as needed.
     */
    public static String set(String src, List<String> path, String key, String rawValue) {
        Jsonc.Node root = Jsonc.parseNode(src);
        Jsonc.Node obj = root;
        int depth = 0;
        for (String p : path) {
            Jsonc.Node next = obj.get(p);
            if (next == null) {
                List<String> names = new java.util.ArrayList<>(path.subList(depth + 1, path.size()));
                names.add(key);
                return insertMember(src, obj, p, nest(names, rawValue, memberIndent(src, obj)));
            }
            if (!next.isObject()) throw new IllegalArgumentException("\"" + p + "\" is not an object");
            obj = next;
            depth++;
        }
        Jsonc.Member existing = obj.members.get(key);
        if (existing != null) {
            return src.substring(0, existing.value.start) + rawValue + src.substring(existing.value.end);
        }
        return insertMember(src, obj, key, rawValue);
    }

    /**
     * Removes {@code path... -> key} if present.
     */
    public static String remove(String src, List<String> path, String key) {
        Jsonc.Node obj = Jsonc.parseNode(src);
        for (String p : path) {
            obj = obj.get(p);
            if (obj == null || !obj.isObject()) return src;
        }
        Jsonc.Member m = obj.members.get(key);
        if (m == null) return src;
        int start = m.keyStart;
        int lineStart = src.lastIndexOf('\n', start - 1) + 1;
        if (src.substring(lineStart, start).isBlank()) start = lineStart;
        int end = m.value.end;
        int i = skipInline(src, end);
        boolean hadComma = i < src.length() && src.charAt(i) == ',';
        if (hadComma) {
            end = i + 1;
            int j = end;
            while (j < src.length() && (src.charAt(j) == ' ' || src.charAt(j) == '\t')) j++;
            if (src.startsWith("//", j)) {
                while (j < src.length() && src.charAt(j) != '\n') j++;
            }
            if (j < src.length() && src.charAt(j) == '\n') end = j + 1;
            else if (start == lineStart) end = j;
        } else {
            int k = start - 1;
            while (k > obj.start && Character.isWhitespace(src.charAt(k))) k--;
            if (src.charAt(k) == ',') {
                String between = src.substring(k + 1, start);
                String result = src.substring(0, k) + (between.contains("\n") ? between : "") + src.substring(end);
                return cleanupBlankLine(result, start - 1);
            }
            if (start == lineStart) {
                int j = end;
                while (j < src.length() && (src.charAt(j) == ' ' || src.charAt(j) == '\t')) j++;
                if (j < src.length() && src.charAt(j) == '\n') end = j + 1;
            }
        }
        return src.substring(0, start) + src.substring(end);
    }

    private static String cleanupBlankLine(String s, int around) {
        int ls = s.lastIndexOf('\n', Math.max(0, around - 1));
        if (ls < 0) return s;
        int le = s.indexOf('\n', ls + 1);
        if (le < 0) return s;
        if (s.substring(ls + 1, le).isBlank()) return s.substring(0, ls) + s.substring(le);
        return s;
    }

    private static int skipInline(String src, int i) {
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') i++;
            else if (src.startsWith("/*", i)) i = src.indexOf("*/", i) + 2;
            else if (src.startsWith("//", i)) {
                while (i < src.length() && src.charAt(i) != '\n') i++;
            } else break;
        }
        return i;
    }

    private static String insertMember(String src, Jsonc.Node obj, String key, String rawValue) {
        String objIndent = indentOf(src, obj);
        int close = obj.end - 1;
        if (obj.members.isEmpty()) {
            String inner = objIndent + "  ";
            return src.substring(0, obj.start + 1) + "\n" + inner + Jsonc.quote(key) + ": " + rawValue
                    + "\n" + objIndent + src.substring(close);
        }
        Jsonc.Member last = null;
        for (Jsonc.Member m : obj.members.values()) last = m;
        String memberIndent = lineIndent(src, last.keyStart);
        boolean multiline = src.substring(obj.start, obj.end).indexOf('\n') >= 0;
        int afterLast = last.value.end;
        int i = skipInlineSameLine(src, afterLast);
        boolean trailingComma = i < src.length() && src.charAt(i) == ',';
        int insertAt;
        String prefix;
        if (trailingComma) {
            insertAt = i + 1;
            prefix = "";
        } else {
            insertAt = afterLast;
            prefix = ",";
        }
        String piece;
        if (multiline) {
            int eol = src.indexOf('\n', insertAt);
            if (eol < 0 || eol > close) eol = -1;
            if (eol >= 0) {
                String head = src.substring(0, insertAt) + prefix + src.substring(insertAt, eol + 1);
                piece = memberIndent + Jsonc.quote(key) + ": " + rawValue + (trailingComma ? "," : "") + "\n";
                return head + piece + src.substring(eol + 1);
            }
            piece = prefix + "\n" + memberIndent + Jsonc.quote(key) + ": " + rawValue;
        } else {
            piece = prefix + " " + Jsonc.quote(key) + ": " + rawValue;
        }
        return src.substring(0, insertAt) + piece + src.substring(insertAt);
    }

    private static int skipInlineSameLine(String src, int i) {
        while (i < src.length() && (src.charAt(i) == ' ' || src.charAt(i) == '\t')) i++;
        return i;
    }

    private static String indentOf(String src, Jsonc.Node node) {
        return lineIndent(src, node.start);
    }

    private static String lineIndent(String src, int offset) {
        int ls = src.lastIndexOf('\n', offset - 1) + 1;
        int i = ls;
        while (i < src.length() && (src.charAt(i) == ' ' || src.charAt(i) == '\t')) i++;
        return src.substring(ls, i);
    }

    private static String memberIndent(String src, Jsonc.Node obj) {
        Jsonc.Member last = null;
        for (Jsonc.Member m : obj.members.values()) last = m;
        return last != null ? lineIndent(src, last.keyStart) : indentOf(src, obj) + "  ";
    }

    private static String nest(List<String> names, String raw, String indent) {
        if (names.isEmpty()) return raw;
        return "{\n" + indent + "  " + Jsonc.quote(names.get(0)) + ": "
                + nest(names.subList(1, names.size()), raw, indent + "  ") + "\n" + indent + "}";
    }
}
