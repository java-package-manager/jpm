package dev.jpm.resolve;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.build.Env;
import dev.jpm.maven.MavenRepo;
import dev.jpm.util.Fs;
import dev.jpm.util.Hash;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** gradle:api: the jars of a Gradle distribution, for Gradle plugins. */
class GradleApiTest {
    /** A distribution as services.gradle.org has it, put where jpm downloads it to. */
    private static Path fakeDistribution(Path home, String version) throws IOException {
        Path zip = home.resolve("gradle/gradle-" + version + "-bin.zip");
        Files.createDirectories(zip.getParent());
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            for (String f : List.of("lib/gradle-core-api-" + version + ".jar", "lib/groovy-4.0.jar",
                    "lib/plugins/gradle-test-kit-" + version + ".jar", "lib/gradle-core-api.properties", "bin/gradle")) {
                z.putNextEntry(new ZipEntry("gradle-" + version + "/" + f));
                z.write(f.endsWith(".jar") ? emptyJar() : "x".getBytes());
            }
        }
        return zip;
    }

    private static byte[] emptyJar() throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            z.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            z.write("Manifest-Version: 1.0\n".getBytes());
        }
        return b.toByteArray();
    }

    @Test
    void keys() {
        assertTrue(DepKey.parse("gradle:api").isGradle());
        assertEquals("gradle:api", DepKey.parse("gradle:api").toString());
        assertThrows(JpmException.class, () -> DepKey.parse("gradle:gradle-core"));
        assertTrue(GradleDist.validVersion("9.8.0"));
        assertTrue(GradleDist.validVersion("8.14"));
        assertTrue(GradleDist.validVersion("9.0-rc-1"));
        assertTrue(!GradleDist.validVersion("^9.0"));
    }

    @Test
    void distributionJarsAreTheClasspath(@TempDir Path tmp) throws IOException {
        Path home = tmp.resolve("home");
        System.setProperty("jpm.home", home.toString());
        String sha = Hash.sha256(fakeDistribution(home, "9.9.0"));
        Path p = tmp.resolve("plugin");
        Fs.write(p.resolve("project.jsonc"), """
                { "name": "my-plugin", "version": "1.0.0", "java": "21",
                  "dev": { "gradle:api": "9.9.0" }, "test": { "gradle:api": "9.9.0" } }
                """);
        Fs.write(p.resolve("src/p/Plugin.java"), "package p; public class Plugin {}");
        Project project = Project.load(p);
        MavenRepo repo = new MavenRepo(List.of());
        Graph g = Install.run(project, repo, Install.Options.normal());
        Graph.Pkg pkg = g.get("gradle:api");
        assertEquals(Graph.Kind.GRADLE, pkg.kind);
        assertEquals("9.9.0", pkg.version);
        assertEquals(sha, pkg.sha256);

        Lockfile lock = Lockfile.read(p.resolve(Manifest.LOCK));
        assertEquals(Graph.Kind.GRADLE, lock.graph.get("gradle:api").kind);
        assertEquals(sha, lock.graph.get("gradle:api").sha256);

        Builder b = new Builder(project, g, repo, Env.Mode.DEVELOPMENT);
        Project.Module m = project.modules.get(0);
        List<String> cp = b.classpath(m, Builder.Cp.COMPILE).stream().map(x -> x.getFileName().toString()).toList();
        assertEquals(List.of("gradle-core-api-9.9.0.jar", "groovy-4.0.jar", "gradle-test-kit-9.9.0.jar"), cp);
        assertTrue(b.classpath(m, Builder.Cp.RUNTIME).isEmpty(), "the plugin's runtime classpath has no Gradle: Gradle provides it");
        assertEquals(3, b.classpath(m, Builder.Cp.TEST_RUNTIME).size());
        assertTrue(Files.isRegularFile(home.resolve("gradle/9.9.0/gradle-9.9.0/bin/gradle")));

        Path meta = b.pluginUnderTestMetadata(m);
        assertNotNull(meta);
        Properties props = new Properties();
        try (var in = Files.newInputStream(meta.resolve("plugin-under-test-metadata.properties"))) {
            props.load(in);
        }
        assertEquals(Builder.classesDir(m).toString(), props.getProperty("implementation-classpath"));
        assertTrue(b.testRuntimeClasspath(m).contains(meta));
    }

    @Test
    void lockedChecksumIsVerified(@TempDir Path tmp) throws IOException {
        Path home = tmp.resolve("home");
        System.setProperty("jpm.home", home.toString());
        fakeDistribution(home, "9.9.1");
        JpmException e = assertThrows(JpmException.class, () -> GradleDist.require("9.9.1", "0".repeat(64)));
        assertTrue(e.getMessage().contains("Checksum mismatch"), e.getMessage());
    }

    @Test
    void versionsAreExact(@TempDir Path tmp) {
        System.setProperty("jpm.home", tmp.resolve("home").toString());
        Path p = tmp.resolve("plugin");
        Fs.write(p.resolve("project.jsonc"), "{ \"name\": \"pl\", \"version\": \"1.0.0\", \"dev\": { \"gradle:api\": \"9.+\" } }");
        Project project = Project.load(p);
        JpmException e = assertThrows(JpmException.class, () -> Install.run(project, new MavenRepo(List.of()), Install.Options.normal()));
        assertTrue(e.getMessage().contains("exact Gradle version"), e.getMessage());
    }

    @Test
    void otherModulesHaveNoPluginMetadata(@TempDir Path tmp) {
        System.setProperty("jpm.home", tmp.resolve("home").toString());
        Path p = tmp.resolve("app");
        Fs.write(p.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\" }");
        Project project = Project.load(p);
        MavenRepo repo = new MavenRepo(List.of());
        Builder b = new Builder(project, Install.run(project, repo, new Install.Options(false, true, Set.of(), true)), repo, Env.Mode.DEVELOPMENT);
        assertNull(b.pluginUnderTestMetadata(project.modules.get(0)));
    }
}
