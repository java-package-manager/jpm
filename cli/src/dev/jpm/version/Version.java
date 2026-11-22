package dev.jpm.version;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A version string ordered the way Maven orders versions, which also gives the usual semver order
 * for plain {@code major.minor.patch} versions. Used for Maven and Git dependencies alike so conflicts
 * across both are resolved the same way.
 */
public final class Version implements Comparable<Version> {
    private final String raw;
    private final List<Object> items;

    private Version(String raw) {
        this.raw = raw;
        this.items = tokenize(raw);
    }

    /** Parses a version; any text is a version (ADR 0006). */
    public static Version of(String s) {
        return new Version(s.trim());
    }

    @Override
    public String toString() { return raw; }

    @Override
    public boolean equals(Object o) {
        return o instanceof Version v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() { return normalized().hashCode(); }

    private String normalized() {
        return items.toString();
    }

    /** True if this looks like a pre-release (alpha, beta, rc, snapshot, milestone). */
    public boolean isPreRelease() {
        for (Object o : items) {
            if (o instanceof String s && qualifierRank(s) < 5) return true;
        }
        return false;
    }

    /** True for {@code -SNAPSHOT} versions. */
    public boolean isSnapshot() {
        return raw.toUpperCase(Locale.ROOT).endsWith("SNAPSHOT");
    }

    private static List<Object> tokenize(String v) {
        int plus = v.indexOf('+');
        if (plus >= 0) v = v.substring(0, plus);
        if (v.startsWith("v") && v.length() > 1 && Character.isDigit(v.charAt(1))) v = v.substring(1);
        List<Object> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        Boolean digit = null;
        for (int i = 0; i <= v.length(); i++) {
            char c = i < v.length() ? v.charAt(i) : '.';
            boolean sep = c == '.' || c == '-' || c == '_';
            boolean isDigit = Character.isDigit(c);
            if (sep || (digit != null && cur.length() > 0 && isDigit != digit)) {
                if (cur.length() > 0) {
                    Object it = item(cur.toString(), digit);
                    if (it instanceof String) {
                        while (!out.isEmpty() && out.get(out.size() - 1) instanceof Long l && l == 0L) {
                            out.remove(out.size() - 1);
                        }
                    }
                    out.add(it);
                }
                cur.setLength(0);
                digit = null;
                if (sep) continue;
            }
            cur.append(c);
            digit = isDigit;
        }
        while (!out.isEmpty()) {
            Object last = out.get(out.size() - 1);
            if ((last instanceof Long l && l == 0L) || (last instanceof String s && qualifierRank(s) == 5)) {
                out.remove(out.size() - 1);
            } else break;
        }
        return out;
    }

    private static Object item(String s, Boolean digit) {
        if (Boolean.TRUE.equals(digit)) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return Long.MAX_VALUE; }
        }
        String q = s.toLowerCase(Locale.ROOT);
        return switch (q) {
            case "a" -> "alpha";
            case "b" -> "beta";
            case "m" -> "milestone";
            case "cr" -> "rc";
            case "ga", "final", "release" -> "";
            default -> q;
        };
    }

    /** alpha < beta < milestone < rc < snapshot < (release) < sp < unknown. */
    private static int qualifierRank(String q) {
        return switch (q) {
            case "alpha" -> 0;
            case "beta" -> 1;
            case "milestone" -> 2;
            case "rc" -> 3;
            case "snapshot" -> 4;
            case "" -> 5;
            case "sp" -> 6;
            default -> 7;
        };
    }

    @Override
    public int compareTo(Version o) {
        int n = Math.max(items.size(), o.items.size());
        for (int i = 0; i < n; i++) {
            Object a = i < items.size() ? items.get(i) : null;
            Object b = i < o.items.size() ? o.items.get(i) : null;
            int c = compareItem(a, b);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int compareItem(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -compareItem(b, null);
        if (a instanceof Long la) {
            if (b == null) return la == 0 ? 0 : 1;
            if (b instanceof Long lb) return Long.compare(la, lb);
            return 1;
        }
        String sa = (String) a;
        if (b == null) return Integer.compare(qualifierRank(sa), 5);
        if (b instanceof Long) return -1;
        String sb = (String) b;
        int ra = qualifierRank(sa), rb = qualifierRank(sb);
        if (ra != rb) return Integer.compare(ra, rb);
        return ra == 7 ? sa.compareTo(sb) : 0;
    }
}
