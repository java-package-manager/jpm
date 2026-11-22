package dev.jpm.resolve;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.maven.MavenRepo;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "api": true on a dev dependency: consumers compile against it (Gradle's compileOnlyApi), nothing runs with it. */
class ApiDevDependencyTest {
    private static void publish(Path repo, String group, String artifact, String version, String cls) throws IOException {
        Path dir = repo.resolve(group.replace('.', '/') + "/" + artifact);
        Fs.write(dir.resolve(version + "/" + artifact + "-" + version + ".pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>"
                + group + "</groupId><artifactId>" + artifact + "</artifactId><version>" + version + "</version></project>");
        Path jar = dir.resolve(version + "/" + artifact + "-" + version + ".jar");
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry(cls));
            z.write(new byte[]{1});
        }
        Fs.write(dir.resolve("maven-metadata.xml"), "<metadata><versioning><versions><version>" + version + "</version></versions></versioning></metadata>");
    }

    @Test
    void manifestOption() {
        Manifest m = Manifest.parse(Path.of("project.jsonc"), """
                { "name": "lib", "dev": { "a:api": { "version": "1", "api": true }, "a:private": "1" } }
                """);
        assertTrue(m.deps(Manifest.Scope.DEV).stream().filter(d -> d.key().toString().equals("a:api")).findFirst().orElseThrow().api());
        assertFalse(m.deps(Manifest.Scope.DEV).stream().filter(d -> d.key().toString().equals("a:private")).findFirst().orElseThrow().api());
        JpmException e = assertThrows(JpmException.class, () -> Manifest.parse(Path.of("project.jsonc"),
                "{ \"name\": \"lib\", \"runtime\": { \"a:b\": { \"version\": \"1\", \"api\": true } } }"));
        assertTrue(e.getMessage().contains("runtime dependencies are always passed on"), e.getMessage());
    }

    @Test
    void workspaceSiblingsCompileAgainstApiDevDependencies(@TempDir Path tmp) throws IOException {
        System.setProperty("jpm.home", tmp.resolve("home").toString());
        Path repo = tmp.resolve("repo");
        publish(repo, "io.papermc.paper", "paper-api", "26.3", "org/bukkit/Bukkit.class");
        publish(repo, "org.projectlombok", "lombok", "1.18.34", "lombok/Lombok.class");
        Path ws = tmp.resolve("ws");
        String repos = "\"repositories\": [" + dev.jpm.json.Jsonc.quote(repo.toUri().toString()) + "]";
        Fs.write(ws.resolve("project.jsonc"), "{ \"name\": \"ws\", \"version\": \"1.0.0\", " + repos + ", \"workspace\": [\"lib\", \"app\"] }");
        Fs.write(ws.resolve("lib/project.jsonc"), "{ \"name\": \"lib\", \"version\": \"1.0.0\", \"java\": \"21\", " + repos + ","
                + " \"dev\": { \"io.papermc.paper:paper-api\": { \"version\": \"26.3\", \"api\": true }, \"org.projectlombok:lombok\": \"1.18.34\" } }");
        Fs.write(ws.resolve("lib/src/lib/Lib.java"), "package lib; public class Lib {}");
        Fs.write(ws.resolve("app/project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"java\": \"21\", " + repos + ","
                + " \"runtime\": { \"workspace:lib\": \"*\" } }");
        Fs.write(ws.resolve("app/src/app/App.java"), "package app; public class App {}");

        Project p = Project.load(ws);
        MavenRepo mr = new MavenRepo(p.root.repositories);
        Graph g = Install.run(p, mr, new Install.Options(false, true, Set.of(), true));
        Builder b = new Builder(p, g, mr, dev.jpm.build.Env.Mode.PRODUCTION);
        Project.Module app = p.module("app");
        List<String> compile = names(b.classpath(app, Builder.Cp.COMPILE));
        List<String> runtime = names(b.classpath(app, Builder.Cp.RUNTIME));
        assertTrue(compile.contains("paper-api-26.3.jar"), "app compiles against lib's api dev dependency: " + compile);
        assertFalse(compile.contains("lombok-1.18.34.jar"), "lib's other dev dependencies stay its own");
        assertFalse(runtime.contains("paper-api-26.3.jar"), "nothing runs with it: " + runtime);

        List<String> bundled = new ArrayList<>();
        try (ZipFile zf = new ZipFile(b.flatBundle(app).toFile())) {
            zf.stream().forEach(e -> bundled.add(e.getName()));
        }
        assertFalse(bundled.contains("org/bukkit/Bukkit.class"), bundled.toString());
        assertTrue(bundled.contains("lib/Lib.class"));

        assertTrue(g.get("workspace:lib").deps.get("io.papermc.paper:paper-api").compileOnly());
    }

    private static List<String> names(List<Path> paths) {
        return paths.stream().map(x -> x.getFileName().toString()).toList();
    }
}
