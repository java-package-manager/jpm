package dev.jpm.resolve;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.maven.MavenRepo;
import dev.jpm.testing.TestRepo;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Extensions declared in the root manifest are resolved on their own and pinned in project.lock's "extensions" section. */
class ProjectExtensionsTest {
    private static String dep(String a, String v) {
        return "<dependency><groupId>g</groupId><artifactId>" + a + "</artifactId><version>" + v + "</version></dependency>";
    }

    private Path project(Path dir, String extensions) {
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Path repo = dir.resolve("repo");
        TestRepo r = new TestRepo(repo);
        r.artifact("g:annotations:1.0").publish();
        r.artifact("g:annotations:2.0").publish();
        r.artifact("g:lib:1.0").dependenciesXml(dep("annotations", "1.0")).publish();
        r.artifact("g:jpm-tool:1.0").dependenciesXml(dep("annotations", "2.0")).publish();
        r.artifact("g:jpm-tool:1.1").dependenciesXml(dep("annotations", "2.0")).publish();
        Path p = dir.resolve("p");
        Fs.write(p.resolve("project.jsonc"), "{ \"name\": \"p\", \"version\": \"1.0.0\", \"repositories\": [\"" + repo.toUri() + "\"],"
                + " \"runtime\": { \"g:lib\": \"1.0\" }, \"extensions\": " + extensions + " }");
        return p;
    }

    @Test
    void anExtensionIsResolvedOnItsOwnAndLocked(@TempDir Path dir) {
        Path p = project(dir, "{ \"g:jpm-tool\": \"1.0\", \"tool\": { \"x\": 1 } }");
        Project project = Project.load(p);
        assertEquals("tool", project.root.declaredExtensions.get(0).name());
        Graph g = Install.run(project, new MavenRepo(project.root.repositories), Install.Options.normal());
        assertEquals("1.0", g.get("g:annotations").version, "the project keeps its own versions");
        assertNull(g.get("g:jpm-tool"));
        Graph tool = g.extensions.get("tool");
        assertNotNull(tool);
        assertEquals("1.0", tool.get("g:jpm-tool").version);
        assertEquals("2.0", tool.get("g:annotations").version);
        Lockfile lock = Lockfile.read(project.lockFile());
        assertEquals("1.0", lock.graph.extensions.get("tool").get("g:jpm-tool").version);
        assertTrue(Fs.read(project.lockFile()).contains("\"extensions\": {\n    \"tool\": { \"packages\": {"), Fs.read(project.lockFile()));
        Graph again = Install.run(Project.load(p), new MavenRepo(project.root.repositories), new Install.Options(true, false, Set.of(), true));
        assertEquals("1.0", again.extensions.get("tool").get("g:jpm-tool").version, "--frozen accepts the locked extension");
    }

    @Test
    void changingAnExtensionsVersionMakesTheLockStale(@TempDir Path dir) {
        Path p = project(dir, "{ \"g:jpm-tool\": \"1.0\" }");
        Project project = Project.load(p);
        Install.run(project, new MavenRepo(project.root.repositories), Install.Options.normal());
        Fs.write(p.resolve("project.jsonc"), Fs.read(p.resolve("project.jsonc")).replace("\"g:jpm-tool\": \"1.0\"", "\"g:jpm-tool\": \"1.1\""));
        Project changed = Project.load(p);
        MavenRepo repo = new MavenRepo(changed.root.repositories);
        assertThrows(JpmException.class, () -> Install.run(changed, repo, new Install.Options(true, false, Set.of(), true)));
        assertEquals("1.1", Install.run(changed, repo, Install.Options.normal()).extensions.get("tool").get("g:jpm-tool").version);
    }

    @Test
    void removingTheDeclarationDropsItsSection(@TempDir Path dir) {
        Path p = project(dir, "{ \"g:jpm-tool\": \"1.0\" }");
        Project project = Project.load(p);
        Install.run(project, new MavenRepo(project.root.repositories), Install.Options.normal());
        Fs.write(p.resolve("project.jsonc"), Fs.read(p.resolve("project.jsonc")).replace("\"g:jpm-tool\": \"1.0\"", ""));
        Project without = Project.load(p);
        Install.run(without, new MavenRepo(without.root.repositories), Install.Options.normal());
        assertFalse(Fs.read(project.lockFile()).contains("extensions"));
    }

    @Test
    void declarationsAreChecked(@TempDir Path dir) {
        JpmException version = assertThrows(JpmException.class, () -> Manifest.load(project(dir, "{ \"g:jpm-tool\": { \"v\": 1 } }")));
        assertTrue(version.getMessage().contains("needs a version"), version.getMessage());
        JpmException settings = assertThrows(JpmException.class, () -> Manifest.load(project(dir, "{ \"tool\": \"1.0\" }")));
        assertTrue(settings.getMessage().contains("must be an object of settings"), settings.getMessage());
        JpmException twice = assertThrows(JpmException.class, () -> Manifest.load(project(dir, "{ \"g:jpm-tool\": \"1\", \"h:tool\": \"1\" }")));
        assertTrue(twice.getMessage().contains("are both the extension tool"), twice.getMessage());
        JpmException workspace = assertThrows(JpmException.class, () -> Manifest.load(project(dir, "{ \"workspace:a\": \"*\" }")));
        assertTrue(workspace.getMessage().contains("cannot be an extension"), workspace.getMessage());
    }

    @Test
    void onlyTheRootDeclaresExtensions(@TempDir Path dir) {
        Path ws = dir.resolve("ws");
        Fs.write(ws.resolve("project.jsonc"), "{ \"name\": \"ws\", \"version\": \"1.0.0\", \"workspace\": [\"a\"] }");
        Fs.write(ws.resolve("a/project.jsonc"), "{ \"name\": \"a\", \"extensions\": { \"g:jpm-tool\": \"1.0\" } }");
        JpmException e = assertThrows(JpmException.class, () -> Project.load(ws));
        assertTrue(e.getMessage().contains("declare extensions in the workspace root"), e.getMessage());
    }

    @Test
    void theMarkerMakesAManifestAnExtension(@TempDir Path dir) {
        Fs.write(dir.resolve("e/project.jsonc"), "{ \"name\": \"jpm-x\", \"version\": \"1.0.0\", \"extension\": {} }");
        assertTrue(Manifest.load(dir.resolve("e")).isExtension);
        Fs.write(dir.resolve("f/project.jsonc"), "{ \"name\": \"jpm-x\", \"version\": \"1.0.0\", \"extension\": true }");
        assertThrows(JpmException.class, () -> Manifest.load(dir.resolve("f")));
    }

    private static JpmException descriptorError(Path dir, String descriptor) {
        Fs.write(dir.resolve("d/project.jsonc"), "{ \"name\": \"jpm-lint\", \"version\": \"1.0.0\", \"extension\": " + descriptor + " }");
        return assertThrows(JpmException.class, () -> Manifest.load(dir.resolve("d")));
    }

    @Test
    void readsTheDescriptor(@TempDir Path dir) {
        Fs.write(dir.resolve("e/project.jsonc"), "{ \"name\": \"jpm-lint\", \"version\": \"1.0.0\", \"extension\": {"
                + " \"description\": \"Lints\", \"commands\": { \"lint\": { \"options\": { \"--module\": { \"argument\": \"name\" } } } },"
                + " \"checks\": { \"style\": {} } } }");
        Manifest.ExtensionDescriptor d = Manifest.load(dir.resolve("e")).extension;
        assertEquals("Lints", d.description());
        assertEquals(new Manifest.ExtensionOption("--module", "name", null), d.commands().get(0).options().get(0));
        assertEquals("style", d.checks().get(0).id());
        assertTrue(descriptorError(dir, "{ \"commands\": { \"format\": {} } }").getMessage().contains("must start with \"lint\""));
        assertTrue(descriptorError(dir, "{ \"commands\": { \"lint\": { \"options\": { \"fix\": {} } } } }").getMessage().contains("must start with -"));
        assertTrue(descriptorError(dir, "{ \"checks\": { \"Style\": {} } }").getMessage().contains("a check id is lowercase"));
        assertTrue(descriptorError(dir, "{ \"command\": {} }").getMessage().contains("unknown field \"command\" in extension"));
        assertTrue(descriptorError(dir, "{ \"description\": 1 }").getMessage().contains("description must be a string"));
    }

    @Test
    void anExtensionMayConfigureItself(@TempDir Path dir) {
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Fs.write(dir.resolve("e/project.jsonc"), "{ \"name\": \"jpm-lint\", \"version\": \"1.0.0\", \"extension\": {},"
                + " \"extensions\": { \"lint\": { \"strict\": true }, \"other\": {} } }");
        List<String> warnings = new java.util.ArrayList<>();
        dev.jpm.log.Log.sink((level, message) -> {
            if (level == dev.jpm.log.Log.Level.WARN) warnings.add(message);
        });
        try {
            Project p = Project.load(dir.resolve("e"));
            Install.run(p, new MavenRepo(p.root.repositories), Install.Options.normal());
        } finally {
            dev.jpm.log.Log.sink(null);
        }
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("\"other\""), warnings.toString());
    }
}
