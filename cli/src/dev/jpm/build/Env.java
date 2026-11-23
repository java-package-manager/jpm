package dev.jpm.build;

import dev.jpm.util.Fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Environment variables loaded the way Bun does it: {@code .env}, then {@code .env.development} or
 * {@code .env.production} depending on the command, then {@code .env.local}, which overrides the
 * other files. Variables already set in the process environment take precedence over all files, so CI
 * secrets are never shadowed by a committed file.
 */
public final class Env {
    public enum Mode {
        DEVELOPMENT("development"), PRODUCTION("production");
        public final String suffix;

        Mode(String s) {
            suffix = s;
        }
    }

    private Env() {
    }

    /**
     * Variables from the files only.
     */
    public static Map<String, String> files(Path dir, Mode mode) {
        Map<String, String> out = new LinkedHashMap<>();
        load(dir.resolve(".env"), out);
        load(dir.resolve(".env." + mode.suffix), out);
        load(dir.resolve(".env.local"), out);
        return out;
    }

    /**
     * Files plus the process environment, which wins.
     */
    public static Map<String, String> load(Path dir, Mode mode) {
        Map<String, String> out = files(dir, mode);
        out.putAll(System.getenv());
        return out;
    }

    /**
     * Variables to pass to a child process: file values that the process environment does not already set.
     */
    public static Map<String, String> forChild(Path dir, Mode mode) {
        Map<String, String> out = files(dir, mode);
        out.keySet().removeAll(System.getenv().keySet());
        return out;
    }

    static void load(Path file, Map<String, String> out) {
        if (!Files.exists(file)) return;
        parse(Fs.read(file), out);
    }

    /**
     * Reads {@code .env} text ({@code KEY=value} lines, {@code export}, quotes, multi-line quoted values) into {@code out}.
     */
    public static void parse(String text, Map<String, String> out) {
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring(7).strip();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (value.startsWith("\"") || value.startsWith("'") || value.startsWith("`")) {
                char q = value.charAt(0);
                StringBuilder sb = new StringBuilder(value.substring(1));
                while (sb.indexOf(String.valueOf(q)) < 0 && i + 1 < lines.length) sb.append('\n').append(lines[++i]);
                int end = sb.indexOf(String.valueOf(q));
                value = end >= 0 ? sb.substring(0, end) : sb.toString();
                if (q == '"') value = value.replace("\\n", "\n").replace("\\\"", "\"");
            } else {
                int hash = value.indexOf(" #");
                if (hash >= 0) value = value.substring(0, hash).strip();
                value = expand(value, out);
            }
            out.put(key, value);
        }
    }

    /**
     * Expands ${VAR} and $VAR references against earlier values and the process environment.
     */
    static String expand(String v, Map<String, String> vars) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length() && v.charAt(i + 1) == '$') {
                sb.append('$');
                i++;
                continue;
            }
            if (c != '$' || i + 1 >= v.length()) {
                sb.append(c);
                continue;
            }
            int start, end;
            if (v.charAt(i + 1) == '{') {
                start = i + 2;
                end = v.indexOf('}', start);
                if (end < 0) {
                    sb.append(c);
                    continue;
                }
                String name = v.substring(start, end);
                sb.append(lookup(name, vars));
                i = end;
            } else {
                start = i + 1;
                end = start;
                while (end < v.length() && (Character.isLetterOrDigit(v.charAt(end)) || v.charAt(end) == '_')) end++;
                if (end == start) {
                    sb.append(c);
                    continue;
                }
                sb.append(lookup(v.substring(start, end), vars));
                i = end - 1;
            }
        }
        return sb.toString();
    }

    private static String lookup(String name, Map<String, String> vars) {
        String s = System.getenv(name);
        if (s != null) return s;
        s = vars.get(name);
        return s == null ? "" : s;
    }
}
