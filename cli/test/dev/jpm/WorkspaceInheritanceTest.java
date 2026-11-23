package dev.jpm;

import dev.jpm.resolve.ResolveInputs;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Workspace modules take java, target, group, license, url, layout and flags from the root.
 */
class WorkspaceInheritanceTest {
    @Test
    void modulesInheritFromTheRoot(@TempDir Path dir) {
        Fs.write(dir.resolve("project.jsonc"), """
                {
                  "name": "ws", "version": "1.0.0", "group": "org.example", "java": "25", "target": "21",
                  "license": "MIT", "url": "https://example.org", "layout": "maven",
                  "javacArgs": ["-Xlint:all"], "nativeArgs": ["-O2"],
                  "workspace": ["a", "b"]
                }
                """);
        Fs.write(dir.resolve("a/project.jsonc"), """
                { "name": "a" }
                """);
        Fs.write(dir.resolve("b/project.jsonc"), """
                { "name": "b", "java": "21", "group": "org.other", "layout": "jpm", "javacArgs": ["-Werror"] }
                """);
        Project p = Project.load(dir);
        Manifest a = p.module("a").manifest, b = p.module("b").manifest;
        assertEquals("25", a.java);
        assertEquals("21", a.target);
        assertEquals("org.example", a.group);
        assertEquals("MIT", a.license);
        assertEquals("https://example.org", a.url);
        assertSame(Layout.MAVEN, a.layout);
        assertEquals(List.of("-Xlint:all"), a.javacArgs);
        assertEquals(List.of("-O2"), a.nativeArgs);
        assertEquals("21", b.java);
        assertEquals("org.other", b.group);
        assertSame(Layout.JPM, b.layout);
        assertEquals(List.of("-Xlint:all", "-Werror"), b.javacArgs);
        assertEquals(p.module("a").dir.resolve("src/main/java"), p.module("a").srcDir());
    }

    @Test
    void singleModuleProjectsAreUnchanged(@TempDir Path dir) {
        Fs.write(dir.resolve("project.jsonc"), """
                { "name": "solo", "version": "1.0.0" }
                """);
        Manifest m = Project.load(dir).modules.get(0).manifest;
        assertNull(m.java);
        assertSame(Layout.JPM, m.layout);
    }

    @org.junit.jupiter.api.Test
    void aWorkspaceDependencyMayNameTheModulesFolder(@org.junit.jupiter.api.io.TempDir Path dir) {
        dev.jpm.util.Fs.write(dir.resolve("project.jsonc"), "{ \"name\": \"ws\", \"version\": \"1.0.0\", \"workspace\": [\"lib\", \"app\"] }");
        dev.jpm.util.Fs.write(dir.resolve("lib/project.jsonc"), "{ \"name\": \"my-lib\" }");
        dev.jpm.util.Fs.write(dir.resolve("app/project.jsonc"), "{ \"name\": \"app\", \"runtime\": { \"workspace:lib\": \"*\" } }");
        Project p = Project.load(dir);
        assertEquals("workspace:my-lib", p.module("app").manifest.dependencies.get(0).key().toString());
        assertEquals(java.util.List.of(new dev.jpm.resolve.Graph.Edge("workspace:my-lib", false, java.util.Set.of())),
                dev.jpm.resolve.ResolveInputs.rootEdges(p.module("app"), Manifest.Scope.RUNTIME));
    }
}
