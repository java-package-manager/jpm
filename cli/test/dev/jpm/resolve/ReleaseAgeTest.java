package dev.jpm.resolve;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.check.Problem;
import dev.jpm.check.ProjectChecks;
import dev.jpm.maven.MavenRepo;
import dev.jpm.testing.TestRepo;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The minimum release age (ADR 0018): jpm picks old enough versions, and the check finds young ones.
 */
class ReleaseAgeTest {
    @AfterEach
    void clear() {
        System.clearProperty("jpm.mavenCentral");
    }

    private static Path repo(Path dir, int... daysOld) throws Exception {
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Path repo = dir.resolve("repo");
        System.setProperty("jpm.mavenCentral", repo.toUri().toString());
        TestRepo r = new TestRepo(repo);
        for (int i = 0; i < daysOld.length; i++) {
            r.artifact("g:lib:1." + i).publish();
            Path pom = repo.resolve("g/lib/1." + i + "/lib-1." + i + ".pom");
            Files.setLastModifiedTime(pom, FileTime.from(Instant.now().minus(Duration.ofDays(daysOld[i]))));
        }
        return repo;
    }

    private static Project project(Path dir, String runtime, String more) {
        Path p = dir.resolve("p");
        Fs.write(p.resolve("project.jsonc"), "{ \"name\": \"p\", \"version\": \"1.0.0\", \"minimumReleaseAge\": \"7 days\"" + more
                + ", \"runtime\": { " + runtime + " } }");
        return Project.load(p);
    }

    private static Graph install(Project p) {
        return Install.run(p, new MavenRepo(p.root.repositories), Install.Options.normal());
    }

    @Test
    void aRangeTakesTheHighestVersionThatIsOldEnough(@TempDir Path dir) throws Exception {
        repo(dir, 30, 10, 1);
        assertEquals("1.1", install(project(dir, "\"g:lib\": \"^1.0\"", "")).get("g:lib").version);
    }

    @Test
    void anExceptionLetsItsVersionBeYounger(@TempDir Path dir) throws Exception {
        repo(dir, 30, 10, 1);
        assertEquals("1.2", install(project(dir, "\"g:lib\": \"^1.0\"", ", \"releaseAgeExceptions\": { \"g:lib\": \"1.2\" }")).get("g:lib").version);
    }

    @Test
    void whenNothingIsOldEnoughTheNewestIsTaken(@TempDir Path dir) throws Exception {
        repo(dir, 2, 1);
        assertEquals("1.1", install(project(dir, "\"g:lib\": \"^1.0\"", "")).get("g:lib").version);
    }

    @Test
    void theCheckFailsOnAYoungLockedVersionAndShowsTheException(@TempDir Path dir) throws Exception {
        Path repo = repo(dir, 30, 1);
        Project p = project(dir, "\"g:lib\": \"1.1\"", "");
        List<Problem> problems = ProjectChecks.releaseAge(p, install(p), new MavenRepo(p.root.repositories));
        assertEquals(1, problems.size(), problems.toString());
        Problem e = problems.get(0);
        assertEquals(Problem.Severity.ERROR, e.severity());
        assertTrue(e.message().startsWith("g:lib 1.1 was released 1 day ago, less than the minimum release age (7 days)"), e.message());
        assertTrue(e.hint().contains("\"releaseAgeExceptions\": {\"g:lib\": \"1.1\"}"), e.hint());
        assertEquals(1, e.line());
        assertTrue(Files.exists(dir.resolve("home/repository/g/lib/1.1/lib-1.1.pom.released")), "the release time is cached");
        assertTrue(Files.exists(repo));
    }

    @Test
    void anExceptedVersionPassesAndStaleExceptionsWarn(@TempDir Path dir) throws Exception {
        repo(dir, 30, 1);
        Project p = project(dir, "\"g:lib\": \"1.1\"", ", \"releaseAgeExceptions\": { \"g:lib\": \"1.1\" }");
        assertEquals(List.of(), ProjectChecks.releaseAge(p, install(p), new MavenRepo(p.root.repositories)));
        Project old = project(dir, "\"g:lib\": \"1.0\"", ", \"releaseAgeExceptions\": { \"g:lib\": \"1.0\", \"g:gone\": \"2.0\" }");
        List<Problem> problems = ProjectChecks.releaseAge(old, install(old), new MavenRepo(old.root.repositories));
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.stream().allMatch(x -> x.severity() == Problem.Severity.WARNING), problems.toString());
        assertTrue(problems.get(0).message().contains("g:lib 1.0 is not needed: it was released 30 days ago"), problems.toString());
        assertTrue(problems.get(1).message().contains("g:gone 2.0 is not needed: it is not a dependency"), problems.toString());
    }

    @Test
    void anExceptionForAnotherVersionDoesNotCount(@TempDir Path dir) throws Exception {
        repo(dir, 30, 1);
        Project p = project(dir, "\"g:lib\": \"1.1\"", ", \"releaseAgeExceptions\": { \"g:lib\": \"1.0\" }");
        List<Problem> problems = ProjectChecks.releaseAge(p, install(p), new MavenRepo(p.root.repositories));
        assertEquals(List.of(Problem.Severity.ERROR, Problem.Severity.WARNING), problems.stream().map(Problem::severity).toList(), problems.toString());
        assertTrue(problems.get(1).message().contains("the locked version is 1.1"), problems.toString());
    }

    @Test
    void readsAges() {
        assertEquals(Duration.ofDays(7), Manifest.parseAge("7 days"));
        assertEquals(Duration.ofDays(3), Manifest.parseAge("3d"));
        assertEquals(Duration.ofHours(12), Manifest.parseAge("12 Hours"));
        assertEquals(Duration.ofDays(14), Manifest.parseAge("2w"));
        assertEquals(Duration.ofMinutes(30), Manifest.parseAge("30 min"));
        assertNull(Manifest.parseAge("a week"));
        assertNull(Manifest.parseAge("7"));
        assertEquals("7 days", ReleaseAge.describe(Duration.ofDays(7)));
        assertEquals("2 weeks", ReleaseAge.describe(Duration.ofDays(14)));
        assertEquals("1 hour", ReleaseAge.describe(Duration.ofMinutes(61)));
    }

    @Test
    void onlyTheWorkspaceRootSetsIt(@TempDir Path dir) {
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Path p = dir.resolve("p");
        Fs.write(p.resolve("project.jsonc"), "{ \"name\": \"p\", \"workspace\": [\"a\"] }");
        Fs.write(p.resolve("a/project.jsonc"), "{ \"name\": \"a\", \"minimumReleaseAge\": \"7 days\" }");
        JpmException e = assertThrows(JpmException.class, () -> Project.load(p));
        assertTrue(e.getMessage().contains("set minimumReleaseAge in the workspace root's project.jsonc"), e.getMessage());
        Fs.write(p.resolve("project.jsonc"), "{ \"name\": \"p\", \"minimumReleaseAge\": \"soon\" }");
        e = assertThrows(JpmException.class, () -> Project.load(p));
        assertTrue(e.getMessage().contains("minimumReleaseAge must be a number and a unit"), e.getMessage());
    }
}
