package dev.jpm.maven;

import dev.jpm.version.Version;
import dev.jpm.version.VersionSpecs;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which {@code <profile>}s of a POM are active, as Maven decides it: by {@code <activation>} with {@code jdk}, {@code os},
 * {@code property} and {@code file} (all that are given must hold), and {@code activeByDefault} when no other
 * profile of the POM is active. {@code file} is only evaluated for POMs on disk: a POM from a repository has no
 * project folder.
 */
public final class Profiles {
    private Profiles() {
    }

    /**
     * What activation is decided against.
     *
     * @param jdk        the Java version, such as {@code 21} or {@code 1.8.0_402}
     * @param osName     the OS name, as {@code os.name} gives it
     * @param osArch     the architecture, as {@code os.arch} gives it
     * @param osVersion  the OS version, as {@code os.version} gives it
     * @param properties the properties {@code <property>} activation reads (system properties, and the environment as {@code env.X})
     */
    public record Context(String jdk, String osName, String osArch, String osVersion, Map<String, String> properties) {
        /**
         * This machine, with the Java version {@code JPM_POM_JDK} names, else jpm's own.
         */
        public static Context current() {
            Map<String, String> props = new LinkedHashMap<>();
            for (String n : System.getProperties().stringPropertyNames()) props.put(n, System.getProperty(n));
            for (Map.Entry<String, String> e : System.getenv().entrySet()) props.put("env." + e.getKey(), e.getValue());
            String jdk = System.getenv("JPM_POM_JDK");
            return new Context(jdk != null && !jdk.isBlank() ? jdk : System.getProperty("java.version", "21"),
                    System.getProperty("os.name", ""), System.getProperty("os.arch", ""), System.getProperty("os.version", ""), props);
        }

        /**
         * This machine, with the Java version of a manifest's {@code "java"} (such as {@code temurin-17}), when it names one.
         */
        public static Context forJava(String spec) {
            Context c = current();
            if (System.getenv("JPM_POM_JDK") != null || spec == null) return c;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(\\.\\d+)*)").matcher(spec);
            return m.find() ? new Context(m.group(1), c.osName(), c.osArch(), c.osVersion(), c.properties()) : c;
        }
    }

    /**
     * The active profiles of a {@code <project>} element.
     */
    public static List<Element> active(Element project, Path pomFile, Context ctx) {
        Element profiles = Pom.child(project, "profiles");
        if (profiles == null) return List.of();
        List<Element> active = new ArrayList<>(), byDefault = new ArrayList<>();
        for (Element p : Pom.children(profiles, "profile")) {
            Element a = Pom.child(p, "activation");
            if (a == null) continue;
            if ("true".equals(Pom.text(a, "activeByDefault"))) byDefault.add(p);
            if (activates(a, pomFile, ctx)) active.add(p);
        }
        return active.isEmpty() ? byDefault : active;
    }

    static boolean activates(Element a, Path pomFile, Context ctx) {
        boolean any = false;
        String jdk = Pom.text(a, "jdk");
        if (jdk != null) {
            any = true;
            if (!jdk(jdk, ctx.jdk())) return false;
        }
        Element os = Pom.child(a, "os");
        if (os != null) {
            any = true;
            if (!os(os, ctx)) return false;
        }
        Element prop = Pom.child(a, "property");
        if (prop != null) {
            any = true;
            if (!property(Pom.text(prop, "name"), Pom.text(prop, "value"), ctx.properties())) return false;
        }
        Element file = Pom.child(a, "file");
        if (file != null) {
            any = true;
            if (pomFile == null) return false;
            String exists = Pom.text(file, "exists"), missing = Pom.text(file, "missing");
            Path dir = pomFile.getParent();
            if (exists != null && !Files.exists(dir.resolve(basedir(exists)))) return false;
            if (missing != null && Files.exists(dir.resolve(basedir(missing)))) return false;
        }
        return any;
    }

    private static String basedir(String path) {
        return path.replace("${basedir}/", "").replace("${project.basedir}/", "").replace("${basedir}", ".").replace("${project.basedir}", ".");
    }

    static boolean jdk(String spec, String jdk) {
        spec = spec.strip();
        boolean negate = spec.startsWith("!");
        if (negate) spec = spec.substring(1).strip();
        String version = normalizeJdk(jdk);
        boolean match;
        if (spec.startsWith("[") || spec.startsWith("(")) {
            StringBuilder ranges = new StringBuilder();
            for (String part : spec.split("(?<=[\\])]),")) {
                if (ranges.length() > 0) ranges.append(',');
                ranges.append(normalizeRange(part.strip()));
            }
            try {
                match = VersionSpecs.parse(ranges.toString()).matches(Version.of(version));
            } catch (RuntimeException e) {
                match = false;
            }
        } else {
            String want = normalizeJdk(spec);
            match = version.equals(want) || version.startsWith(want + ".");
        }
        return negate != match;
    }

    private static String normalizeRange(String r) {
        String open = r.substring(0, 1), close = r.substring(r.length() - 1);
        String[] ends = r.substring(1, r.length() - 1).split(",", -1);
        List<String> out = new ArrayList<>();
        for (String e : ends) out.add(e.isBlank() ? "" : normalizeJdk(e.strip()));
        return open + String.join(",", out) + close;
    }

    private static String normalizeJdk(String v) {
        v = v.strip();
        int us = v.indexOf('_');
        if (us >= 0) v = v.substring(0, us);
        int dash = v.indexOf('-');
        if (dash >= 0) v = v.substring(0, dash);
        int plus = v.indexOf('+');
        if (plus >= 0) v = v.substring(0, plus);
        return v.startsWith("1.") && v.length() > 2 ? v.substring(2) : v;
    }

    private static boolean os(Element os, Context ctx) {
        String name = ctx.osName().toLowerCase(Locale.ROOT), arch = ctx.osArch().toLowerCase(Locale.ROOT),
                version = ctx.osVersion().toLowerCase(Locale.ROOT);
        return test(Pom.text(os, "name"), v -> name.equals(v))
                && test(Pom.text(os, "family"), v -> family(v, name))
                && test(Pom.text(os, "arch"), v -> arch.equals(v))
                && test(Pom.text(os, "version"), v -> version.equals(v));
    }

    private static boolean test(String value, java.util.function.Predicate<String> holds) {
        if (value == null) return true;
        String v = value.strip().toLowerCase(Locale.ROOT);
        boolean negate = v.startsWith("!");
        if (negate) v = v.substring(1);
        return negate != holds.test(v);
    }

    private static boolean family(String family, String name) {
        boolean windows = name.contains("windows"), mac = name.contains("mac") || name.contains("darwin");
        return switch (family) {
            case "windows", "win32", "dos" -> windows;
            case "mac" -> mac;
            case "unix" -> !windows && !name.startsWith("openvms") && !name.contains("os/2");
            case "linux" -> name.contains("linux");
            case "os/2" -> name.contains("os/2");
            default -> name.contains(family);
        };
    }

    static boolean property(String name, String value, Map<String, String> props) {
        if (name == null || name.isBlank()) return false;
        name = name.strip();
        if (name.startsWith("!")) return !props.containsKey(name.substring(1)) && value == null;
        String actual = props.get(name);
        if (value == null) return actual != null;
        value = value.strip();
        if (value.startsWith("!")) return !value.substring(1).equals(actual);
        return value.equals(actual);
    }
}
