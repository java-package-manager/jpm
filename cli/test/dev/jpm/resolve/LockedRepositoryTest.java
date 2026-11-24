package dev.jpm.resolve;

import dev.jpm.Project;
import dev.jpm.api.json.Jsonc;
import dev.jpm.check.Problem;
import dev.jpm.check.ProjectChecks;
import dev.jpm.maven.MavenRepo;
import dev.jpm.testing.TestRepo;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockedRepositoryTest {
    @AfterEach
    void clear() {
        System.clearProperty("jpm.mavenCentral");
    }

    @Test
    void theLockNamesTheRepositoryAndCheckWarnsAboutUnusedOnes(@TempDir Path tmp) {
        TestRepo central = new TestRepo(tmp.resolve("central"));
        central.publish("org.central:lib:1.0.0");
        TestRepo used = new TestRepo(tmp.resolve("used"));
        used.publish("com.example:lib:1.0.0");
        TestRepo unused = new TestRepo(tmp.resolve("unused"));
        System.setProperty("jpm.mavenCentral", central.uri());
        Path dir = tmp.resolve("app");
        Fs.write(dir.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"repositories\": ["
                + Jsonc.quote(unused.uri()) + ", " + Jsonc.quote(used.uri()) + "],"
                + " \"runtime\": { \"com.example:lib\": \"1.0.0\", \"org.central:lib\": \"1.0.0\" } }");
        Project p = Project.find(dir);
        MavenRepo repo = new MavenRepo(p.root.repositories);
        Graph g = Install.run(p, repo, Install.Options.normal());
        assertEquals(used.uri(), g.get("com.example:lib").repository);
        assertNull(g.get("org.central:lib").repository);
        Lockfile lock = Lockfile.read(p.lockFile());
        assertEquals(used.uri(), lock.graph.get("com.example:lib").repository);
        assertTrue(Fs.read(p.lockFile()).contains("\"repository\": " + Jsonc.quote(used.uri())));

        List<Problem> problems = ProjectChecks.repositories(p, g, repo);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).message().contains(unused.uri()), problems.toString());

        Project again = Project.find(dir);
        MavenRepo second = new MavenRepo(again.root.repositories);
        Graph g2 = Install.run(again, second, Install.Options.normal());
        assertEquals(used.uri(), second.order("com.example", "lib").get(0));
        assertEquals(used.uri(), g2.get("com.example:lib").repository);
    }
}
