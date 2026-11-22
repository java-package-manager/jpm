package dev.jpm.resolve;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.maven.MavenRepo;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One dependency in two scopes: dev + test is allowed, runtime + test warns, runtime + dev warns or fails. */
class ScopePairsTest {
    private static final Path FILE = Path.of("project.jsonc");

    private static String manifest(String scopes) {
        return "{ \"name\": \"a\", \"version\": \"1.0.0\", " + scopes + " }";
    }

    /** Parses and returns {dependencies as "scope key spec", stderr}. */
    private static Object[] parse(String scopes) {
        PrintStream old = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true));
        try {
            Manifest m = Manifest.parse(FILE, manifest(scopes));
            List<String> deps = m.dependencies.stream().map(d -> d.scope().key + " " + d.key() + " " + d.spec()).toList();
            return new Object[]{deps, err.toString()};
        } finally {
            System.setErr(old);
        }
    }

    @Test
    void devAndTestShareADependency() {
        Object[] r = parse("\"dev\": { \"g:a\": \"1.0\" }, \"test\": { \"g:a\": \"1.0\" }");
        assertEquals(List.of("dev g:a 1.0", "test g:a 1.0"), r[0]);
        assertEquals("", r[1], "no warning");
        JpmException e = assertThrows(JpmException.class,
                () -> Manifest.parse(FILE, manifest("\"dev\": { \"g:a\": \"1.0\" }, \"test\": { \"g:a\": \"2.0\" }")));
        assertTrue(e.getMessage().contains("\"dev\" (1.0) and in \"test\" (2.0) with different versions"), e.getMessage());
    }

    @Test
    void runtimeAndTestWarns() {
        Object[] r = parse("\"runtime\": { \"g:a\": \"1.0\" }, \"test\": { \"g:a\": \"2.0\" }");
        assertEquals(List.of("runtime g:a 1.0"), r[0], "the test entry is ignored");
        assertTrue(((String) r[1]).contains("tests have every runtime dependency already"), (String) r[1]);
        assertTrue(((String) r[1]).contains("1.0 is used, not 2.0"), (String) r[1]);
        Object[] same = parse("\"runtime\": { \"g:a\": \"1.0\" }, \"test\": { \"g:a\": \"1.0\" }");
        assertEquals(List.of("runtime g:a 1.0"), same[0]);
        assertFalse(((String) same[1]).contains("is used"), (String) same[1]);
    }

    @Test
    void runtimeAndDevWarnsWithTheSameVersionAndFailsWithAnother() {
        Object[] r = parse("\"runtime\": { \"g:a\": \"1.0\" }, \"dev\": { \"g:a\": { \"version\": \"1.0\" } }");
        assertEquals(List.of("runtime g:a 1.0"), r[0]);
        assertTrue(((String) r[1]).contains("the \"dev\" entry is ignored"), (String) r[1]);
        JpmException e = assertThrows(JpmException.class,
                () -> Manifest.parse(FILE, manifest("\"runtime\": { \"g:a\": \"1.0\" }, \"dev\": { \"g:a\": \"2.0\" }")));
        assertTrue(e.getMessage().contains("\"runtime\" (1.0) and in \"dev\" (2.0) with different versions"), e.getMessage());
    }

    @Test
    void allThreeKeepOnlyRuntime() {
        Object[] r = parse("\"runtime\": { \"g:a\": \"1.0\" }, \"dev\": { \"g:a\": \"1.0\" }, \"test\": { \"g:a\": \"1.0\" }");
        assertEquals(List.of("runtime g:a 1.0"), r[0]);
    }

    /** dev + test: compiled against, tests run with it, the program does not. */
    @Test
    void devAndTestPutItOnTheTestRuntimeClasspathOnly(@TempDir Path tmp) throws IOException {
        System.setProperty("jpm.home", tmp.resolve("home").toString());
        Path repo = tmp.resolve("repo");
        Path dir = repo.resolve("org/example/platform");
        Fs.write(dir.resolve("1.0/platform-1.0.pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>org.example</groupId>"
                + "<artifactId>platform</artifactId><version>1.0</version></project>");
        Path jar = dir.resolve("1.0/platform-1.0.jar");
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("org/example/Platform.class"));
            z.write(new byte[]{1});
        }
        Fs.write(dir.resolve("maven-metadata.xml"), "<metadata><versioning><versions><version>1.0</version></versions></versioning></metadata>");

        Path project = tmp.resolve("plugin");
        Fs.write(project.resolve("project.jsonc"), "{ \"name\": \"plugin\", \"version\": \"0.1.0\", \"java\": \"21\","
                + " \"repositories\": [" + dev.jpm.json.Jsonc.quote(repo.toUri().toString()) + "],"
                + " \"dev\": { \"org.example:platform\": \"1.0\" }, \"test\": { \"org.example:platform\": \"1.0\" } }");
        Fs.write(project.resolve("src/p/Plugin.java"), "package p; public class Plugin {}");
        Project p = Project.load(project);
        MavenRepo mr = new MavenRepo(p.root.repositories);
        Graph g = Install.run(p, mr, new Install.Options(false, true, Set.of(), true));
        Builder b = new Builder(p, g, mr, dev.jpm.build.Env.Mode.PRODUCTION);
        Project.Module m = p.modules.get(0);
        for (Builder.Cp cp : Builder.Cp.values()) {
            boolean on = b.classpath(m, cp).stream().anyMatch(x -> x.getFileName().toString().equals("platform-1.0.jar"));
            assertEquals(cp != Builder.Cp.RUNTIME, on, cp.toString());
        }
    }
}
