package dev.jpm.e2e;

import dev.jpm.api.client.JpmClient;
import dev.jpm.api.client.JpmError;
import dev.jpm.api.client.LoadOptions;
import dev.jpm.api.model.CheckReport;
import dev.jpm.api.model.Classpaths;
import dev.jpm.api.model.Extension;
import dev.jpm.api.model.Module;
import dev.jpm.api.model.Project;
import dev.jpm.api.protocol.Protocol;
import dev.jpm.testing.TestRepo;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client, dev.jpm:jpm-api, against a real jpm api.
 */
class ClientTest {
    private static JpmClient connect(Session s) {
        List<String> cmd = new ArrayList<>(Session.binary());
        cmd.add("api");
        return JpmClient.start(cmd, s.project, s.env(), "ClientTest", "1");
    }

    private static Session workspace(Path tmp) {
        Session s = new Session(tmp).fixture("workspace-demo");
        new TestRepo(s.repo.root()).artifact("org.example:money:1.2.0").javaClass("org.example.money.Money",
                "package org.example.money; public final class Money { public static String format(long c) { return \"\" + c; } }").publish();
        return s;
    }

    @Test
    void loadsAProject(@TempDir Path tmp) {
        Session s = workspace(tmp);
        List<String> logs = new ArrayList<>();
        try (JpmClient jpm = connect(s)) {
            jpm.onLog((level, message) -> logs.add(level + " " + message));
            assertEquals(Protocol.VERSION, jpm.protocolVersion());
            assertTrue(jpm.supports("project/load"));
            Project p = jpm.load(s.project, LoadOptions.defaults());
            assertEquals("shop", p.name());
            assertTrue(p.lockfileChanged());
            Module app = p.module("app");
            assertEquals(Map.of("main", "shop.app.Main"), app.main());
            assertEquals(21, app.jdk().release());
            assertEquals("Welcome", app.defines().get(0).value());
            assertEquals("shop.app.Config", app.defines().get(0).className());
            Module core = p.module("core");
            assertEquals("1.2.0", core.dependencies().get(0).resolved());
            assertEquals(List.of(s.project.resolve("core/src")), core.folders().sources());
            assertEquals("hello", p.scripts().get(0).name());
            Map<String, Classpaths> cps = jpm.classpaths(s.project, List.of("app"));
            assertEquals(List.of("workspace:core", "org.example:money"), cps.get("app").runtime().stream().map(x -> x.id()).toList());
            assertEquals("jpm project.jsonc", jpm.manifestSchema().get("title"));
        }
    }

    private static void git(Path dir, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=e2e", "-c", "user.email=e2e@example.com",
                "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false"));
        cmd.addAll(List.of(args));
        dev.jpm.util.Exec.Result r = dev.jpm.util.Exec.capture(dir, cmd);
        if (r.exit() != 0) throw new AssertionError(String.join(" ", cmd) + ": " + r.out() + r.err());
    }

    private static void extension(Path dir, String name, String version, String descriptor) {
        Fs.write(dir.resolve("src/x/Main.java"), "package x; public class Main { public static void main(String[] a) {} }");
        Fs.write(dir.resolve("project.jsonc"), "{ \"name\": \"" + name + "\", \"version\": \"" + version + "\", \"main\": \"x.Main\","
                + " \"extension\": " + descriptor + " }");
        if (!java.nio.file.Files.isDirectory(dir.resolve(".git"))) git(dir, "init", "-q", "-b", "main");
        git(dir, "add", "-A");
        git(dir, "commit", "-qm", version);
        git(dir, "tag", version);
    }

    @Test
    void listsExtensionsWithoutRunningThem(@TempDir Path tmp) {
        Session s = new Session(tmp);
        s.env("JPM_GIT_BASE_GITHUB", tmp.resolve("git").toUri().toString());
        Path lint = tmp.resolve("git/me/jpm-lint");
        extension(lint, "jpm-lint", "1.0.0", "{}");
        extension(lint, "jpm-lint", "2.0.0", "{ \"description\": \"Lints the sources\", \"commands\": { \"lint\": { \"description\": \"Lint\","
                + " \"options\": { \"--fix\": { \"description\": \"Fix it\" }, \"--module\": { \"argument\": \"name\" } } },"
                + " \"lint report\": {} }, \"checks\": { \"style\": { \"description\": \"The style\" } } }");
        extension(tmp.resolve("git/me/jpm-hello"), "jpm-hello", "1.0.0", "{}");
        s.jpm("add", "-g", "github:me/jpm-lint@1.0.0");
        s.jpm("add", "-g", "github:me/jpm-hello@1.0.0");
        Fs.write(s.project.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"extensions\": { \"github:me/jpm-lint\": \"^2.0\" } }");
        try (JpmClient jpm = connect(s)) {
            assertTrue(jpm.supports("extensions/list"));
            List<Extension> all = jpm.extensions(s.project);
            assertEquals(List.of("lint project 2.0.0", "hello global 1.0.0"), all.stream().map(e -> e.name() + " " + e.source() + " " + e.version()).toList(),
                    "the declared lint takes the place of the global one");
            Extension l = all.get(0);
            assertEquals("github:me/jpm-lint", l.key());
            assertEquals("Lints the sources", l.description());
            assertEquals(List.of("lint", "lint report"), l.commands().stream().map(Extension.Command::name).toList());
            assertEquals(List.of(new Extension.Option("--fix", null, "Fix it"), new Extension.Option("--module", "name", null)), l.commands().get(0).options());
            assertEquals(List.of(new Extension.Check("style", "The style")), l.checks());
            Extension hello = all.get(1);
            assertEquals(List.of(new Extension.Command("hello", null, List.of())), hello.commands(), "no commands listed: one named after it");
            assertEquals(List.of("hello", "lint"), jpm.extensions(null).stream().map(Extension::name).sorted().toList());
        }
    }

    @Test
    void runsTheChecks(@TempDir Path tmp) {
        Session s = new Session(tmp);
        Fs.write(s.project.resolve("project.jsonc"), "{\n  \"name\": \"app\",\n  \"version\": \"1.0.0\",\n  \"sources\": [\"src\", \"gen\"]\n}\n");
        Fs.write(s.project.resolve("src/app/A.java"), "package app; public class A {}");
        try (JpmClient jpm = connect(s)) {
            assertTrue(jpm.supports("check/run"));
            CheckReport r = jpm.check(s.project, List.of(), false);
            assertEquals(List.of("manifest", "dependencies", "paths", "license", "compile", "unused-dependencies", "duplicate-classes"), r.ran());
            assertTrue(r.failed());
            dev.jpm.api.model.Problem p = r.problems().get(0);
            assertEquals("paths", p.check());
            assertEquals("error", p.severity());
            assertEquals("the source folder gen does not exist", p.message());
            assertEquals(s.project.resolve("project.jsonc"), p.file());
            assertEquals(4, p.line());
            CheckReport only = jpm.check(s.project, List.of("license"), false);
            assertEquals(List.of("license"), only.ran());
            assertFalse(only.failed());
        }
    }

    @Test
    void errorsCarryTheProblem(@TempDir Path tmp) {
        Session s = workspace(tmp);
        Fs.write(s.project.resolve("project.jsonc"), "{ \"name\": \"shop\", \"versoin\": \"1\" }");
        try (JpmClient jpm = connect(s)) {
            JpmError e = assertThrows(JpmError.class, () -> jpm.load(s.project, LoadOptions.defaults()));
            assertEquals(Protocol.PROJECT_ERROR, e.code());
            assertEquals("unknown field \"versoin\"", e.getMessage());
            assertEquals(s.project.resolve("project.jsonc"), e.problem().file());
            assertEquals(1, e.problem().line());
            assertEquals("did you mean \"version\"?", e.problem().hint());
            assertFalse(jpm.supports("no/such"));
        }
    }
}
