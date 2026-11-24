package dev.jpm.cli.impl;

import dev.jpm.DepKey;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Graph;
import dev.jpm.testing.TestRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExtensionsTest {
    private static Graph.Pkg locked(String key, String version) {
        Graph.Pkg p = new Graph.Pkg(key, Graph.Kind.MAVEN);
        p.version = version;
        return p;
    }

    @Test
    void findsTheUpdateInTheRangeAndTheNewestOutsideIt(@TempDir Path dir) {
        TestRepo t = new TestRepo(dir.resolve("repo"));
        for (String v : List.of("1.0.0", "1.2.0", "1.3.0-beta.1", "2.0.0")) t.publish("org.example:jpm-lint:" + v);
        MavenRepo repo = new MavenRepo(List.of(t.uri()));
        DepKey key = DepKey.parse("org.example:jpm-lint");
        assertEquals(new GlobalExtensions.Available("1.2.0", "2.0.0"),
                GlobalExtensions.available(repo, key, "[1.0,2.0)", locked("org.example:jpm-lint", "1.0.0")));
        assertEquals(new GlobalExtensions.Available(null, "2.0.0"),
                GlobalExtensions.available(repo, key, "[1.0,2.0)", locked("org.example:jpm-lint", "1.2.0")));
        assertEquals(new GlobalExtensions.Available(null, null),
                GlobalExtensions.available(repo, key, "[1.0,)", locked("org.example:jpm-lint", "2.0.0")));
        assertEquals(new GlobalExtensions.Available("2.0.0", null),
                GlobalExtensions.available(repo, key, "[1.0,)", null));
    }
}
