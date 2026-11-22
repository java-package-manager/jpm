package dev.jpm.version;

import java.util.ArrayList;
import java.util.List;

/**
 * Maven version range: {@code [1.0,2.0)}, {@code [1.5,)}, {@code (,1.0],[1.2,)}, {@code [1.0]}, and
 * Gradle's way of writing open ends with reversed brackets: {@code ]1.0,2.0[} is {@code (1.0,2.0)} and
 * {@code [1.0,2.0[} is {@code [1.0,2.0)}. A bare version such as {@code 1.18.34} is exact. This is the
 * syntax POMs use; {@link VersionSpecs} reads every syntax a manifest may use.
 */
public final class MavenRange implements VersionSpec {
    private record Bound(Version lower, boolean lowerInclusive, Version upper, boolean upperInclusive) {
        boolean matches(Version v) {
            if (lower != null) {
                int c = v.compareTo(lower);
                if (c < 0 || (c == 0 && !lowerInclusive)) return false;
            }
            if (upper != null) {
                int c = v.compareTo(upper);
                if (c > 0 || (c == 0 && !upperInclusive)) return false;
            }
            return true;
        }
    }

    private final String text;
    private final List<Bound> bounds;
    private final boolean exact;

    private MavenRange(String text, List<Bound> bounds, boolean exact) {
        this.text = text;
        this.bounds = bounds;
        this.exact = exact;
    }

    public static MavenRange parse(String text) {
        String s = text.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("Empty version");
        if (!isRange(s)) {
            if (s.contains(",") || s.contains(" ") || s.startsWith("^") || s.startsWith("~")) {
                throw new IllegalArgumentException("Invalid Maven version \"" + text
                        + "\". Maven dependencies take an exact version or a Maven range like [1.0,2.0)");
            }
            Version v = Version.of(s);
            return new MavenRange(text, List.of(new Bound(v, true, v, true)), true);
        }
        List<Bound> bounds = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char open = s.charAt(i);
            if (open != '[' && open != '(' && open != ']') throw new IllegalArgumentException("Invalid range \"" + text + "\"");
            int close = i + 1;
            while (close < s.length() && s.charAt(close) != ']' && s.charAt(close) != ')' && s.charAt(close) != '[') close++;
            if (close >= s.length()) throw new IllegalArgumentException("Unclosed range \"" + text + "\"");
            String inner = s.substring(i + 1, close).trim();
            boolean li = open == '[';
            boolean ui = s.charAt(close) == ']';
            int comma = inner.indexOf(',');
            if (comma < 0) {
                if (!li || !ui) throw new IllegalArgumentException("Single-version range must use [] in \"" + text + "\"");
                Version v = Version.of(inner);
                bounds.add(new Bound(v, true, v, true));
            } else {
                String lo = inner.substring(0, comma).trim();
                String hi = inner.substring(comma + 1).trim();
                bounds.add(new Bound(lo.isEmpty() ? null : Version.of(lo), li, hi.isEmpty() ? null : Version.of(hi), ui));
            }
            i = close + 1;
            while (i < s.length() && (s.charAt(i) == ',' || s.charAt(i) == ' ')) i++;
        }
        boolean exact = bounds.size() == 1 && bounds.get(0).lower != null && bounds.get(0).lower.equals(bounds.get(0).upper);
        return new MavenRange(text, bounds, exact);
    }

    /** True if a POM version string is a range rather than a soft requirement. */
    public static boolean isRange(String s) {
        s = s.trim();
        return s.startsWith("[") || s.startsWith("(") || s.startsWith("]");
    }

    @Override
    public boolean matches(Version v) {
        for (Bound b : bounds) if (b.matches(v)) return true;
        return false;
    }

    @Override
    public String text() { return text; }

    @Override
    public boolean isExact() { return exact; }

    @Override
    public Version exactVersion() { return exact ? bounds.get(0).lower : null; }

    @Override
    public String toString() { return text; }
}
