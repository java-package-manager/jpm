package dev.jpm.build;

import dev.jpm.Project;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Install;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "tests": true on a workspace dependency (another module's compiled tests on this one's test classpaths), and the
 * module-wide "exclude" (packages the platform provides, left out of everything the runtime and dev dependencies bring).
 */
class TestFixturesTest {
    private static void publish(Path repo, String group, String artifact, String version, String deps, Path dir) {
        Path v = repo.resolve(group.replace('.', '/') + "/" + artifact + "/" + version);
        Fs.write(v.resolve(artifact + "-" + version + ".pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>" + group
                + "</groupId><artifactId>" + artifact + "</artifactId><version>" + version + "</version><dependencies>" + deps
                + "</dependencies></project>");
        Jars.write(v.resolve(artifact + "-" + version + ".jar"), List.of(dir), Map.of());
    }

    private static String dep(String group, String artifact, String version) {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId><version>" + version + "</version></dependency>";
    }

    @Test
    void testsOfAnotherModule(@TempDir Path tmp) throws IOException {
        System.setProperty("jpm.home", tmp.resolve("home").toString());
        Path repo = tmp.resolve("repo"), empty = tmp.resolve("empty");
        Files.createDirectories(empty);
        publish(repo, "org.junit.local", "none", "1.0", "", empty);
        Path ws = tmp.resolve("ws");
        Fs.write(ws.resolve("project.jsonc"), "{ \"name\": \"ws\", \"version\": \"1.0.0\", \"java\": \"21\", \"workspace\": [\"lib\", \"core\"],"
                + " \"repositories\": [" + dev.jpm.api.json.Jsonc.quote(repo.toUri().toString()) + "] }");
        Fs.write(ws.resolve("lib/project.jsonc"), "{ \"name\": \"lib\", \"test\": { \"org.junit.local:none\": \"1.0\" } }");
        Fs.write(ws.resolve("lib/src/lib/Lib.java"), "package lib; public class Lib {}");
        Fs.write(ws.resolve("lib/test/lib/TaskTest.java"), "package lib; public class TaskTest { protected String project() { return \"p\"; } }");
        Fs.write(ws.resolve("core/project.jsonc"), """
                { "name": "core",
                  "runtime": { "workspace:lib": "*" },
                  "test": { "workspace:lib": { "version": "*", "tests": true }, "org.junit.local:none": "1.0" } }
                """);
        Fs.write(ws.resolve("core/src/core/Core.java"), "package core; public class Core { lib.Lib lib; }");
        Fs.write(ws.resolve("core/test/core/CoreTest.java"), "package core; public class CoreTest extends lib.TaskTest { }");
        Project p = Project.load(ws);
        MavenRepo mr = new MavenRepo(p.root.repositories);
        Builder b = new Builder(p, Install.run(p, mr, new Install.Options(false, true, Set.of(), true)), mr, Env.Mode.DEVELOPMENT);
        Project.Module core = p.module("core"), lib = p.module("lib");
        assertTrue(b.compileTests(core));
        assertTrue(Files.isRegularFile(Builder.testClassesDir(core).resolve("core/CoreTest.class")));
        assertTrue(b.testRuntimeClasspath(core).contains(Builder.testClassesDir(lib)));
        assertFalse(Files.exists(Builder.testClassesDir(core).resolve("lib/TaskTest.class")));
    }

    @Test
    void testsOnlyForWorkspaceTestDependencies(@TempDir Path tmp) {
        Fs.write(tmp.resolve("project.jsonc"), "{ \"name\": \"a\", \"runtime\": { \"workspace:b\": { \"version\": \"*\", \"tests\": true } } }");
        JpmException e = assertThrows(JpmException.class, () -> Project.load(tmp));
        assertTrue(e.getMessage().contains("tests applies to"), e.getMessage());
    }

    @Test
    void moduleExcludesLeaveOutWhatThePlatformProvides(@TempDir Path tmp) throws IOException {
        System.setProperty("jpm.home", tmp.resolve("home").toString());
        Path repo = tmp.resolve("repo"), classes = tmp.resolve("classes");
        Files.createDirectories(classes);
        Fs.write(classes.resolve("x.txt"), "x");
        publish(repo, "org.stdlib", "stdlib", "1.0", "", classes);
        publish(repo, "org.lib", "lib", "1.0", dep("org.stdlib", "stdlib", "1.0"), classes);
        publish(repo, "org.test", "testlib", "1.0", dep("org.stdlib", "stdlib", "1.0"), classes);
        Path ws = tmp.resolve("ws");
        Fs.write(ws.resolve("project.jsonc"), "{ \"name\": \"ws\", \"version\": \"1.0.0\", \"workspace\": [\"plugin\"],"
                + " \"exclude\": [\"org.stdlib\"], \"repositories\": [" + dev.jpm.api.json.Jsonc.quote(repo.toUri().toString()) + "] }");
        Fs.write(ws.resolve("plugin/project.jsonc"), """
                { "name": "plugin", "runtime": { "org.lib:lib": "1.0" }, "test": { "org.test:testlib": "1.0" } }
                """);
        Project p = Project.load(ws);
        MavenRepo mr = new MavenRepo(p.root.repositories);
        Graph g = Install.run(p, mr, new Install.Options(false, true, Set.of(), true));
        Builder b = new Builder(p, g, mr, Env.Mode.PRODUCTION);
        Project.Module m = p.module("plugin");
        List<String> runtime = b.classpathIds(m, Builder.Cp.RUNTIME);
        assertTrue(runtime.contains("org.lib:lib") && !runtime.contains("org.stdlib:stdlib"), runtime.toString());
        assertTrue(b.classpathIds(m, Builder.Cp.TEST_RUNTIME).contains("org.stdlib:stdlib"));
    }
}
