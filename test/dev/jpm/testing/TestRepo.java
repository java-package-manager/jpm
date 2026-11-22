package dev.jpm.testing;

import dev.jpm.util.Fs;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * A Maven repository in a local directory, for tests that must not reach the network. Tests point jpm at it
 * with {@link #uri()} in {@code "repositories"}, or in place of Maven Central with {@code JPM_MAVEN_CENTRAL}
 * (or the {@code jpm.mavenCentral} system property).
 */
public final class TestRepo {
    private final Path root;

    public TestRepo(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The repository's directory. */
    public Path root() {
        return root;
    }

    /** The repository's {@code file:} URI, ending in a slash. */
    public String uri() {
        return root.toUri().toString();
    }

    /** Starts an artifact with the coordinates {@code group:artifact:version}. */
    public Artifact artifact(String coordinates) {
        String[] p = coordinates.split(":");
        if (p.length != 3) throw new IllegalArgumentException("expected group:artifact:version, got " + coordinates);
        return new Artifact(p[0], p[1], p[2]);
    }

    /** Publishes an empty jar with no dependencies. */
    public TestRepo publish(String coordinates) {
        artifact(coordinates).publish();
        return this;
    }

    /**
     * Copies the artifacts on the test JVM's classpath that come from the Maven cache of the jpm running the
     * tests: JUnit and the console launcher, at exactly the versions jpm's own tests use. Projects in tests
     * can then run {@code jpm test} without the network, and resolve the same versions on every machine.
     *
     * @return the copied artifacts as {@code group:artifact} to version
     */
    public Map<String, String> copyTestClasspath() {
        Path cache = JpmHome.outerRepository().toAbsolutePath().normalize();
        Map<String, String> copied = new java.util.TreeMap<>();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            Path jar = Path.of(entry).toAbsolutePath().normalize();
            if (!jar.startsWith(cache) || !jar.toString().endsWith(".jar")) continue;
            Path versionDir = jar.getParent(), artifactDir = versionDir.getParent();
            Path rel = cache.relativize(artifactDir);
            String group = rel.getParent().toString().replace(java.io.File.separatorChar, '.');
            String ga = group + ":" + artifactDir.getFileName(), version = versionDir.getFileName().toString();
            copied.put(ga, version);
            Path target = root.resolve(cache.relativize(versionDir).toString());
            copyVersion(versionDir, target);
            Map<String, String> deps = LOCKED.get(ga);
            if (deps != null) {
                StringBuilder pom = new StringBuilder("<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>"
                        + artifactDir.getFileName() + "</artifactId><version>" + version + "</version><dependencies>");
                deps.forEach((dep, scope) -> {
                    String[] d = dep.split(":");
                    pom.append("<dependency><groupId>").append(d[0]).append("</groupId><artifactId>").append(d[1])
                            .append("</artifactId><version>").append(LOCKED_VERSIONS.get(dep)).append("</version><scope>")
                            .append(scope).append("</scope></dependency>");
                });
                Fs.write(target.resolve(artifactDir.getFileName() + "-" + version + ".pom"), pom.append("</dependencies></project>").toString());
            }
            if (group.startsWith("org.junit")) {
                Path bom = cache.resolve("org/junit/junit-bom").resolve(versionDir.getFileName().toString());
                if (Files.isDirectory(bom)) copyVersion(bom, root.resolve(cache.relativize(bom).toString()));
            }
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path d : s.filter(p -> Files.isDirectory(p) && hasVersionDirs(p)).toList()) {
                Path rel = root.relativize(d);
                String group = rel.getParent().toString().replace(java.io.File.separatorChar, '.');
                new Artifact(group, d.getFileName().toString(), "").writeMetadata(d);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return copied;
    }

    /**
     * The packages in jpm's own {@code project.lock}, with their dependencies and scopes. A fresh cache holds
     * only the jars of locked packages (jpm reads their dependencies from the lockfile, not from POMs), so
     * their POMs are written from it.
     */
    private static final Map<String, Map<String, String>> LOCKED = new java.util.HashMap<>();
    private static final Map<String, String> LOCKED_VERSIONS = new java.util.HashMap<>();

    static {
        Path lock = Path.of("project.lock");
        if (Files.exists(lock)) {
            Object parsed = dev.jpm.json.Jsonc.parse(Fs.read(lock));
            if (parsed instanceof Map<?, ?> m && m.get("packages") instanceof Map<?, ?> packages) {
                for (Map.Entry<?, ?> e : packages.entrySet()) {
                    if (!(e.getValue() instanceof Map<?, ?> pkg)) continue;
                    Map<String, String> deps = new java.util.TreeMap<>();
                    if (pkg.get("dependencies") instanceof Map<?, ?> d) d.forEach((k, v) -> deps.put(k.toString(), v.toString()));
                    LOCKED.put(e.getKey().toString(), deps);
                    LOCKED_VERSIONS.put(e.getKey().toString(), String.valueOf(pkg.get("version")));
                }
            }
        }
    }

    private static boolean hasVersionDirs(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.anyMatch(v -> Files.isDirectory(v) && Files.exists(v.resolve(dir.getFileName() + "-" + v.getFileName() + ".pom")));
        } catch (IOException e) {
            return false;
        }
    }

    private static void copyVersion(Path from, Path to) {
        try (Stream<Path> s = Files.list(from)) {
            Files.createDirectories(to);
            for (Path f : (Iterable<Path>) s::iterator) {
                String n = f.getFileName().toString();
                if (Files.isRegularFile(f) && !n.startsWith(".") && (n.endsWith(".jar") || n.endsWith(".pom"))) {
                    Files.copy(f, to.resolve(n), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One artifact being published: its jar contents, dependencies and POM extras. */
    public final class Artifact {
        private final String group, name, version;
        private final Map<String, byte[]> entries = new LinkedHashMap<>();
        private final Map<String, byte[]> sources = new LinkedHashMap<>();
        private final List<String> dependencies = new ArrayList<>();
        private String pomExtra = "";

        private Artifact(String group, String name, String version) {
            this.group = group;
            this.name = name;
            this.version = version;
        }

        /** A file in the jar. */
        public Artifact entry(String path, String text) {
            entries.put(path, text.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        /** Every file under {@code dir}, for example compiled classes. */
        public Artifact entries(Path dir) {
            for (Path f : Fs.files(dir, null)) {
                try {
                    entries.put(dir.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return this;
        }

        /**
         * A class compiled from {@code source} for Java 11, for stubs of real libraries: only the members a
         * test uses, so the test needs no network.
         */
        public Artifact javaClass(String className, String source) {
            try {
                Path tmp = Files.createTempDirectory("stub");
                Path src = tmp.resolve("src").resolve(className.replace('.', '/') + ".java");
                Fs.write(src, source);
                Path out = tmp.resolve("classes");
                int code = javax.tools.ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, "--release", "11", "-d", out.toString(), src.toString());
                if (code != 0) throw new IllegalStateException("could not compile the stub " + className);
                entries(out);
                Fs.deleteTree(tmp);
                return this;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** A file in the sources jar ({@code -sources.jar}), which is written when there is at least one. */
        public Artifact source(String path, String text) {
            sources.put(path, text.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        /** A compile-scope dependency {@code group:artifact:version}. */
        public Artifact dependsOn(String coordinates) {
            return dependsOn(coordinates, "compile");
        }

        /** A dependency {@code group:artifact:version} with a Maven scope. */
        public Artifact dependsOn(String coordinates, String scope) {
            String[] p = coordinates.split(":");
            dependencies.add("<dependency><groupId>" + p[0] + "</groupId><artifactId>" + p[1] + "</artifactId><version>" + p[2]
                    + "</version><scope>" + scope + "</scope></dependency>");
            return this;
        }

        /** XML added inside {@code <project>}, for example {@code <dependencyManagement>}. */
        public Artifact pom(String xml) {
            pomExtra += xml;
            return this;
        }

        /** Writes the POM, the jar and the artifact's {@code maven-metadata.xml}. */
        public void publish() {
            Path dir = root.resolve(group.replace('.', '/')).resolve(name);
            Path v = dir.resolve(version);
            Fs.write(v.resolve(name + "-" + version + ".pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>" + group
                    + "</groupId><artifactId>" + name + "</artifactId><version>" + version + "</version>" + pomExtra
                    + "<dependencies>" + String.join("", dependencies) + "</dependencies></project>");
            writeJar(v.resolve(name + "-" + version + ".jar"), entries);
            if (!sources.isEmpty()) writeJar(v.resolve(name + "-" + version + "-sources.jar"), sources);
            writeMetadata(dir);
        }

        private static void writeJar(Path file, Map<String, byte[]> files) {
            try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out)) {
                for (Map.Entry<String, byte[]> e : files.entrySet()) {
                    ZipEntry entry = new ZipEntry(e.getKey());
                    entry.setTimeLocal(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
                    jar.putNextEntry(entry);
                    jar.write(e.getValue());
                    jar.closeEntry();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void writeMetadata(Path dir) {
            List<String> versions;
            try (Stream<Path> s = Files.list(dir)) {
                versions = s.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                        .sorted(Comparator.comparing(dev.jpm.version.Version::of)).toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            StringBuilder xml = new StringBuilder("<metadata><groupId>" + group + "</groupId><artifactId>" + name
                    + "</artifactId><versioning><latest>" + versions.getLast() + "</latest><release>" + versions.getLast()
                    + "</release><versions>");
            for (String ver : versions) xml.append("<version>").append(ver).append("</version>");
            xml.append("</versions></versioning></metadata>");
            Fs.write(dir.resolve("maven-metadata.xml"), xml.toString());
        }
    }
}
