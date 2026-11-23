package dev.jpm.e2e;

import dev.jpm.testing.TestRepo;
import dev.jpm.util.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The same sources and lockfile give byte-identical output, wherever and whenever they are built.
 */
class ReproducibleBuildTest {
    @Test
    void twoBuildsAreByteIdentical(@TempDir Path tmp) throws Exception {
        Map<String, String> first = build(tmp.resolve("first"));
        Thread.sleep(2100);
        Map<String, String> second = build(tmp.resolve("somewhere/else/second"));
        assertFalse(first.isEmpty());
        assertEquals(first, second);
    }

    private static Map<String, String> build(Path dir) {
        Map<String, String> out = new TreeMap<>();
        for (String fixture : new String[]{"workspace-demo", "bundle-demo"}) {
            Session s = new Session(dir.resolve(fixture)).fixture(fixture);
            new TestRepo(s.repo.root()).artifact("org.example:money:1.0.0").javaClass("org.example.money.Money",
                    "package org.example.money; public final class Money { public static String format(long c) { return \"\" + c; } }").publish();
            new TestRepo(s.repo.root()).artifact("org.apache.commons:commons-text:1.12.0").javaClass("org.apache.commons.text.WordUtils",
                    "package org.apache.commons.text; public final class WordUtils { public static String capitalizeFully(String s) { return s; } }").publish();
            s.jpm("build", "--bundle");
            try (Stream<Path> files = Files.walk(s.project)) {
                for (Path f : files.filter(p -> Files.isRegularFile(p) && p.toString().replace('\\', '/').contains("/dist/")).toList()) {
                    out.put(fixture + "/" + s.project.relativize(f), Hash.sha256(f));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }
}
