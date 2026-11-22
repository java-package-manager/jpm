package dev.jpm.toolchain;

import dev.jpm.build.Jars;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.Http;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;
import dev.jpm.version.Version;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds and installs JDKs. Installed JDKs live in {@code ~/.jpm/jdks/<distribution>-<version>}; nothing
 * outside that folder is changed (no PATH or JAVA_HOME edits).
 */
public final class Toolchain {
    private Toolchain() {}

    /** True unless automatic installation is switched off with {@code JPM_NO_INSTALL}. */
    public static boolean autoInstall() {
        return System.getenv("JPM_NO_INSTALL") == null && !Fs.offline();
    }

    /** The best installed JDK for a spec, or null. JAVA_HOME wins among equals; otherwise the newest. */
    public static Jdk find(JavaSpec spec) {
        List<Jdk> all = Jdk.discover();
        List<Jdk> matching = all.stream().filter(spec::matches).toList();
        if (matching.isEmpty()) return null;
        Jdk javaHome = all.get(0).home().equals(javaHomePath()) ? all.get(0) : null;
        if (javaHome != null && matching.contains(javaHome) && spec.version() != null) return javaHome;
        if (spec.version() == null && javaHome != null && matching.contains(javaHome)) return javaHome;
        return matching.stream().max(Comparator.comparing((Jdk j) -> Version.of(j.version()))).orElseThrow();
    }

    private static Path javaHomePath() {
        String jh = System.getenv("JAVA_HOME");
        if (jh == null || jh.isBlank()) return null;
        try {
            return Path.of(jh).toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    /** Finds a JDK for the spec, installing it if needed and allowed. */
    public static Jdk require(JavaSpec spec) {
        Jdk j = find(spec);
        if (j != null) return j;
        if (spec.version() == null && spec.distribution() == null) {
            throw new JpmException("No JDK found. Install one with `jpm java install 21`, or set \"java\" in project.jsonc"
                    + " to have jpm install it.");
        }
        if (!autoInstall()) {
            throw new JpmException(spec + " is not installed" + (Fs.offline() ? " and jpm is offline" : "")
                    + ". Installed: " + describe(Jdk.discover()) + ". Run `jpm java install " + spec + "`.");
        }
        Log.step("Installing", spec + " (not found on this machine)");
        return install(spec);
    }

    private static String describe(List<Jdk> all) {
        if (all.isEmpty()) return "none";
        return String.join(", ", all.stream().map(Jdk::id).toList());
    }

    public static Jdk install(JavaSpec spec) {
        Foojay.Package pkg = Foojay.find(spec);
        Path root = Jdk.managedRoot();
        Path target = root.resolve(pkg.distribution() + "-" + pkg.javaVersion());
        Jdk existing = Files.isDirectory(target) ? probeInstalled(target) : null;
        if (existing != null) {
            Log.step("Installed", existing.id() + " is already in " + target);
            return existing;
        }
        Foojay.Download dl = Foojay.download(pkg);
        Path archive = Fs.home().resolve("downloads").resolve(pkg.filename());
        String label = pkg.distribution() + " " + pkg.javaVersion();
        Log.step("Downloading", label + Log.dim(" (" + dl.url() + ")"));
        String sha = Http.download(dl.url(), archive, label);
        if (dl.sha256() != null && !dl.sha256().equals(sha)) {
            try {
                Files.deleteIfExists(archive);
            } catch (IOException ignored) {
            }
            throw new JpmException("Checksum mismatch for " + pkg.filename() + ":\n  expected " + dl.sha256() + "\n  got      " + sha);
        }
        if (dl.sha256() == null) Log.warn("no checksum published for " + pkg.filename() + "; not verified");

        Path tmp = root.resolve(".tmp-" + pkg.distribution() + "-" + pkg.javaVersion());
        Fs.deleteTree(tmp);
        Fs.mkdirs(tmp);
        extract(archive, tmp);
        Path top = topDirectory(tmp);
        Fs.deleteTree(target);
        try {
            Files.move(top, target);
        } catch (IOException e) {
            throw new JpmException("Could not install into " + target + ": " + e.getMessage());
        }
        Fs.deleteTree(tmp);
        try {
            Files.deleteIfExists(archive);
        } catch (IOException ignored) {
        }
        Fs.write(target.resolve(Jdk.METADATA), pkg.distribution() + "\n" + pkg.javaVersion() + "\n" + dl.url() + "\n");
        Jdk.reset();
        Jdk j = probeInstalled(target);
        if (j == null) {
            throw new JpmException("Installed " + pkg.filename() + " into " + target + ", but it does not look like a JDK");
        }
        Log.step("Installed", j.id() + Log.dim(" (" + j.home() + ")"));
        return j;
    }

    /**
     * The JDK inside an installed directory: the directory itself, a macOS bundle's Contents/Home, or one
     * nested a few levels down (Zulu's macOS archives hold zulu-21.jdk/Contents/Home).
     */
    static Jdk probeInstalled(Path dir) {
        Jdk j = Jdk.probe(dir);
        if (j != null) return j;
        try (Stream<Path> s = Files.walk(dir, 4)) {
            List<Path> homes = s.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("release")) && Files.isDirectory(p.resolve("bin")))
                    .sorted(Comparator.comparingInt(Path::getNameCount))
                    .toList();
            for (Path h : homes) {
                j = Jdk.probe(h);
                if (j != null) return j;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    static void extract(Path archive, Path dir) {
        String name = archive.getFileName().toString();
        if (name.endsWith(".zip")) {
            Jars.extract(archive, dir);
            // zip does not keep permissions; make launchers executable again
            if (!Jdk.windows()) {
                try (Stream<Path> s = Files.walk(dir)) {
                    s.filter(p -> p.getParent() != null && p.getParent().getFileName() != null
                                    && (p.getParent().getFileName().toString().equals("bin") || p.toString().contains("/lib/")))
                            .filter(Files::isRegularFile)
                            .forEach(p -> p.toFile().setExecutable(true));
                } catch (IOException e) {
                    throw new JpmException("Could not prepare " + dir + ": " + e.getMessage());
                }
            }
            return;
        }
        if (Exec.which("tar") == null) throw new JpmException("Installing a JDK from " + name + " needs the `tar` command");
        Exec.Result r = Exec.capture(dir, List.of("tar", "-xzf", archive.toString()));
        if (!r.ok()) throw new JpmException("Could not extract " + name + ":\n" + r.err().strip());
    }

    /** The single top-level directory of an extracted archive (or the directory itself). */
    private static Path topDirectory(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> entries = s.filter(p -> !p.getFileName().toString().startsWith("._")).toList();
            if (entries.size() == 1 && Files.isDirectory(entries.get(0))) return entries.get(0);
            return dir;
        } catch (IOException e) {
            throw new JpmException("Could not read " + dir + ": " + e.getMessage());
        }
    }

    /** Removes installed JDKs matching the spec. Only JDKs jpm installed can be removed. */
    public static List<Jdk> remove(JavaSpec spec) {
        List<Jdk> removed = new ArrayList<>();
        for (Jdk j : Jdk.discover()) {
            if (!j.managed() || !spec.matches(j)) continue;
            Path dir = j.home();
            // macOS bundles: remove the whole bundle directory under ~/.jpm/jdks
            Path root;
            try {
                root = Jdk.managedRoot().toRealPath();
            } catch (IOException e) {
                continue;
            }
            while (dir.getParent() != null && !dir.getParent().equals(root)) dir = dir.getParent();
            if (dir.getParent() == null) continue;
            Fs.deleteTree(dir);
            removed.add(j);
        }
        Jdk.reset();
        return removed;
    }
}
