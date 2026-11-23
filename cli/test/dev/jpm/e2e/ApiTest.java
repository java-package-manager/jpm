package dev.jpm.e2e;

import dev.jpm.api.json.Jsonc;
import dev.jpm.api.protocol.Frames;
import dev.jpm.api.protocol.Protocol;
import dev.jpm.testing.TestRepo;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code jpm api} spoken to over its real stdin and stdout, as docs/protocol.md specifies it.
 */
@SuppressWarnings("unchecked")
class ApiTest {
    private Process process;
    private OutputStream in;
    private InputStream out;
    private int nextId;
    private final List<Map<String, Object>> notifications = new ArrayList<>();

    private void start(Session s) throws IOException {
        List<String> cmd = new ArrayList<>(Session.binary());
        cmd.add("api");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(s.project.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.environment().keySet().removeIf(k -> k.startsWith("JPM_"));
        pb.environment().putAll(s.env());
        process = pb.start();
        in = process.getOutputStream();
        out = process.getInputStream();
    }

    @AfterEach
    void stop() {
        if (process != null) process.destroyForcibly();
    }

    private Map<String, Object> call(String method, Map<String, Object> params) throws IOException {
        int id = ++nextId;
        send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        while (true) {
            String text = Frames.read(out);
            assertNotNull(text, "jpm api closed its stdout");
            Map<String, Object> msg = (Map<String, Object>) Jsonc.parse(text);
            if (msg.containsKey("id")) {
                assertEquals(id, ((Number) msg.get("id")).intValue());
                return msg;
            }
            notifications.add(msg);
        }
    }

    private void send(Map<String, Object> msg) throws IOException {
        Frames.write(in, Jsonc.write(msg));
    }

    private Map<String, Object> initialize() throws IOException {
        return call("initialize", Map.of("protocolVersion", Protocol.VERSION, "client", Map.of("name", "ApiTest", "version", "1")));
    }

    private static Map<String, Object> result(Map<String, Object> response) {
        assertFalse(response.containsKey("error"), () -> "error: " + response.get("error"));
        return (Map<String, Object>) response.get("result");
    }

    private static Map<String, Object> error(Map<String, Object> response) {
        assertTrue(response.containsKey("error"), () -> "no error: " + response);
        return (Map<String, Object>) response.get("error");
    }

    private Session workspace(Path tmp) {
        Session s = new Session(tmp).fixture("workspace-demo");
        new TestRepo(s.repo.root()).artifact("org.example:money:1.2.0").javaClass("org.example.money.Money",
                "package org.example.money; public final class Money { public static String format(long c) { return \"\" + c; } }").publish();
        return s;
    }

    @Test
    void handshake(@TempDir Path tmp) throws Exception {
        start(workspace(tmp));
        assertEquals(Protocol.NOT_INITIALIZED, ((Number) error(call("project/load", Map.of("dir", "."))).get("code")).intValue());
        Map<String, Object> mismatch = error(call("initialize", Map.of("protocolVersion", "2.0", "client", Map.of("name", "ide", "version", "9"))));
        assertEquals(Protocol.PROTOCOL_MISMATCH, ((Number) mismatch.get("code")).intValue());
        assertTrue(mismatch.get("message").toString().contains("speaks protocol " + Protocol.VERSION + ", ide 9 speaks protocol 2.0"),
                mismatch.toString());
        Map<String, Object> ok = result(call("initialize", Map.of("protocolVersion", "1.7", "client", Map.of("name", "newer", "version", "1"))));
        assertEquals(Protocol.VERSION, ok.get("protocolVersion"));
        assertTrue(((List<?>) ok.get("methods")).contains("project/load"));
        assertEquals(-32601, ((Number) error(call("no/such", Map.of())).get("code")).intValue());
        assertNull(call("shutdown", Map.of()).get("result"));
        send(Map.of("jsonrpc", "2.0", "method", "exit"));
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
    }

    @Test
    void exitWithoutShutdownFails(@TempDir Path tmp) throws Exception {
        start(workspace(tmp));
        initialize();
        send(Map.of("jsonrpc", "2.0", "method", "exit"));
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(1, process.exitValue());
    }

    @Test
    void loadsTheProjectModel(@TempDir Path tmp) throws Exception {
        Session s = workspace(tmp);
        start(s);
        initialize();
        Map<String, Object> first = result(call("project/load", Map.of("dir", s.project.toString())));
        assertEquals(true, first.get("lockfileChanged"), "the first load writes project.lock");
        Map<String, Object> p = result(call("project/load", Map.of("dir", s.project.resolve("core").toString())));
        assertFalse((Boolean) p.get("lockfileChanged"));
        assertEquals("shop", p.get("name"));
        assertTrue((Boolean) p.get("workspace"));
        assertEquals(List.of(Map.of("name", "hello", "run", "echo hello from a script", "kind", "command", "dependencies", Map.of())),
                p.get("scripts"));
        List<Map<String, Object>> modules = (List<Map<String, Object>>) p.get("modules");
        assertEquals(List.of("core", "app"), modules.stream().map(m -> m.get("name")).toList());
        Map<String, Object> core = modules.get(0), app = modules.get(1);
        assertEquals(21, ((Number) ((Map<String, Object>) core.get("jdk")).get("release")).intValue());
        assertEquals(List.of(s.project.resolve("core/src").toString()), ((Map<String, Object>) core.get("folders")).get("sources"));
        Map<String, Object> money = ((List<Map<String, Object>>) core.get("dependencies")).get(0);
        assertEquals("org.example:money", money.get("key"));
        assertEquals("maven", money.get("kind"));
        assertEquals("runtime", money.get("scope"));
        assertEquals("1.2.0", money.get("resolved"));
        List<Map<String, Object>> packages = (List<Map<String, Object>>) app.get("packages");
        Map<String, String> scopes = new LinkedHashMap<>();
        for (Map<String, Object> pk : packages) scopes.put(pk.get("id").toString(), pk.get("scope").toString());
        assertEquals("compile", scopes.get("workspace:core"));
        assertEquals("compile", scopes.get("org.example:money"));
        assertEquals(List.of(Map.of("name", "GREETING", "type", "string", "value", "Welcome", "class", "shop.app.Config")), app.get("defines"));
        assertTrue(((List<?>) ((Map<String, Object>) app.get("folders")).get("generatedSources")).size() == 1);
        assertFalse(java.nio.file.Files.exists(s.project.resolve("core/dist")), "project/load builds nothing");
    }

    @Test
    void classpathsAndSchema(@TempDir Path tmp) throws Exception {
        Session s = workspace(tmp);
        start(s);
        initialize();
        Map<String, Object> cps = (Map<String, Object>) result(call("project/classpaths",
                Map.of("dir", s.project.toString(), "modules", List.of("app")))).get("modules");
        assertEquals(List.of("app"), new ArrayList<>(cps.keySet()));
        List<Map<String, Object>> runtime = (List<Map<String, Object>>) ((Map<String, Object>) cps.get("app")).get("runtime");
        assertEquals(List.of("workspace:core", "org.example:money"), runtime.stream().map(m -> m.get("id")).toList());
        Map<String, Object> schema = result(call("schema/manifest", Map.of()));
        assertEquals("jpm project.jsonc", ((Map<String, Object>) schema.get("schema")).get("title"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void versionsOfDependencies(@TempDir Path tmp) throws Exception {
        Session s = workspace(tmp);
        for (String v : List.of("1.0.0", "2.0.0", "2.1.0-rc1")) s.repo.artifact("org.example:money:" + v).publish();
        start(s);
        Map<String, Object> init = result(initialize());
        assertTrue(((List<?>) init.get("methods")).contains("dependencies/versions"));
        Map<String, Object> v = (Map<String, Object>) result(call("dependencies/versions",
                Map.of("dir", s.project.toString(), "keys", List.of("org.example:money")))).get("versions");
        Map<String, Object> money = (Map<String, Object>) v.get("org.example:money");
        assertEquals(List.of("1.0.0", "1.2.0", "2.0.0", "2.1.0-rc1"), money.get("versions"));
        assertEquals("2.0.0", money.get("latest"));
        Map<String, Object> bad = error(call("dependencies/versions", Map.of("keys", List.of("github:me/x"))));
        assertEquals(-32602, ((Number) bad.get("code")).intValue());
    }

    @Test
    void errorsAreProblems(@TempDir Path tmp) throws Exception {
        Session s = workspace(tmp);
        Fs.write(s.project.resolve("core/project.jsonc"), "{\n  \"name\": \"core\",\n  \"runtim\": {}\n}\n");
        start(s);
        initialize();
        Map<String, Object> e = error(call("project/load", Map.of("dir", s.project.toString())));
        assertEquals(Protocol.PROJECT_ERROR, ((Number) e.get("code")).intValue());
        Map<String, Object> problem = (Map<String, Object>) e.get("data");
        assertEquals("error", problem.get("severity"));
        assertEquals("unknown field \"runtim\"", problem.get("message"));
        Map<String, Object> location = (Map<String, Object>) problem.get("location");
        assertEquals(s.project.resolve("core/project.jsonc").toString(), location.get("file"));
        assertEquals(3, ((Number) location.get("line")).intValue());
        assertEquals("did you mean \"runtime\"?", problem.get("hint"));
        assertEquals(-32602, ((Number) error(call("project/load", Map.of())).get("code")).intValue());
    }

    @Test
    void cancelledRequestsAreAnsweredAsCancelled(@TempDir Path tmp) throws Exception {
        start(workspace(tmp));
        initialize();
        send(Map.of("jsonrpc", "2.0", "method", "$/cancelRequest", "params", Map.of("id", nextId + 1)));
        assertEquals(Protocol.CANCELLED, ((Number) error(call("schema/manifest", Map.of())).get("code")).intValue());
        assertEquals("jpm project.jsonc", ((Map<String, Object>) result(call("schema/manifest", Map.of())).get("schema")).get("title"));
    }

    @Test
    void logsAreNotifications(@TempDir Path tmp) throws Exception {
        Session s = workspace(tmp);
        start(s);
        call("initialize", Map.of("protocolVersion", Protocol.VERSION, "client", Map.of("name", "t", "version", "1"), "logLevel", "debug"));
        result(call("project/load", Map.of("dir", s.project.toString())));
        assertTrue(notifications.stream().anyMatch(n -> "log".equals(n.get("method"))
                && "debug".equals(((Map<String, Object>) n.get("params")).get("level"))), notifications.toString());
    }
}
