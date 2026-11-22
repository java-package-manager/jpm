package dev.jpm.testing;

import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

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
     * Copies whole groups (for example {@code org.junit}) from the Maven cache of the jpm running the tests.
     * Tests use it for test frameworks, which jpm itself needs to run its tests and so are always cached.
     */
    public TestRepo copyFromCache(String... groups) {
        Path cache = JpmHome.outerRepository();
        for (String g : groups) {
            Path from = cache.resolve(g.replace('.', '/'));
            if (!Files.isDirectory(from)) throw new JpmException(g + " is not in the jpm cache " + cache);
            copyTree(from, root.resolve(g.replace('.', '/')));
        }
        return this;
    }

    private static void copyTree(Path from, Path to) {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                Path target = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) Files.createDirectories(target);
                else if (!f.getFileName().toString().startsWith(".")) Files.copy(f, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One artifact being published: its jar contents, dependencies and POM extras. */
    public final class Artifact {
        private final String group, name, version;
        private final Map<String, byte[]> entries = new LinkedHashMap<>();
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
            try (OutputStream out = Files.newOutputStream(v.resolve(name + "-" + version + ".jar"));
                 JarOutputStream jar = new JarOutputStream(out)) {
                for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                    jar.putNextEntry(new ZipEntry(e.getKey()));
                    jar.write(e.getValue());
                    jar.closeEntry();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            writeMetadata(dir);
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
