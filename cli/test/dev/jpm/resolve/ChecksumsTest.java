package dev.jpm.resolve;

import dev.jpm.Project;
import dev.jpm.maven.MavenRepo;
import dev.jpm.testing.TestRepo;
import dev.jpm.util.Fs;
import dev.jpm.util.Hash;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Downloads are checked against the checksums repositories publish, and project.lock pins POMs too. */
class ChecksumsTest {
    private static Path project(Path dir, Path repo, String runtime) {
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Path p = dir.resolve("p");
        Fs.write(p.resolve("project.jsonc"), "{ \"name\": \"p\", \"version\": \"1.0.0\", \"repositories\": [\"" + repo.toUri() + "\"],"
                + " \"runtime\": { " + runtime + " } }");
        return p;
    }

    private static Path jar(Path repo) {
        return repo.resolve("g/lib/1.0/lib-1.0.jar");
    }

    @Test
    void aJarThatDoesNotMatchItsPublishedChecksumIsRefused(@TempDir Path dir) throws Exception {
        Path repo = dir.resolve("repo");
        new TestRepo(repo).artifact("g:lib:1.0").publish();
        Fs.write(jar(repo).resolveSibling("lib-1.0.jar.sha1"), "0000000000000000000000000000000000000000  lib-1.0.jar\n");
        Project p = Project.load(project(dir, repo, "\"g:lib\": \"1.0\""));
        JpmException e = assertThrows(JpmException.class, () -> Install.run(p, new MavenRepo(p.root.repositories), Install.Options.normal()));
        assertTrue(e.getMessage().contains("Checksum mismatch for") && e.getMessage().contains("sha1 published: 0000"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("home/repository/g/lib/1.0/lib-1.0.jar")), "nothing is kept");
        Fs.write(jar(repo).resolveSibling("lib-1.0.jar.sha1"), Hash.of("sha1", jar(repo)) + "\n");
        Graph g = Install.run(p, new MavenRepo(p.root.repositories), Install.Options.normal());
        assertEquals(Hash.sha256(jar(repo)), g.get("g:lib").sha256);
    }

    @Test
    void theStrongestPublishedChecksumDecides(@TempDir Path dir) {
        Path repo = dir.resolve("repo");
        new TestRepo(repo).artifact("g:lib:1.0").publish();
        Fs.write(jar(repo).resolveSibling("lib-1.0.jar.sha1"), Hash.of("sha1", jar(repo)) + "\n");
        Fs.write(jar(repo).resolveSibling("lib-1.0.jar.sha512"), "ab".repeat(64) + "\n");
        Project p = Project.load(project(dir, repo, "\"g:lib\": \"1.0\""));
        JpmException e = assertThrows(JpmException.class, () -> Install.run(p, new MavenRepo(p.root.repositories), Install.Options.normal()));
        assertTrue(e.getMessage().contains("sha512 published"), e.getMessage());
    }

    @Test
    void theLockPinsPomsAndRefusesOneThatChanged(@TempDir Path dir) {
        Path repo = dir.resolve("repo");
        TestRepo r = new TestRepo(repo);
        r.artifact("g:lib:1.0").publish();
        r.artifact("g:other:1.0").publish();
        Path p = project(dir, repo, "\"g:lib\": \"1.0\"");
        Project project = Project.load(p);
        Graph g = Install.run(project, new MavenRepo(project.root.repositories), Install.Options.normal());
        String pinned = g.get("g:lib").pomSha256;
        assertNotNull(pinned);
        assertEquals(pinned, Lockfile.read(project.lockFile()).graph.get("g:lib").pomSha256);
        Path pom = repo.resolve("g/lib/1.0/lib-1.0.pom");
        Fs.write(pom, Fs.read(pom).replace("</project>", "<description>changed</description></project>"));
        Fs.deleteTree(dir.resolve("home/repository/g/lib"));
        Fs.write(p.resolve("project.jsonc"), Fs.read(p.resolve("project.jsonc")).replace("\"g:lib\": \"1.0\"", "\"g:lib\": \"1.0\", \"g:other\": \"1.0\""));
        Project changed = Project.load(p);
        JpmException e = assertThrows(JpmException.class, () -> Install.run(changed, new MavenRepo(changed.root.repositories), Install.Options.normal()));
        assertTrue(e.getMessage().contains("Checksum mismatch for the POM of g:lib 1.0"), e.getMessage());
    }
}
