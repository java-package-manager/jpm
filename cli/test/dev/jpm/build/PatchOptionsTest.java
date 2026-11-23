package dev.jpm.build;

import dev.jpm.Project;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Install;
import dev.jpm.toolchain.JavaSpec;
import dev.jpm.toolchain.Jdk;
import dev.jpm.toolchain.Toolchain;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.module.ModuleDescriptor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Patches as objects: compile-only dependencies, a release, and patching modular jars.
 */
class PatchOptionsTest {
    /**
     * Compiles {@code sources} (relative path to text) and publishes them as g:name:1.0 with a sources jar.
     */
    private static void publish(Path repo, Path work, String name, Map<String, String> sources, int release, List<Path> cp) throws Exception {
        Path src = work.resolve(name + "-src"), classes = work.resolve(name + "-classes");
        List<String> args = new ArrayList<>(List.of("--release", Integer.toString(release), "-d", classes.toString()));
        if (!cp.isEmpty()) {
            args.add(sources.containsKey("module-info.java") ? "--module-path" : "-cp");
            args.add(cp.stream().map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator)));
        }
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Fs.write(src.resolve(e.getKey()), e.getValue());
            args.add(src.resolve(e.getKey()).toString());
        }
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        Path dir = repo.resolve("g/" + name + "/1.0");
        Fs.write(dir.resolve(name + "-1.0.pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>" + name
                + "</artifactId><version>1.0</version></project>");
        Jars.write(dir.resolve(name + "-1.0.jar"), List.of(classes), Map.of());
        Jars.write(dir.resolve(name + "-1.0-sources.jar"), List.of(src), Map.of());
    }

    /**
     * A git-style patch turning the files of {@code before} into those of {@code after}.
     */
    private static String diff(Path work, Map<String, String> before, Map<String, String> after) {
        Path a = work.resolve("diff/a"), b = work.resolve("diff/b");
        Fs.deleteTree(work.resolve("diff"));
        before.forEach((k, v) -> Fs.write(a.resolve(k), v));
        after.forEach((k, v) -> Fs.write(b.resolve(k), v));
        Exec.Result r = Exec.capture(work.resolve("diff"), List.of("git", "diff", "--no-index", "--no-color", "a", "b"));
        return r.out().replace("a/a/", "a/").replace("b/b/", "b/");
    }

    private static Set<String> entries(Path jar) throws Exception {
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.stream().map(java.util.zip.ZipEntry::getName).collect(Collectors.toSet());
        }
    }

    @Test
    void patchesUseTheirOwnDependenciesAndRelease(@TempDir Path dir) throws Exception {
        Jdk jdk = Toolchain.require(JavaSpec.any());
        Assumptions.assumeTrue(jdk.major() >= 17, "records need Java 16+");
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Path repo = dir.resolve("repo"), work = dir.resolve("work");
        Map<String, String> message = Map.of("base/Message.java", "package base;\npublic interface Message {\n    String getString();\n}\n");
        publish(repo, work, "base", message, 8, List.of());
        publish(repo, work, "extra", Map.of("extra/ComponentLike.java", "package extra; public interface ComponentLike { }"), 8, List.of());

        Path ws = dir.resolve("app");
        Fs.write(ws.resolve("patches/base.patch"), diff(work, message, Map.of(
                "base/Message.java", "package base;\npublic interface Message extends extra.ComponentLike {\n    String getString();\n}\n",
                "base/Pair.java", "package base;\npublic record Pair(String a, String b) { }\n")));
        String manifest = "{ \"name\": \"app\", \"version\": \"1.0.0\", \"repositories\": [\"" + repo.toUri() + "\"],"
                + " \"runtime\": { \"g:base\": \"1.0\" }, \"patches\": { \"g:base\": %s } }";

        Fs.write(ws.resolve("project.jsonc"), manifest.formatted("\"patches/base.patch\""));
        Project plain = Project.load(ws);
        MavenRepo r = new MavenRepo(plain.root.repositories);
        Builder b0 = new Builder(plain, Install.run(plain, r, Install.Options.normal()), r, Env.Mode.PRODUCTION);
        java.io.PrintStream err = System.err;
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        try {
            JpmException e = assertThrows(JpmException.class, () -> b0.classpath(plain.modules.get(0), Builder.Cp.RUNTIME));
            assertTrue(e.getMessage().contains("\"dependencies\""), e.getMessage());
        } finally {
            System.setErr(err);
        }

        Fs.write(ws.resolve("project.jsonc"), manifest.formatted(
                "{ \"file\": \"patches/base.patch\", \"dependencies\": { \"g:extra\": \"1.0\" }, \"release\": 17 }"));
        Project p = Project.load(ws);
        Graph g = Install.run(p, r, Install.Options.normal());
        assertNotNull(g.get("g:extra"), "patch dependencies are resolved and locked");
        assertTrue(Fs.read(ws.resolve("project.lock")).contains("\"g:extra\""));
        Builder b = new Builder(p, g, r, Env.Mode.PRODUCTION);
        List<Path> runtime = b.classpath(p.modules.get(0), Builder.Cp.RUNTIME);
        assertEquals(1, runtime.size(), "patch dependencies are on no runtime classpath: " + runtime);
        Path patched = runtime.get(0);
        assertTrue(patched.getFileName().toString().endsWith("-patched.jar"), patched.toString());
        assertTrue(entries(patched).contains("base/Pair.class"));
        assertFalse(b.classpath(p.modules.get(0), Builder.Cp.COMPILE).stream().anyMatch(x -> x.toString().contains("extra")));

        Fs.write(ws.resolve("project.jsonc"), manifest.formatted("{ \"file\": \"patches/base.patch\", \"release\": 7 }"));
        assertThrows(JpmException.class, () -> Project.load(ws));
    }

    @Test
    void patchesModuleInfoOfModularJars(@TempDir Path dir) throws Exception {
        Jdk jdk = Toolchain.require(JavaSpec.any());
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Path repo = dir.resolve("repo"), work = dir.resolve("work");
        Map<String, String> before = Map.of(
                "module-info.java", "module g.mod {\n    exports g.mod.api;\n}\n",
                "g/mod/api/A.java", "package g.mod.api; public class A { }",
                "g/mod/internal/B.java", "package g.mod.internal; public class B { static int hidden() { return 1; } }");
        publish(repo, work, "mod", before, Math.min(jdk.major(), Runtime.version().feature()), List.of());
        Map<String, String> after = new java.util.HashMap<>(before);
        after.put("module-info.java", "module g.mod {\n    exports g.mod.api;\n    exports g.mod.internal;\n}\n");
        after.put("g/mod/internal/C.java", "package g.mod.internal; public class C { public static int f() { return B.hidden(); } }");

        Path ws = dir.resolve("app");
        Fs.write(ws.resolve("patches/mod.patch"), diff(work, before, after));
        Fs.write(ws.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"repositories\": [\"" + repo.toUri() + "\"],"
                + " \"runtime\": { \"g:mod\": \"1.0\" }, \"patches\": { \"g:mod\": \"patches/mod.patch\" } }");
        Project p = Project.load(ws);
        MavenRepo r = new MavenRepo(p.root.repositories);
        Builder b = new Builder(p, Install.run(p, r, Install.Options.normal()), r, Env.Mode.PRODUCTION);
        Path patched = b.classpath(p.modules.get(0), Builder.Cp.RUNTIME).get(0);
        assertTrue(entries(patched).contains("g/mod/internal/C.class"));
        try (JarFile jf = new JarFile(patched.toFile())) {
            ModuleDescriptor d = ModuleDescriptor.read(jf.getInputStream(jf.getEntry("module-info.class")));
            assertEquals(Set.of("g.mod.api", "g.mod.internal"), d.exports().stream().map(ModuleDescriptor.Exports::source).collect(Collectors.toSet()));
        }
    }
}
