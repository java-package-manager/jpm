package dev.jpm.cli;

import dev.jpm.Manifest;
import dev.jpm.maven.MavenRepo;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoriesTest {
    @Test
    void addsRepositoriesOnce() {
        String src = "{\n  // keep me\n  \"name\": \"x\",\n  \"repositories\": [\"https://repo.a.dev/releases\"]\n}\n";
        Manifest m = Manifest.parse(Path.of("project.jsonc"), src);
        String out = Commands.withRepositories(src, m, List.of("https://repo.a.dev/releases/", "https://repo.b.dev"));
        Manifest after = Manifest.parse(Path.of("project.jsonc"), out);
        assertEquals(List.of("https://repo.a.dev/releases", "https://repo.b.dev"), after.repositories);
        assertTrue(out.contains("// keep me"));
        assertEquals(out, Commands.withRepositories(out, after, List.of("https://repo.b.dev/")));
        assertThrows(JpmException.class, () -> Commands.withRepositories(src, m, List.of("repo.c.dev")));
    }

    @Test
    void readsFileRepositories(@TempDir Path dir) {
        Path repo = dir.resolve("repo");
        Fs.write(repo.resolve("g/a/1.0/a-1.0.pom"), "<project><groupId>g</groupId><artifactId>a</artifactId><version>1.0</version></project>");
        System.setProperty("jpm.home", dir.resolve("home").toString());
        System.setProperty("jpm.mavenCentral", dir.resolve("central").toUri().toString());
        try {
            MavenRepo r = new MavenRepo(List.of(repo.toUri().toString()));
            Path pom = r.fetch("g", "a", "1.0", null, "pom");
            assertNotNull(pom);
            assertTrue(pom.startsWith(dir.resolve("home")), pom.toString());
            assertTrue(Fs.read(pom).contains("<artifactId>a</artifactId>"));
        } finally {
            System.clearProperty("jpm.home");
            System.clearProperty("jpm.mavenCentral");
        }
    }
}
