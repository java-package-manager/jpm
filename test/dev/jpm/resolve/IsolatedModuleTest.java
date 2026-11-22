package dev.jpm.resolve;

import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.build.Env;
import dev.jpm.maven.MavenRepo;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An isolated module is resolved in a graph of its own: a tool that needs a newer version of a library
 * does not move the version the product gets.
 */
class IsolatedModuleTest {
    private static void publish(Path repo, String a, String v, String deps) {
        Path dir = repo.resolve("g/" + a + "/" + v);
        Fs.write(dir.resolve(a + "-" + v + ".pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>" + a
                + "</artifactId><version>" + v + "</version><dependencies>" + deps + "</dependencies></project>");
        Fs.write(dir.resolve(a + "-" + v + ".jar"), "not really a jar");
        Fs.write(repo.resolve("g/" + a + "/maven-metadata.xml"), "<metadata><versioning><versions><version>1.0</version><version>2.0</version></versions></versioning></metadata>");
    }

    private static String dep(String a, String v) {
        return "<dependency><groupId>g</groupId><artifactId>" + a + "</artifactId><version>" + v + "</version></dependency>";
    }

    private Path workspace(Path dir) {
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Path repo = dir.resolve("repo");
        publish(repo, "annotations", "1.0", "");
        publish(repo, "annotations", "2.0", "");
        publish(repo, "lib", "1.0", dep("annotations", "1.0"));
        publish(repo, "tool", "1.0", dep("annotations", "2.0"));
        Path ws = dir.resolve("ws");
        Fs.write(ws.resolve("project.jsonc"), "{ \"name\": \"ws\", \"version\": \"1.0.0\", \"workspace\": [\"server\", \"tools\"],"
                + " \"repositories\": [\"" + repo.toUri() + "\"] }");
        Fs.write(ws.resolve("server/project.jsonc"), "{ \"name\": \"server\", \"runtime\": { \"g:lib\": \"1.0\" } }");
        Fs.write(ws.resolve("tools/project.jsonc"), "{ \"name\": \"tools\", \"isolated\": true, \"runtime\": { \"g:tool\": \"1.0\" } }");
        return ws;
    }

    @Test
    void toolsDoNotMoveTheProductsVersions(@TempDir Path dir) {
        Path ws = workspace(dir);
        Project p = Project.load(ws);
        MavenRepo repo = new MavenRepo(p.root.repositories);
        Graph g = Install.run(p, repo, Install.Options.normal());
        assertEquals("1.0", g.get("g:annotations").version, "the server keeps the version its library asks for");
        assertNull(g.get("g:tool"));
        Graph tools = g.isolated.get("tools");
        assertNotNull(tools);
        assertEquals("2.0", tools.get("g:annotations").version);
        assertNull(tools.get("g:lib"));

        String lock = Fs.read(ws.resolve("project.lock"));
        assertTrue(lock.contains("\"isolated\": {"), lock);
        Lockfile read = Lockfile.read(ws.resolve("project.lock"));
        assertEquals("2.0", read.graph.isolated.get("tools").get("g:annotations").version);
        assertEquals(lock, read.render());
        Graph again = Install.run(Project.load(ws), repo, new Install.Options(true, false, Set.of(), true));
        assertEquals("2.0", again.isolated.get("tools").get("g:annotations").version);

        Project q = Project.load(ws);
        Builder b = new Builder(q, again, repo, Env.Mode.PRODUCTION);
        List<String> serverCp = names(b.forModule(q.module("server")).classpath(q.module("server"), Builder.Cp.RUNTIME));
        List<String> toolsCp = names(b.forModule(q.module("tools")).classpath(q.module("tools"), Builder.Cp.RUNTIME));
        assertEquals(List.of("lib-1.0.jar", "annotations-1.0.jar"), serverCp);
        assertEquals(List.of("tool-1.0.jar", "annotations-2.0.jar"), toolsCp);
    }

    private static List<String> names(List<Path> paths) {
        return paths.stream().map(x -> x.getFileName().toString()).toList();
    }

    @Test
    void nothingDependsOnAnIsolatedModule(@TempDir Path dir) {
        Path ws = workspace(dir);
        Fs.write(ws.resolve("server/project.jsonc"), "{ \"name\": \"server\", \"runtime\": { \"workspace:tools\": \"*\" } }");
        JpmException e = assertThrows(JpmException.class, () -> Project.load(ws));
        assertTrue(e.getMessage().contains("tools is isolated"), e.getMessage());
        Fs.write(ws.resolve("server/project.jsonc"), "{ \"name\": \"server\" }");
        Fs.write(ws.resolve("tools/project.jsonc"), "{ \"name\": \"tools\", \"isolated\": true, \"runtime\": { \"workspace:server\": \"*\" } }");
        e = assertThrows(JpmException.class, () -> Project.load(ws));
        assertTrue(e.getMessage().contains("cannot depend on workspace modules"), e.getMessage());
    }
}
