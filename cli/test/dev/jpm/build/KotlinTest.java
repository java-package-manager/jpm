package dev.jpm.build;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Install;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "kotlin": Kotlin sources compiled with kotlinc, mixed with Java. */
class KotlinTest {

    private static Manifest parse(Path dir, String json) {
        return Manifest.parse(dir.resolve("project.jsonc"), json);
    }

    @Test
    void settings(@TempDir Path dir) {
        Manifest plain = parse(dir, "{ \"name\": \"a\", \"kotlin\": \"2.2.20\" }");
        assertEquals("2.2.20", plain.kotlin.version());
        assertEquals(List.of(), plain.kotlin.plugins());

        Manifest full = parse(dir, """
                { "name": "a", "layout": "maven",
                  "kotlin": { "version": "2.4.10", "plugins": ["assignment", "com.example:my-plugin", "com.example:pinned:1.0"],
                              "args": ["-Xjsr305=strict", "-J-Xmx1g"] } }
                """);
        assertEquals(List.of("org.jetbrains.kotlin:kotlin-assignment-compiler-plugin-embeddable:2.4.10",
                "com.example:my-plugin:2.4.10", "com.example:pinned:1.0"), full.kotlin.pluginCoordinates());
        assertEquals(List.of(dir.resolve("src/main/java"), dir.resolve("src/main/kotlin")), full.sourceDirs(dir));
        assertEquals(List.of(dir.resolve("src/test/java"), dir.resolve("src/test/kotlin")), full.testSourceDirs(dir));
        assertEquals(List.of(dir.resolve("src")), plain.sourceDirs(dir));

        assertNull(parse(dir, "{ \"name\": \"a\" }").kotlin);
        assertThrows(JpmException.class, () -> parse(dir, "{ \"name\": \"a\", \"kotlin\": \"2.+\" }"));
        assertThrows(JpmException.class, () -> parse(dir, "{ \"name\": \"a\", \"kotlin\": { \"version\": \"2.2.20\", \"plugin\": [] } }"));
        JpmException e = assertThrows(JpmException.class,
                () -> parse(dir, "{ \"name\": \"a\", \"kotlin\": { \"version\": \"2.2.20\", \"plugins\": [\"assigment\"] } }"));
        assertTrue(e.getMessage().contains("sam-with-receiver"), e.getMessage());
    }

    @Test
    void workspaceModulesInheritKotlin(@TempDir Path dir) {
        Fs.write(dir.resolve("project.jsonc"), "{ \"name\": \"ws\", \"version\": \"1.0.0\", \"workspace\": [\"a\", \"b\"],"
                + " \"kotlin\": \"2.4.10\", \"testJvmArgs\": [\"-Xmx1g\"] }");
        Fs.write(dir.resolve("a/project.jsonc"), "{ \"name\": \"a\" }");
        Fs.write(dir.resolve("b/project.jsonc"), "{ \"name\": \"b\", \"kotlin\": \"2.2.20\", \"testJvmArgs\": [\"-ea\"] }");
        Project p = Project.load(dir);
        assertEquals("2.4.10", p.module("a").manifest.kotlin.version());
        assertEquals("2.2.20", p.module("b").manifest.kotlin.version());
        assertEquals(List.of("-Xmx1g", "-ea"), p.module("b").manifest.testJvmArgs);
    }

    @Test
    void kotlinSourcesNeedAKotlinVersion(@TempDir Path dir) {
        Path p = dir.resolve("p");
        Fs.write(p.resolve("project.jsonc"), "{ \"name\": \"p\", \"version\": \"1.0.0\" }");
        Fs.write(p.resolve("src/app/Main.kt"), "package app\nfun main() {}\n");
        Project project = Project.load(p);
        MavenRepo r = new MavenRepo(List.of());
        Builder b = new Builder(project, Install.run(project, r, new Install.Options(false, true, Set.of(), true)), r, Env.Mode.PRODUCTION);
        JpmException e = assertThrows(JpmException.class, () -> b.compileMain(project.modules.get(0)));
        assertTrue(e.getMessage().contains("\"kotlin\""), e.getMessage());
    }

    /** Needs kotlinc and the Kotlin standard library from Maven Central. */
    @Test
    @Tag("live")
    void compilesKotlinAndJavaTogether(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("p");
        Fs.write(p.resolve("project.jsonc"), """
                { "name": "mixed", "version": "1.0.0", "java": "21", "layout": "maven",
                  "kotlin": "2.2.20",
                  "runtime": { "org.jetbrains.kotlin:kotlin-stdlib": "2.2.20" },
                  "define": { "GREETING": "hi" }, "defineClass": "app.Config" }
                """);
        Fs.write(p.resolve("src/main/kotlin/app/Greeter.kt"), """
                package app
                internal fun secret() = 42
                class Greeter(val name: String) {
                    fun greet(): String = Config.GREETING + " " + Shout.loud(name) + " " + secret()
                }
                """);
        Fs.write(p.resolve("src/main/java/app/Shout.java"),
                "package app; public class Shout { public static String loud(String s) { return s.toUpperCase(); } }");
        Fs.write(p.resolve("src/main/java/app/UsesKotlin.java"),
                "package app; public class UsesKotlin { public static String run() { return new Greeter(\"jpm\").greet(); } }");
        Fs.write(p.resolve("src/main/resources/app.txt"), "resource");
        Project project = Project.load(p);
        MavenRepo r = new MavenRepo(List.of());
        Builder b = new Builder(project, Install.run(project, r, Install.Options.normal()), r, Env.Mode.PRODUCTION);
        Project.Module m = project.modules.get(0);
        Path jar = b.jar(m);
        try (JarFile jf = new JarFile(jar.toFile())) {
            Set<String> names = jf.stream().map(java.util.zip.ZipEntry::getName).collect(Collectors.toSet());
            assertTrue(names.containsAll(Set.of("app/Greeter.class", "app/GreeterKt.class", "app/Shout.class", "app/UsesKotlin.class",
                    "app/Config.class", "META-INF/mixed.kotlin_module", "app.txt")), names.toString());
        }
        try (URLClassLoader cl = new URLClassLoader(b.runtimeClasspath(m).stream().map(KotlinTest::url).toArray(java.net.URL[]::new), null)) {
            assertEquals("hi JPM 42", cl.loadClass("app.UsesKotlin").getMethod("run").invoke(null));
        }
        Path stamp = m.cacheDir().resolve("kotlin-classes.key");
        long before = Files.getLastModifiedTime(stamp).toMillis();
        assertTrue(!new Builder(project, b.graph, r, Env.Mode.PRODUCTION).compileMain(m));
        assertEquals(before, Files.getLastModifiedTime(stamp).toMillis());

        Fs.write(p.resolve("src/test/kotlin/app/SecretCheck.kt"), "package app\nobject SecretCheck { fun value() = secret() }\n");
        Fs.write(p.resolve("project.jsonc"), Fs.read(p.resolve("project.jsonc")).replace("\"defineClass\"",
                "\"test\": { \"org.junit.jupiter:junit-jupiter\": \"5.11.4\" }, \"defineClass\""));
        Project again = Project.load(p);
        Builder b2 = new Builder(again, Install.run(again, r, Install.Options.normal()), r, Env.Mode.PRODUCTION);
        assertTrue(b2.compileTests(again.modules.get(0)));
        assertTrue(Files.isRegularFile(Builder.testClassesDir(again.modules.get(0)).resolve("app/SecretCheck.class")));
    }

    private static java.net.URL url(Path p) {
        try {
            return p.toUri().toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }
}
