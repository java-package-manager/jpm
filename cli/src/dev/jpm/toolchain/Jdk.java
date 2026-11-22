package dev.jpm.toolchain;

import dev.jpm.util.Exec;
import dev.jpm.util.Fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A JDK on this machine. Discovery looks at {@code JAVA_HOME}, JDKs installed by jpm in
 * {@code ~/.jpm/jdks}, {@code javac} on PATH and the usual install locations.
 *
 * @param distribution Foojay distribution name ({@code temurin}, {@code graalvm_community}, ...),
 *                     or {@code openjdk} when the vendor is not recognized
 * @param managed      installed by jpm (and removable with {@code jpm java remove})
 */
public record Jdk(Path home, int major, String version, boolean graal, String distribution, boolean managed) {

    public static final String METADATA = ".jpm-jdk";

    static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    public Path bin(String tool) {
        Path base = home.resolve("bin");
        if (!windows()) return base.resolve(tool);
        for (String ext : new String[]{".exe", ".cmd", ".bat"}) {
            Path p = base.resolve(tool + ext);
            if (Files.exists(p)) return p;
        }
        return base.resolve(tool + ".exe");
    }

    public String java() { return bin("java").toString(); }

    /** {@code path} with {@code .exe} appended on Windows. */
    public static Path windowsExe(Path p) {
        return windows() ? p.resolveSibling(p.getFileName() + ".exe") : p;
    }
    public String javac() { return bin("javac").toString(); }

    public boolean hasNativeImage() {
        return Files.exists(bin("native-image"));
    }

    /** A short name such as {@code temurin-21.0.5} or {@code graalvm_community-21.0.2}. */
    public String id() {
        return distribution + "-" + version;
    }

    private static List<Jdk> cached;

    /** Forgets discovered JDKs, after installing or removing one. */
    public static synchronized void reset() {
        cached = null;
    }

    public static Path managedRoot() {
        return Fs.home().resolve("jdks");
    }

    public static synchronized List<Jdk> discover() {
        if (cached != null) return cached;
        List<Path> homes = new ArrayList<>();
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isBlank()) homes.add(Path.of(jh));
        for (Path m : children(managedRoot())) {
            Jdk nested = Toolchain.probeInstalled(m);
            homes.add(nested != null ? nested.home() : m);
        }
        Path onPath = Exec.which("javac", Fs.home().resolve("bin"));
        if (onPath != null) {
            try {
                homes.add(onPath.toRealPath().getParent().getParent());
            } catch (IOException ignored) {
            }
        }
        String uh = System.getProperty("user.home");
        homes.addAll(children(Path.of("/usr/lib/jvm")));
        homes.addAll(children(Path.of("/opt/java")));
        homes.addAll(children(Path.of(uh, ".sdkman", "candidates", "java")));
        homes.addAll(children(Path.of(uh, ".jdks")));
        homes.addAll(children(Path.of("/Library/Java/JavaVirtualMachines")));
        homes.addAll(children(Path.of(uh, "Library/Java/JavaVirtualMachines")));
        homes.addAll(children(Path.of("C:\\Program Files\\Java")));
        homes.addAll(children(Path.of("C:\\Program Files\\Eclipse Adoptium")));

        Map<Path, Jdk> found = new LinkedHashMap<>();
        for (Path h : homes) {
            for (Path cand : List.of(h, h.resolve("Contents").resolve("Home"))) {
                try {
                    if (!Files.isDirectory(cand)) continue;
                    Path real = cand.toRealPath();
                    if (found.containsKey(real)) continue;
                    Jdk j = probe(real);
                    if (j != null) {
                        found.put(real, j);
                        break;
                    }
                } catch (IOException ignored) {
                }
            }
        }
        cached = new ArrayList<>(found.values());
        return cached;
    }

    private static List<Path> children(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith(".")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Reads a JDK home, or returns null if it is not one. */
    public static Jdk probe(Path home) {
        Path release = home.resolve("release");
        if (!Files.exists(home.resolve("bin").resolve(windows() ? "javac.exe" : "javac"))) return null;
        if (!Files.exists(release)) return null;
        String text = Fs.read(release);
        String version = prop(text, "JAVA_VERSION");
        if (version == null) return null;
        String implementor = prop(text, "IMPLEMENTOR");
        boolean graal = text.contains("GRAALVM_VERSION")
                || (implementor != null && implementor.toLowerCase(Locale.ROOT).contains("graalvm"))
                || Files.exists(home.resolve("bin").resolve(windows() ? "native-image.cmd" : "native-image"));
        String v = version.startsWith("1.") ? version.substring(2) : version;
        int major;
        try {
            major = Integer.parseInt(v.split("[.+_-]")[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        Path meta = metadataFile(home);
        String distribution = null;
        boolean managed = false;
        if (meta != null) {
            distribution = Fs.read(meta).lines().findFirst().orElse("").trim();
            managed = true;
        }
        if (distribution == null || distribution.isEmpty()) distribution = distributionOf(implementor, text, graal);
        return new Jdk(home, major, version, graal, distribution, managed);
    }

    /** The metadata file for a jpm-installed JDK. */
    private static Path metadataFile(Path home) {
        Path p = home;
        for (int i = 0; i < 4 && p != null; i++, p = p.getParent()) {
            Path m = p.resolve(METADATA);
            if (Files.exists(m)) return m;
        }
        return null;
    }

    static String distributionOf(String implementor, String release, boolean graal) {
        String i = implementor == null ? "" : implementor.toLowerCase(Locale.ROOT);
        if (graal) {
            if (i.contains("red hat")) return "mandrel";
            if (i.contains("bellsoft")) return "liberica_native";
            if (i.contains("oracle")) return "graalvm";
            return "graalvm_community";
        }
        if (i.contains("adoptium") || i.contains("adoptopenjdk")) return "temurin";
        if (i.contains("azul")) return "zulu";
        if (i.contains("amazon")) return "corretto";
        if (i.contains("bellsoft")) return "liberica";
        if (i.contains("microsoft")) return "microsoft";
        if (i.contains("sap")) return "sap_machine";
        if (i.contains("jetbrains")) return "jetbrains";
        if (i.contains("red hat")) return "redhat";
        if (i.contains("alibaba")) return "dragonwell";
        if (i.contains("ibm") || i.contains("eclipse openj9")) return "semeru";
        if (i.contains("oracle") && release.contains("BUILD_TYPE=\"commercial\"")) return "oracle";
        return "openjdk";
    }

    private static String prop(String text, String key) {
        for (String line : text.split("\n")) {
            if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim().replace("\"", "");
        }
        return null;
    }
}
