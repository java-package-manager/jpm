package dev.jpm.resolve;

import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.maven.MavenRepo;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Gradle library's compileOnlyApi dependency is "compile" in its POM; its module metadata says it is only
 * needed to compile. jpm keeps it off runtime classpaths and out of bundles.
 */
class GradleMetadataTest {
    private static void publish(Path repo, String group, String artifact, String version, String pomDeps, String module, String cls) throws IOException {
        Path dir = repo.resolve(group.replace('.', '/') + "/" + artifact);
        Fs.write(dir.resolve(version + "/" + artifact + "-" + version + ".pom"), "<project>"
                + (module != null ? "<!-- do_not_remove: published-with-gradle-metadata -->" : "")
                + "<modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>" + artifact
                + "</artifactId><version>" + version + "</version><dependencies>" + pomDeps + "</dependencies></project>");
        if (module != null) Fs.write(dir.resolve(version + "/" + artifact + "-" + version + ".module"), module);
        Path jar = dir.resolve(version + "/" + artifact + "-" + version + ".jar");
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry(cls));
            z.write(new byte[]{1});
        }
        Fs.write(dir.resolve("maven-metadata.xml"), "<metadata><versioning><versions><version>" + version + "</version></versions></versioning></metadata>");
    }

    private static String dep(String group, String artifact, String version) {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId><version>" + version
                + "</version><scope>compile</scope></dependency>";
    }

    /**
     * A Kotlin Multiplatform library's JVM classes are in another module, which its module metadata names with
     * available-at (Gradle follows it; the POM may not depend on it). A library with JVM classes of its own whose other
     * variants are optional features (kotlin-test and its JUnit support) is used as it is.
     */
    @Test
    void multiplatformLibrariesResolveToTheirJvmModule(@TempDir Path tmp) throws IOException {
        System.setProperty("jpm.home", tmp.resolve("home").toString());
        // no network: an empty folder stands in for Maven Central, which is asked first
        System.setProperty("jpm.mavenCentral", tmp.resolve("central").toUri().toString());
        try {
            multiplatform(tmp);
        } finally {
            System.clearProperty("jpm.mavenCentral");
        }
    }

    private static void multiplatform(Path tmp) throws IOException {
        Path repo = tmp.resolve("repo");
        String kmp = """
                { "formatVersion": "1.1", "variants": [
                    { "name": "metadataApiElements", "attributes": { "org.gradle.category": "library", "org.gradle.usage": "kotlin-metadata",
                      "org.jetbrains.kotlin.platform.type": "common" } },
                    { "name": "jsRuntimeElements-published", "attributes": { "org.gradle.category": "library", "org.gradle.usage": "kotlin-runtime",
                      "org.jetbrains.kotlin.platform.type": "js" }, "available-at": { "group": "test.kmp", "module": "mockk-js", "version": "1.0" } },
                    { "name": "jvmRuntimeElements-published", "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime",
                      "org.jetbrains.kotlin.platform.type": "jvm" }, "available-at": { "group": "test.kmp", "module": "mockk-jvm", "version": "1.0" } } ] }
                """;
        publish(repo, "test.kmp", "mockk", "1.0", dep("test.kmp", "mockk-dsl", "1.0"), kmp, "META-INF/mockk.kotlin_module");
        publish(repo, "test.kmp", "mockk-jvm", "1.0", "", null, "io/mockk/MockK.class");
        String withFeatures = """
                { "formatVersion": "1.1", "variants": [
                    { "name": "jvmRuntimeElements", "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime",
                      "org.jetbrains.kotlin.platform.type": "jvm" } },
                    { "name": "jvmJUnit5RuntimeElements", "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime",
                      "org.jetbrains.kotlin.platform.type": "jvm" }, "capabilities": [ { "group": "test.kotlin", "name": "kotlin-test-framework-junit5" } ],
                      "available-at": { "group": "test.kotlin", "module": "kotlin-test-junit5", "version": "1.0" } } ] }
                """;
        publish(repo, "test.kotlin", "kotlin-test", "1.0", "", withFeatures, "kotlin/test/Asserter.class");

        Path project = tmp.resolve("p");
        Fs.write(project.resolve("project.jsonc"), "{ \"name\": \"p\", \"version\": \"0.1.0\","
                + " \"repositories\": [" + dev.jpm.json.Jsonc.quote(repo.toUri().toString()) + "],"
                + " \"runtime\": { \"test.kmp:mockk\": \"1.0\", \"test.kotlin:kotlin-test\": \"1.0\" } }");
        Project p = Project.load(project);
        MavenRepo mr = new MavenRepo(p.root.repositories);
        Graph g = Install.run(p, mr, new Install.Options(false, true, Set.of(), true));
        assertFalse(g.get("test.kmp:mockk").hasJar, "the root module holds only metadata");
        assertTrue(g.get("test.kmp:mockk").deps.containsKey("test.kmp:mockk-jvm"));
        assertTrue(g.get("test.kmp:mockk-jvm").hasJar);
        assertTrue(g.get("test.kotlin:kotlin-test").hasJar);
        assertEquals(null, g.get("test.kotlin:kotlin-test-junit5"));
    }

    /** The other way round: a dev dependency's tree goes on the compile classpath with its compile-only parts. */
    @Test
    void devDependenciesKeepTheirCompileOnlyApiDependencies(@TempDir Path tmp) throws IOException {
        System.setProperty("jpm.home", tmp.resolve("home").toString());
        Path repo = tmp.resolve("repo");
        publish(repo, "org.jetbrains", "annotations", "26.1.0", "", null, "org/jetbrains/annotations/NotNull.class");
        String adventureModule = """
                { "formatVersion": "1.1", "variants": [
                    { "name": "apiElements", "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-api" },
                      "dependencies": [ { "group": "org.jetbrains", "module": "annotations", "version": { "requires": "26.1.0" } } ] },
                    { "name": "runtimeElements", "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime" } } ] }
                """;
        publish(repo, "net.kyori", "adventure-api", "5.2.0", dep("org.jetbrains", "annotations", "26.1.0"), adventureModule, "net/kyori/Adventure.class");
        publish(repo, "io.papermc.paper", "paper-api", "26.3", dep("net.kyori", "adventure-api", "5.2.0"), null, "org/bukkit/Bukkit.class");

        Path project = tmp.resolve("lobby");
        Fs.write(project.resolve("project.jsonc"), "{ \"name\": \"lobby\", \"version\": \"0.1.0\", \"java\": \"21\","
                + " \"repositories\": [" + dev.jpm.json.Jsonc.quote(repo.toUri().toString()) + "],"
                + " \"dev\": { \"io.papermc.paper:paper-api\": \"26.3\" } }");
        Fs.write(project.resolve("src/l/Lobby.java"), "package l; public class Lobby {}");
        Project p = Project.load(project);
        MavenRepo mr = new MavenRepo(p.root.repositories);
        Graph g = Install.run(p, mr, new Install.Options(false, true, Set.of(), true));
        assertTrue(g.get("net.kyori:adventure-api").deps.get("org.jetbrains:annotations").compileOnly());

        Builder b = new Builder(p, g, mr, dev.jpm.build.Env.Mode.PRODUCTION);
        List<String> compile = b.classpath(p.modules.get(0), Builder.Cp.COMPILE).stream().map(x -> x.getFileName().toString()).toList();
        assertTrue(compile.contains("annotations-26.1.0.jar"), "a dev dependency's compile-only dependency is on the compile classpath: " + compile);
        List<String> processor = b.classpath(p.modules.get(0), Builder.Cp.PROCESSOR).stream().map(x -> x.getFileName().toString()).toList();
        assertTrue(processor.contains("annotations-26.1.0.jar"));

        // the same for a Git package's dev dependencies, which are resolved like a tool
        List<String> tool = b.resolveTool(List.of(new Resolver.Root("test", dev.jpm.DepKey.maven("io.papermc.paper", "paper-api"), "26.3", Set.of())),
                null, Graph.Walk.ALL).stream().map(x -> x.getFileName().toString()).toList();
        assertTrue(tool.contains("annotations-26.1.0.jar"), tool.toString());
        List<String> runtimeTool = b.resolveTool(List.of(new Resolver.Root("test", dev.jpm.DepKey.maven("io.papermc.paper", "paper-api"), "26.3", Set.of())),
                null, Graph.Walk.RUNTIME).stream().map(x -> x.getFileName().toString()).toList();
        assertFalse(runtimeTool.contains("annotations-26.1.0.jar"));
    }

    /** jpm-publish writes "api" dev dependencies as provided in the POM, and compile-only in the .module. */
    @Test
    void providedDependenciesTheModuleListsForCompilingArePassedOn(@TempDir Path tmp) throws IOException {
        System.setProperty("jpm.home", tmp.resolve("home").toString());
        Path repo = tmp.resolve("repo");
        publish(repo, "org.jspecify", "jspecify", "1.0.0", "", null, "org/jspecify/Nullable.class");
        publish(repo, "org.example", "hidden", "1.0", "", null, "org/example/Hidden.class");
        String module = """
                { "formatVersion": "1.1", "variants": [
                    { "name": "apiElements", "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-api" },
                      "dependencies": [ { "group": "org.jspecify", "module": "jspecify", "version": { "requires": "1.0.0" } } ] },
                    { "name": "runtimeElements", "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime" } } ] }
                """;
        String provided = "<dependency><groupId>org.jspecify</groupId><artifactId>jspecify</artifactId><version>1.0.0</version>"
                + "<scope>provided</scope></dependency><dependency><groupId>org.example</groupId><artifactId>hidden</artifactId>"
                + "<version>1.0</version><scope>provided</scope></dependency>";
        publish(repo, "com.acme", "lib", "1.0.0", provided, module, "com/acme/Lib.class");

        Path project = tmp.resolve("app");
        Fs.write(project.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"0.1.0\", \"java\": \"21\","
                + " \"repositories\": [" + dev.jpm.json.Jsonc.quote(repo.toUri().toString()) + "],"
                + " \"runtime\": { \"com.acme:lib\": \"1.0.0\" } }");
        Fs.write(project.resolve("src/a/App.java"), "package a; public class App {}");
        Project p = Project.load(project);
        MavenRepo mr = new MavenRepo(p.root.repositories);
        Graph g = Install.run(p, mr, new Install.Options(false, true, Set.of(), true));
        assertTrue(g.get("com.acme:lib").deps.get("org.jspecify:jspecify").compileOnly());
        assertFalse(g.get("com.acme:lib").deps.containsKey("org.example:hidden"), "other provided dependencies stay out");
        var roots = Resolver.rootEdges(p.modules.get(0), dev.jpm.Manifest.Scope.RUNTIME);
        assertTrue(g.closure(roots, Graph.Walk.COMPILE).contains("org.jspecify:jspecify"));
        assertFalse(g.closure(roots, Graph.Walk.RUNTIME).contains("org.jspecify:jspecify"));
    }

    @Test
    void compileOnlyApiDependenciesStayOutAtRunTime(@TempDir Path tmp) throws IOException {
        System.setProperty("jpm.home", tmp.resolve("home").toString());
        Path repo = tmp.resolve("repo");
        publish(repo, "net.kyori", "adventure-api", "4.17.0", "", null, "net/kyori/Adventure.class");
        publish(repo, "org.example", "util", "1.0", "", null, "org/example/Util.class");
        String module = """
                { "formatVersion": "1.1",
                  "component": { "group": "net.thenextlvl", "module": "i18n", "version": "1.2.0" },
                  "variants": [
                    { "name": "apiElements",
                      "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-api" },
                      "dependencies": [ { "group": "net.kyori", "module": "adventure-api", "version": { "requires": "4.17.0" } },
                                        { "group": "org.example", "module": "util", "version": { "requires": "1.0" } } ] },
                    { "name": "runtimeElements",
                      "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime" },
                      "dependencies": [ { "group": "org.example", "module": "util", "version": { "requires": "1.0" } } ] },
                    { "name": "javadocElements", "attributes": { "org.gradle.category": "documentation" } }
                  ] }
                """;
        publish(repo, "net.thenextlvl", "i18n", "1.2.0",
                dep("net.kyori", "adventure-api", "4.17.0") + dep("org.example", "util", "1.0"), module, "net/thenextlvl/I18n.class");

        Path project = tmp.resolve("bedwars");
        Fs.write(project.resolve("project.jsonc"), "{ \"name\": \"bedwars\", \"version\": \"0.1.0\", \"java\": \"21\","
                + " \"repositories\": [" + dev.jpm.json.Jsonc.quote(repo.toUri().toString()) + "],"
                + " \"runtime\": { \"net.thenextlvl:i18n\": \"1.2.0\" } }");
        Fs.write(project.resolve("src/bw/BedWars.java"), "package bw; public class BedWars {}");

        Project p = Project.load(project);
        MavenRepo mr = new MavenRepo(p.root.repositories);
        Graph g = Install.run(p, mr, new Install.Options(false, true, Set.of(), true));
        Graph.Edge adventure = g.get("net.thenextlvl:i18n").deps.get("net.kyori:adventure-api");
        assertTrue(adventure.compileOnly(), "adventure is compile only for i18n");
        assertFalse(g.get("net.thenextlvl:i18n").deps.get("org.example:util").compileOnly());

        var roots = Resolver.rootEdges(p.modules.get(0), dev.jpm.Manifest.Scope.RUNTIME);
        assertTrue(g.closure(roots, Graph.Walk.COMPILE).contains("net.kyori:adventure-api"), "on the compile classpath");
        assertFalse(g.closure(roots, Graph.Walk.RUNTIME).contains("net.kyori:adventure-api"), "not on the runtime classpath");
        assertTrue(g.closure(roots, Graph.Walk.RUNTIME).contains("org.example:util"));

        // the lockfile keeps it
        Graph locked = Lockfile.read(p.lockFile()).graph;
        assertTrue(locked.get("net.thenextlvl:i18n").deps.get("net.kyori:adventure-api").compileOnly());

        Builder b = new Builder(p, g, mr, dev.jpm.build.Env.Mode.PRODUCTION);
        List<String> names = new ArrayList<>();
        try (ZipFile zf = new ZipFile(b.flatBundle(p.modules.get(0)).toFile())) {
            zf.stream().forEach(e -> names.add(e.getName()));
        }
        assertTrue(names.contains("net/thenextlvl/I18n.class") && names.contains("org/example/Util.class"), names.toString());
        assertFalse(names.contains("net/kyori/Adventure.class"), "the platform provides adventure");
        assertEquals(1, names.stream().filter(n -> n.equals("bw/BedWars.class")).count());
    }
}
