package dev.jpm.resolve;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.build.Env;
import dev.jpm.build.Jars;
import dev.jpm.maven.MavenRepo;
import dev.jpm.util.Fs;
import dev.jpm.util.Hash;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ext:<extension>/<name>: dependencies an extension provides, such as jpm-paperweight's Paper server.
 */
class ExtensionDepsTest {
    @Test
    void keys() {
        DepKey k = DepKey.parse("ext:paperweight/server");
        assertTrue(k.isExtension());
        assertEquals("paperweight", k.group());
        assertEquals("server", k.artifact());
        assertEquals("ext:paperweight/server", k.toString());
        assertThrows(JpmException.class, () -> DepKey.parse("ext:paperweight/server/extra"));
        assertTrue(DepKey.parse("ext:artifact").isMaven(), "a Maven group named ext is still one");
    }

    /**
     * A fake jpm-<name> in ~/.jpm/bin answering `resolve <name> <version>` with a jar and a Maven dependency.
     */
    private static void fakeExtension(Path home, String name, Path jar, String deps) throws IOException {
        Path bin = home.resolve("bin/jpm-" + name);
        Fs.write(bin, """
                #!/bin/sh
                [ "$1" = resolve ] || exit 2
                echo "resolving $2 $3" >&2
                v="$3"; [ "$v" = latest ] && v=2.0
                printf '{"version": "%s", "jars": ["%s"], "dependencies": {%s}}\\n' "$v" "JAR" 'DEPS'
                """.replace("JAR", jar.toString()).replace("DEPS", deps));
        bin.toFile().setExecutable(true);
    }

    @Test
    void extensionsProvideJarsAndDependencies(@TempDir Path tmp) throws IOException {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"), "the fake extension is a shell script");
        Path home = tmp.resolve("home");
        System.setProperty("jpm.home", home.toString());
        Path classes = tmp.resolve("classes");
        Fs.write(classes.resolve("srv/Server.class"), "x");
        Path serverJar = tmp.resolve("server.jar");
        Jars.write(serverJar, List.of(classes), Map.of());
        Path repo = tmp.resolve("repo");
        Path v = repo.resolve("org/api/api/1.0");
        Fs.write(v.resolve("api-1.0.pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>org.api</groupId><artifactId>api</artifactId><version>1.0</version></project>");
        Jars.write(v.resolve("api-1.0.jar"), List.of(classes), Map.of());
        fakeExtension(home, "fakeserver", serverJar, "\"org.api:api\": \"1.0\"");

        Path p = tmp.resolve("plugin");
        Fs.write(p.resolve("project.jsonc"), "{ \"name\": \"plugin\", \"version\": \"1.0.0\","
                + " \"repositories\": [" + dev.jpm.api.json.Jsonc.quote(repo.toUri().toString()) + "],"
                + " \"dev\": { \"ext:fakeserver/server\": \"latest\" } }");
        Project project = Project.load(p);
        MavenRepo mr = new MavenRepo(project.root.repositories);
        Graph g = Install.run(project, mr, new Install.Options(false, true, Set.of(), true));
        Graph.Pkg pkg = g.get("ext:fakeserver/server");
        assertEquals(Graph.Kind.EXTENSION, pkg.kind);
        assertEquals("2.0", pkg.version, "the extension says which version a spec is");
        assertEquals(Hash.sha256(serverJar), pkg.sha256);
        assertTrue(pkg.deps.containsKey("org.api:api"));

        Lockfile lock = Lockfile.read(p.resolve(Manifest.LOCK));
        assertEquals(Graph.Kind.EXTENSION, lock.graph.get("ext:fakeserver/server").kind);

        Builder b = new Builder(project, g, mr, Env.Mode.PRODUCTION);
        Project.Module m = project.modules.get(0);
        List<Path> cp = b.classpath(m, Builder.Cp.COMPILE);
        assertTrue(cp.contains(serverJar), cp.toString());
        assertTrue(cp.stream().anyMatch(x -> x.getFileName().toString().equals("api-1.0.jar")), cp.toString());
        assertTrue(b.classpath(m, Builder.Cp.RUNTIME).isEmpty(), "a dev dependency is compile only");
    }

    @Test
    void missingExtensionsSayHowToInstallThem(@TempDir Path tmp) {
        System.setProperty("jpm.home", tmp.resolve("home").toString());
        JpmException e = assertThrows(JpmException.class, () -> ExtensionDeps.resolve(DepKey.parse("ext:nosuchextension/x"), "1.0"));
        assertTrue(e.getMessage().contains("jpm add -g"), e.getMessage());
    }

    @Test
    void answersAreChecked(@TempDir Path tmp) throws IOException {
        DepKey key = DepKey.parse("ext:x/y");
        assertThrows(JpmException.class, () -> ExtensionDeps.parse(key, "not json"));
        assertThrows(JpmException.class, () -> ExtensionDeps.parse(key, "{\"version\": \"1\", \"jars\": [" + q(tmp.resolve("nope.jar")) + "]}"));
        Path jar = Files.writeString(tmp.resolve("a.jar"), "x");
        assertThrows(JpmException.class, () -> ExtensionDeps.parse(key, "{\"version\": \"1\", \"jars\": [" + q(jar) + "],"
                + " \"dependencies\": { \"github:a/b\": \"1\" }}"));
        assertEquals(List.of(jar), ExtensionDeps.parse(key, "{\"version\": \"1\", \"jars\": [" + q(jar) + "]}").jars());
    }

    private static String q(Path p) {
        return dev.jpm.api.json.Jsonc.quote(p.toString());
    }
}
