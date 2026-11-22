package dev.jpm.toolchain;

import dev.jpm.util.JpmException;

import java.util.Locale;
import java.util.Map;

/**
 * A requested Java toolchain as written in the manifest's {@code java} field or on the command line:
 * {@code 21}, {@code 21.0.5}, {@code temurin-17}, {@code graalvm-21}, {@code zulu-21.0.5}.
 *
 * <p>Without a distribution any JDK of that version will do; jpm installs Temurin when none is present.
 * {@code graalvm} means GraalVM Community; {@code oracle-graalvm} means Oracle GraalVM.
 *
 * @param distribution Foojay distribution name, or null for any
 * @param version      a major version ("21") or an exact one ("21.0.5"), or null for any
 */
public record JavaSpec(String distribution, String version) {

    public static final String DEFAULT_DISTRIBUTION = "temurin";
    public static final String DEFAULT_GRAALVM = "graalvm_community";

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("graalvm", "graalvm_community"),
            Map.entry("graalvm-ce", "graalvm_community"),
            Map.entry("graalvm-community", "graalvm_community"),
            Map.entry("graal", "graalvm_community"),
            Map.entry("oracle-graalvm", "graalvm"),
            Map.entry("graalvm-oracle", "graalvm"),
            Map.entry("adoptium", "temurin"),
            Map.entry("sapmachine", "sap_machine"),
            Map.entry("sap-machine", "sap_machine"),
            Map.entry("jbr", "jetbrains"),
            Map.entry("liberica-nik", "liberica_native"));

    /** Distribution names Foojay knows, plus "openjdk" for JDKs from unrecognized vendors. */
    public static final java.util.Set<String> KNOWN = java.util.Set.of("aoj", "aoj_openj9", "bisheng", "corretto",
            "dragonwell", "gluon_graalvm", "graalvm", "graalvm_community", "jetbrains", "kona", "liberica",
            "liberica_native", "mandrel", "microsoft", "ojdk_build", "openlogic", "oracle", "oracle_open_jdk", "redhat",
            "sap_machine", "semeru", "semeru_certified", "temurin", "trava", "zulu", "openjdk");

    /** Distributions whose JDKs include native-image. */
    private static final java.util.Set<String> GRAAL = java.util.Set.of("graalvm_community", "graalvm", "mandrel",
            "liberica_native", "gluon_graalvm");

    public static JavaSpec any() {
        return new JavaSpec(null, null);
    }

    public static JavaSpec parse(String s) {
        if (s == null || s.isBlank()) return any();
        String t = s.trim().toLowerCase(Locale.ROOT);
        String dist = null, version = t;
        int dash = t.lastIndexOf('-');
        if (!Character.isDigit(t.charAt(0))) {
            if (dash < 0 || dash + 1 >= t.length() || !Character.isDigit(t.charAt(dash + 1))) {
                dist = t;
                version = null;
            } else {
                dist = t.substring(0, dash);
                version = t.substring(dash + 1);
            }
        }
        if (version != null && !version.matches("\\d+(\\.\\d+){0,3}([+._-][0-9A-Za-z.+_-]*)?")) {
            throw new JpmException("Invalid Java version \"" + s + "\" (expected e.g. 21, 21.0.5, temurin-17 or graalvm-21)");
        }
        if (version != null && version.startsWith("1.8")) version = "8" + version.substring(3);
        if (dist != null) dist = ALIASES.getOrDefault(dist, dist.replace('-', '_'));
        if (dist != null && !KNOWN.contains(dist)) {
            throw new JpmException("Unknown Java distribution in \"" + s + "\". Known: temurin, zulu, corretto, liberica,"
                    + " microsoft, sap_machine, semeru, oracle, graalvm (Community), oracle-graalvm, mandrel, jetbrains, ...");
        }
        return new JavaSpec(dist, version);
    }

    public Integer major() {
        if (version == null) return null;
        return Integer.parseInt(version.split("[.+_-]")[0]);
    }

    public boolean exact() {
        return version != null && version.contains(".");
    }

    public boolean graal() {
        return distribution != null && GRAAL.contains(distribution);
    }

    public static boolean isGraal(String distribution) {
        return GRAAL.contains(distribution);
    }

    /** The same version with a GraalVM distribution, for native builds. */
    public JavaSpec asGraal() {
        return graal() ? this : new JavaSpec(DEFAULT_GRAALVM, version);
    }

    public String installDistribution() {
        if (distribution == null) return DEFAULT_DISTRIBUTION;
        return distribution.equals("openjdk") ? "oracle_open_jdk" : distribution;
    }

    public boolean matches(Jdk j) {
        if (version != null) {
            if (exact()) {
                if (!versionMatches(j.version(), version)) return false;
            } else if (j.major() != major()) {
                return false;
            }
        }
        return distribution == null || distribution.equals(j.distribution());
    }

    /** "21.0.5" matches "21.0.5", "21.0.5+11" and "21.0.5.1"; "21.0" matches any 21.0.x. */
    static boolean versionMatches(String actual, String wanted) {
        if (actual.equals(wanted)) return true;
        if (!actual.startsWith(wanted)) return false;
        char next = actual.charAt(wanted.length());
        return next == '.' || next == '+' || next == '_' || next == '-';
    }

    @Override
    public String toString() {
        if (distribution == null) return version == null ? "any JDK" : version;
        String d = distribution.equals("graalvm_community") ? "graalvm" : distribution;
        return version == null ? d : d + "-" + version;
    }
}
