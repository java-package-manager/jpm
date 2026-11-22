package dev.jpm.resolve;

import dev.jpm.build.Jars;
import dev.jpm.util.Fs;
import dev.jpm.util.Hash;
import dev.jpm.util.Http;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code gradle:api}: the Gradle API for Gradle plugins, taken from a Gradle distribution. Gradle generates its
 * API jar from the distribution it runs on and publishes none for current versions, so jpm downloads the
 * distribution ({@code gradle-<version>-bin.zip}) once into {@code ~/.jpm/gradle/<version>} and puts its jars
 * ({@code lib/} and {@code lib/plugins/}) on the classpath: the Gradle API, the Kotlin DSL extensions, TestKit and
 * the {@code ProjectBuilder} test fixtures, and the libraries Gradle provides at run time (Groovy, the Kotlin
 * standard library, SLF4J). TestKit finds the distribution from these jars, so functional tests run on it too.
 *
 * <p>The SHA-256 of the archive is locked in {@code project.lock}, like a Maven jar's.
 */
public final class GradleDist {
    private GradleDist() {}

    public static final String ID = "gradle:api";

    /** Where distributions are downloaded from; {@code JPM_GRADLE_DISTRIBUTIONS} points to a mirror. */
    static String base() {
        String b = System.getenv("JPM_GRADLE_DISTRIBUTIONS");
        if (b == null || b.isBlank()) b = "https://services.gradle.org/distributions";
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    /** The current Gradle release, from services.gradle.org/versions/current (or the mirror's). */
    public static String latest() {
        String url = System.getenv("JPM_GRADLE_VERSIONS");
        if (url == null || url.isBlank()) url = "https://services.gradle.org/versions/current";
        Object v = dev.jpm.json.Jsonc.parse(Http.getString(url));
        if (v instanceof java.util.Map<?, ?> m && m.get("version") instanceof String s && validVersion(s)) return s;
        throw new JpmException("Could not read the current Gradle version from " + url);
    }

    /** An unpacked distribution: its home directory and the SHA-256 of the archive it came from. */
    public record Installed(Path home, String sha256) {}

    /** Gradle versions as they name their distributions: 9.8.0, 8.14, 9.0-rc-1, 9.1.0-milestone-2. */
    public static boolean validVersion(String v) {
        return v != null && v.matches("\\d+\\.\\d+(\\.\\d+)?(-(rc|milestone)-\\d+)?");
    }

    /** The distribution of a Gradle version, downloaded and unpacked if needed and checked against {@code lockedSha}. */
    public static synchronized Installed require(String version, String lockedSha) {
        if (!validVersion(version)) {
            throw new JpmException("gradle:api needs an exact Gradle version such as 9.8.0, not \"" + version + "\"");
        }
        Path root = Fs.home().resolve("gradle").resolve(version);
        Path home = root.resolve("gradle-" + version);
        Path marker = root.resolve("sha256");
        if (Files.isDirectory(home.resolve("lib")) && Files.isRegularFile(marker)) {
            String sha = Fs.read(marker).strip();
            check(version, lockedSha, sha, root);
            return new Installed(home, sha);
        }
        String name = "gradle-" + version + "-bin.zip";
        Path archive = Fs.home().resolve("gradle").resolve(name);
        String sha;
        if (Files.isRegularFile(archive)) {
            sha = Hash.sha256(archive);
        } else {
            Log.step("Downloading", "Gradle " + version + Log.dim(" (" + base() + "/" + name + ")"));
            sha = Http.download(base() + "/" + name, archive, "Gradle " + version);
        }
        try {
            check(version, lockedSha, sha, null);
        } catch (JpmException e) {
            delete(archive);
            throw e;
        }
        Path tmp = root.resolveSibling(".tmp-" + version);
        Fs.deleteTree(tmp);
        Jars.extract(archive, tmp);
        if (!Files.isDirectory(tmp.resolve("gradle-" + version).resolve("lib"))) {
            Fs.deleteTree(tmp);
            throw new JpmException(name + " is not a Gradle distribution: it has no gradle-" + version + "/lib");
        }
        Fs.deleteTree(root);
        try {
            Files.createDirectories(root.getParent());
            Files.move(tmp, root);
        } catch (IOException e) {
            throw new JpmException("Could not install Gradle " + version + " into " + root + ": " + e.getMessage());
        }
        markExecutable(home);
        Fs.write(marker, sha + "\n");
        delete(archive);
        return new Installed(home, sha);
    }

    private static void check(String version, String lockedSha, String sha, Path installed) {
        if (lockedSha == null || lockedSha.equals(sha)) return;
        throw new JpmException("Checksum mismatch for the Gradle " + version + " distribution:\n  locked:   " + lockedSha
                + "\n  download: " + sha + (installed == null ? "" : "\nIf the installed copy is corrupt, delete " + installed + " and try again."));
    }

    /** The distribution's jars: {@code lib/*.jar}, then {@code lib/plugins/*.jar}, each sorted by name. */
    public static List<Path> jars(Path home) {
        List<Path> out = new ArrayList<>();
        for (Path dir : List.of(home.resolve("lib"), home.resolve("lib/plugins"))) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().forEach(out::add);
            } catch (IOException e) {
                throw new JpmException("Could not list " + dir + ": " + e.getMessage());
            }
        }
        if (out.isEmpty()) throw new JpmException(home + " has no jars in lib/; delete " + home.getParent() + " and try again");
        return out;
    }

    /** TestKit runs the distribution's launcher, which the zip extraction does not mark executable. */
    private static void markExecutable(Path home) {
        for (String f : List.of("bin/gradle")) {
            Path p = home.resolve(f);
            if (Files.isRegularFile(p)) p.toFile().setExecutable(true, false);
        }
    }

    private static void delete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }
}
