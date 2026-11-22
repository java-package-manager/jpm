package dev.jpm.version;

import dev.jpm.version.impl.MavenRange;
import dev.jpm.version.impl.NpmRange;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads a version requirement in any of the syntaxes jpm accepts, for Maven and Git dependencies alike:
 *
 * <ul>
 *   <li>Maven: {@code 1.2.3} (exact), {@code [1.0,2.0)}, {@code [1.5,)}, {@code (,1.0]}, {@code [1.0]},
 *       {@code (,1.0],[1.2,)}, and {@code RELEASE} / {@code LATEST};</li>
 *   <li>Gradle: {@code 1.+}, {@code 1.2.+}, {@code +} (the newest of any kind, as in Gradle), {@code ]1.0,2.0[}, {@code [1.0,2.0[},
 *       {@code latest.release}, {@code latest.milestone}, {@code latest.integration}, and strict
 *       {@code 1.2!!} or {@code [1.0,2.0)!!1.5} (that range, preferring 1.5 when it is available);</li>
 *   <li>npm: {@code ^1.2}, {@code ~1.2.3}, {@code ~1}, {@code >=0.1}, {@code >1.0 <=2.0}, {@code 1.x},
 *       {@code 1.2.*}, {@code *}, {@code 1.0 - 2.0}, {@code ^1 || ^2}.</li>
 * </ul>
 *
 * A bare version is exact for Maven dependencies; for Git dependencies (versions are tags) it is the npm
 * reading, where {@code 1.2} means {@code 1.2.x}.
 */
public final class VersionSpecs {
    private VersionSpecs() {}

    /** Reads a requirement of a Maven dependency: a bare version is exact. */
    public static VersionSpec parse(String text) {
        return parse(text, false);
    }

    /** Reads a requirement; {@code bareIsRange} reads a bare version the npm way (Git tags). */
    public static VersionSpec parse(String text, boolean bareIsRange) {
        String s = text.trim();
        int strict = s.indexOf("!!");
        if (strict >= 0) {
            String prefer = s.substring(strict + 2).trim();
            s = s.substring(0, strict).trim();
            if (s.isEmpty()) throw new IllegalArgumentException("Invalid version \"" + text + "\": nothing before !!");
            VersionSpec inner = parse(s, false);
            return prefer.isEmpty() ? inner : new Preferring(text, inner, Version.of(prefer));
        }
        if (s.isEmpty()) throw new IllegalArgumentException("Empty version");
        Any any = Any.named(s, text);
        if (any != null) return any;
        if (MavenRange.isRange(s)) return MavenRange.parse(s);
        if (s.endsWith("+") && s.length() > 1 && !s.contains(" ")) return GradlePrefix.parse(s, text);
        if (looksLikeNpm(s)) return NpmRange.parse(s);
        return bareIsRange && NpmRange.looksLikeRange(s) ? NpmRange.parse(s) : MavenRange.parse(s);
    }

    /** True for a plain version (1.2.3, 33.0-jre), which a POM uses as a preference rather than a requirement. */
    public static boolean isBareVersion(String s) {
        if (s == null || s.isBlank()) return true;
        s = s.trim();
        return !(s.contains("!!") || MavenRange.isRange(s) || Any.named(s, s) != null || s.endsWith("+") || looksLikeNpm(s));
    }

    /** True if the text is a version requirement in one of the syntaxes (as opposed to a Git tag or branch name). */
    public static boolean looksLikeSpec(String s) {
        s = s.trim();
        if (s.isEmpty()) return false;
        if (Any.named(s, s) != null || MavenRange.isRange(s) || s.contains("!!")) return true;
        if (s.matches("(\\d+\\.)*\\+")) return true;
        return NpmRange.looksLikeRange(s);
    }

    /** npm operators, wildcards, hyphen ranges or alternatives: anything a bare Maven version is not. */
    private static boolean looksLikeNpm(String s) {
        if (s.startsWith("^") || s.startsWith("~") || s.startsWith("<") || s.startsWith(">") || s.startsWith("=")) return true;
        if (s.contains("||") || s.contains(" - ") || s.contains(" ")) return true;
        return s.matches("v?\\d+(\\.\\d+)*(\\.[xX*])+") || s.equals("x") || s.equals("X");
    }

    /** A requirement with a preferred version inside it (Gradle's {@code strictly ... prefer}). */
    static final class Preferring implements VersionSpec {
        private final String text;
        private final VersionSpec inner;
        private final Version preferred;

        Preferring(String text, VersionSpec inner, Version preferred) {
            if (!inner.matches(preferred)) {
                throw new IllegalArgumentException("Invalid version \"" + text + "\": " + preferred + " is outside " + inner.text());
            }
            this.text = text;
            this.inner = inner;
            this.preferred = preferred;
        }

        @Override
        public boolean matches(Version v) { return inner.matches(v); }

        @Override
        public Optional<Version> best(Collection<Version> candidates) {
            for (Version v : candidates) if (v.equals(preferred)) return Optional.of(v);
            return inner.best(candidates);
        }

        @Override
        public String text() { return text; }

        @Override
        public boolean isExact() { return inner.isExact(); }

        @Override
        public Version exactVersion() { return inner.exactVersion(); }

        @Override
        public String toString() { return text; }
    }

    /** {@code *}, {@code +}, {@code latest} and the Maven and Gradle keywords: any version, of some kind. */
    static final class Any implements VersionSpec {
        private final String text;
        private final boolean preReleases;
        private final boolean snapshots;

        private Any(String text, boolean preReleases, boolean snapshots) {
            this.text = text;
            this.preReleases = preReleases;
            this.snapshots = snapshots;
        }

        static Any named(String s, String text) {
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "+" -> new Any(text, true, true);
                case "*", "x", "latest", "latest.release", "release" ->
                        s.equals("LATEST") ? new Any(text, true, true) : new Any(text, false, false);
                case "latest.milestone" -> new Any(text, true, false);
                case "latest.integration" -> new Any(text, true, true);
                default -> null;
            };
        }

        @Override
        public boolean matches(Version v) {
            return (preReleases || !v.isPreRelease() || (snapshots && v.isSnapshot())) && (snapshots || !v.isSnapshot());
        }

        @Override
        public Optional<Version> best(Collection<Version> candidates) {
            Version best = null;
            for (Version v : candidates) if (matches(v) && (best == null || v.compareTo(best) > 0)) best = v;
            return Optional.ofNullable(best);
        }

        @Override
        public String text() { return text; }

        @Override
        public boolean isExact() { return false; }

        @Override
        public String toString() { return text; }
    }

    /**
     * Gradle's prefix versions: {@code 1.+} is every version starting with {@code 1.}, {@code 1.2.+} with
     * {@code 1.2.}. As in Gradle, the newest one is chosen whatever it is, snapshots and pre-releases too.
     */
    static final class GradlePrefix implements VersionSpec {
        private final String text;
        private final String prefix;

        private GradlePrefix(String text, String prefix) {
            this.text = text;
            this.prefix = prefix;
        }

        static GradlePrefix parse(String s, String text) {
            String prefix = s.substring(0, s.length() - 1);
            if (prefix.startsWith("v") && prefix.length() > 1 && Character.isDigit(prefix.charAt(1))) prefix = prefix.substring(1);
            if (!prefix.matches("[0-9A-Za-z][0-9A-Za-z._-]*")) {
                throw new IllegalArgumentException("Invalid version \"" + text + "\": a + goes after a version prefix, as in 1.+ or 1.2.+");
            }
            return new GradlePrefix(text, prefix);
        }

        @Override
        public Optional<Version> best(Collection<Version> candidates) {
            Version best = null;
            for (Version v : candidates) if (matches(v) && (best == null || v.compareTo(best) > 0)) best = v;
            return Optional.ofNullable(best);
        }

        @Override
        public boolean matches(Version v) {
            String raw = v.toString();
            if (raw.startsWith("v") && raw.length() > 1 && Character.isDigit(raw.charAt(1))) raw = raw.substring(1);
            return raw.startsWith(prefix);
        }

        @Override
        public String text() { return text; }

        @Override
        public boolean isExact() { return false; }

        @Override
        public String toString() { return text; }
    }
}
