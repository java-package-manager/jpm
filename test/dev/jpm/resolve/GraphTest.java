package dev.jpm.resolve;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphTest {
    private static Graph.Pkg pkg(Graph g, String id, String... deps) {
        Graph.Pkg p = new Graph.Pkg(id, Graph.Kind.MAVEN);
        p.version = "1";
        for (String d : deps) {
            boolean rt = d.startsWith("rt:");
            String t = rt ? d.substring(3) : d;
            p.deps.put(t, new Graph.Edge(t, rt, Set.of()));
        }
        g.put(p);
        return p;
    }

    @Test
    void closureHonorsScopesAndExclusions() {
        Graph g = new Graph();
        pkg(g, "a:a", "b:b", "rt:c:c");
        pkg(g, "b:b", "d:d");
        pkg(g, "c:c");
        pkg(g, "d:d");
        List<Graph.Edge> roots = List.of(new Graph.Edge("a:a", false, Set.of()));
        assertEquals(List.of("a:a", "b:b", "c:c", "d:d"), g.closure(roots, Graph.Walk.RUNTIME));
        assertEquals(List.of("a:a", "b:b", "d:d"), g.closure(roots, Graph.Walk.COMPILE));
        List<Graph.Edge> excl = List.of(new Graph.Edge("a:a", false, Set.of("d:*")));
        assertEquals(List.of("a:a", "b:b", "c:c"), g.closure(excl, Graph.Walk.RUNTIME));
    }

    @Test
    void exclusionsApplyPerPath() {
        Graph g = new Graph();
        pkg(g, "a:a", "x:x");
        pkg(g, "b:b", "x:x");
        pkg(g, "x:x");
        List<Graph.Edge> roots = List.of(new Graph.Edge("a:a", false, Set.of("x:x")), new Graph.Edge("b:b", false, Set.of()));
        assertTrue(g.closure(roots, Graph.Walk.RUNTIME).contains("x:x"));
    }

    @Test
    void lockfileRoundTrips() throws Exception {
        Graph g = new Graph();
        Graph.Pkg a = pkg(g, "a:a", "b:b");
        a.sha256 = "abc";
        a.deps.put("c:c", new Graph.Edge("c:c", true, Set.of("x:y", "z:*")));
        Graph.Pkg git = new Graph.Pkg("github:u/r", Graph.Kind.GIT);
        git.version = "1.2.0";
        git.tag = "v1.2.0";
        git.commit = "0123456789abcdef0123456789abcdef01234567";
        g.put(git);
        pkg(g, "b:b");
        pkg(g, "c:c");
        Path f = java.nio.file.Files.createTempFile("jpm", ".lock");
        new Lockfile("inputs1", g).write(f);
        Lockfile back = Lockfile.read(f);
        assertEquals("inputs1", back.inputs);
        assertEquals(new Lockfile("inputs1", g).render(), back.render());
        assertEquals(Graph.Kind.GIT, back.graph.get("github:u/r").kind);
        assertEquals(Set.of("x:y", "z:*"), back.graph.get("a:a").deps.get("c:c").exclusions());
        assertTrue(back.graph.get("a:a").deps.get("c:c").runtimeOnly());
        // one line per package
        assertEquals(4, back.render().lines().filter(l -> l.startsWith("    \"")).count());
    }

    @Test
    void tagVersions() {
        DepKey api = DepKey.parse("github:u/r/api");
        DepKey plain = DepKey.parse("github:u/r");
        List<String> lockstep = List.of("v1.0.0", "v1.1.0", "nightly");
        assertEquals("1.1.0", Resolver.tagVersion(api, "v1.1.0", lockstep));
        assertNull(Resolver.tagVersion(plain, "nightly", lockstep));
        List<String> prefixed = List.of("api/v1.2.0", "impl/v0.3.0", "v9.0.0");
        assertEquals("1.2.0", Resolver.tagVersion(api, "api/v1.2.0", prefixed));
        assertNull(Resolver.tagVersion(api, "v9.0.0", prefixed));
        assertNull(Resolver.tagVersion(api, "impl/v0.3.0", prefixed));
    }

    @Test
    void depKeys() {
        assertEquals("com.google.guava:guava", DepKey.parse("com.google.guava:guava").toString());
        DepKey g = DepKey.parse("github:user/repo/api");
        assertTrue(g.isGit());
        assertEquals("api", g.module());
        assertEquals("github:user/repo", g.repoKey().toString());
        assertThrows(JpmException.class, () -> DepKey.parse("guava"));
        assertThrows(JpmException.class, () -> DepKey.parse("a:b:1.0"));
        assertThrows(JpmException.class, () -> DepKey.parse("github:user"));
    }

    @Test
    void classifiersAndDependencyOptions() {
        DepKey natives = DepKey.parse("org.lwjgl:lwjgl:natives-linux");
        assertEquals("natives-linux", natives.classifier());
        assertEquals("org.lwjgl:lwjgl:natives-linux", natives.toString());
        Manifest m = Manifest.parse(Path.of("project.jsonc"), """
                { "name": "x", "runtime": {
                    "a:b": { "version": "1.0", "exclude": ["c:d", "e:*"] },
                    "f:g": { "version": "2.0", "runtimeOnly": true },
                    "h:i:linux": "3.0" } }
                """);
        Manifest.Dependency ab = m.dependencies.get(0);
        assertEquals("1.0", ab.spec());
        assertEquals(Set.of("c:d", "e:*"), ab.exclusions());
        assertTrue(m.dependencies.get(1).runtimeOnly());
        assertEquals("linux", m.dependencies.get(2).key().classifier());
        assertThrows(JpmException.class, () -> Manifest.parse(Path.of("project.jsonc"),
                "{ \"name\": \"x\", \"test\": { \"a:b\": { \"version\": \"1\", \"runtimeOnly\": true } } }"));
        assertThrows(JpmException.class, () -> Manifest.parse(Path.of("project.jsonc"),
                "{ \"name\": \"x\", \"runtime\": { \"a:b\": { \"version\": \"1\", \"excludes\": [] } } }"));
        // exclusions by group:artifact also cover classified jars
        assertTrue(Graph.excluded("h:i:linux", Set.of("h:i")));
        Graph.Pkg p = new Graph.Pkg("h:i:linux", Graph.Kind.MAVEN);
        assertEquals("i", p.name());
        assertEquals("linux", p.classifier());
    }

    @Test
    void manifestErrorsNameTheLine() {
        JpmException e = assertThrows(JpmException.class, () -> Manifest.parse(Path.of("project.jsonc"),
                "{\n  \"name\": \"x\",\n  \"runtime\": {\n    \"guava\": \"1\"\n  }\n}"));
        assertEquals("project.jsonc:4", e.location());
        JpmException dup = assertThrows(JpmException.class, () -> Manifest.parse(Path.of("project.jsonc"),
                "{ \"name\": \"x\", \"runtime\": { \"a:b\": \"1\" },\n \"dev\": { \"a:b\": \"2\" } }"));
        assertEquals("project.jsonc:2", dup.location());
        assertTrue(dup.getMessage().contains("different versions"), dup.getMessage());
        JpmException unknown = assertThrows(JpmException.class, () -> Manifest.parse(Path.of("project.jsonc"),
                "{ \"name\": \"x\",\n \"dependencies\": {} }"));
        assertEquals("project.jsonc:2", unknown.location());
        assertEquals("unknown field \"dependencies\"", unknown.getMessage());
        JpmException typo = assertThrows(JpmException.class, () -> Manifest.parse(Path.of("project.jsonc"), "{ \"name\": \"x\", \"runtim\": {} }"));
        assertEquals("did you mean \"runtime\"?", typo.hint());
    }

    @Test
    void manifestReadsSpecExample() {
        Manifest m = Manifest.parse(Path.of("project.jsonc"), """
                {
                  "name": "my-app",
                  "version": "1.3.0",
                  "java": "graalvm-21",   // installed automatically if missing
                  "target": "17",         // bytecode target, defaults to java
                  "main": "com.example.App",
                  "runtime": {
                    "com.google.guava:guava": "[33.0,34.0)",
                    "github:user/repo/api": "^1.2"
                  },
                  "dev": { "org.projectlombok:lombok": "1.18.34" },
                  "test": { "org.junit.jupiter:junit-jupiter": "5.11.0" },
                  "define": { "API_URL": { "type": "string", "env": "API_URL" } },
                  "scripts": { "format": "scripts/format.sh", "upload-mappings": "scripts/UploadMappings.java" },
                  "hooks": { "postbuild:release": "upload-mappings" },
                  "patches": { "com.example:old-lib": "patches/old-lib.patch" }
                }
                """);
        assertEquals(21, m.javaMajor());
        assertEquals(17, m.targetMajor());
        assertEquals(4, m.dependencies.size());
        assertEquals("com.example.App", m.mains.get("main"));
        assertEquals("API_URL", m.defines.get("API_URL").env());
        assertEquals("upload-mappings", m.hooks.get("postbuild:release"));
    }
}
