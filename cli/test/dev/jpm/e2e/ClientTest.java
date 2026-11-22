package dev.jpm.e2e;

import dev.jpm.api.client.JpmClient;
import dev.jpm.api.client.JpmError;
import dev.jpm.api.client.LoadOptions;
import dev.jpm.api.model.Classpaths;
import dev.jpm.api.model.Module;
import dev.jpm.api.model.Project;
import dev.jpm.api.protocol.Protocol;
import dev.jpm.testing.TestRepo;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The client in the api module (github:java-package-manager/jpm/api), against a real jpm api. */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the e2e sessions are not run on Windows yet")
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
