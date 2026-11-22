package dev.jpm.cli.impl;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.api.json.Jsonc;
import dev.jpm.api.protocol.Frames;
import dev.jpm.api.protocol.Protocol;
import dev.jpm.cli.Main;
import dev.jpm.log.Log;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Install;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

final class ApiServer {
    static final List<String> METHODS = List.of("initialize", "shutdown", "exit", "project/load", "project/classpaths",
            "schema/manifest", "extensions/list", "check/run", "$/cancelRequest");

    private static final Object EOF = new Object();

    private final InputStream in;
    private final OutputStream out;
    private final Path cwd;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final Set<Object> cancelled = ConcurrentHashMap.newKeySet();
    private boolean initialized, shutdown;

    ApiServer(InputStream in, OutputStream out, Path cwd) {
        this.in = in;
        this.out = out;
        this.cwd = cwd;
    }

    int run() {
        Thread reader = new Thread(this::read, "jpm-api-reader");
        reader.setDaemon(true);
        reader.start();
        Log.configure(Log.Level.INFO, Log.Color.NEVER);
        Log.sink((level, message) -> notify("log", Map.of("level", level.name().toLowerCase(Locale.ROOT), "message", message)));
        try {
            while (true) {
                Object next = queue.take();
                if (next == EOF) return 1;
                if (next instanceof Map<?, ?> msg && handle(msg)) return shutdown ? 0 : 1;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        } finally {
            Log.sink(null);
        }
    }

    private void read() {
        try {
            String text;
            while ((text = Frames.read(in)) != null) {
                Object msg;
                try {
                    msg = Jsonc.parse(text);
                } catch (Jsonc.ParseException e) {
                    send(error(null, -32700, "not JSON: " + e.getMessage(), null));
                    continue;
                }
                if (msg instanceof Map<?, ?> m && "$/cancelRequest".equals(m.get("method")) && m.get("params") instanceof Map<?, ?> p) {
                    cancelled.add(key(p.get("id")));
                    continue;
                }
                queue.add(msg);
            }
        } catch (IOException e) {
            Log.debug("jpm api: " + e.getMessage());
        }
        queue.add(EOF);
    }

    private static Object key(Object id) {
        return id instanceof Number n ? n.toString() : id;
    }

    private boolean handle(Map<?, ?> msg) {
        Object id = msg.get("id");
        Object method = msg.get("method");
        boolean request = msg.containsKey("id");
        if (!(method instanceof String name)) {
            if (request) send(error(id, -32600, "not a request: no \"method\"", null));
            return false;
        }
        if (name.equals("exit")) return true;
        if (!request) return false;
        if (cancelled.remove(key(id))) {
            send(error(id, Protocol.CANCELLED, "cancelled", null));
            return false;
        }
        Map<?, ?> params = msg.get("params") instanceof Map<?, ?> p ? p : Map.of();
        try {
            send(result(id, call(name, params)));
        } catch (ApiError e) {
            send(error(id, e.code, e.getMessage(), e.data));
        } catch (JpmException e) {
            send(error(id, Protocol.PROJECT_ERROR, e.getMessage(), problem(e)));
        } catch (RuntimeException e) {
            Log.debug(stackTrace(e));
            send(error(id, -32603, "unexpected failure in jpm: " + e, null));
        }
        return false;
    }

    private Object call(String method, Map<?, ?> params) {
        if (method.equals("initialize")) return initialize(params);
        if (!initialized) throw new ApiError(Protocol.NOT_INITIALIZED, "the first request must be initialize", null);
        if (shutdown) throw new ApiError(-32600, "jpm api is shutting down; only exit is accepted", null);
        return switch (method) {
            case "shutdown" -> {
                shutdown = true;
                yield null;
            }
            case "project/load" -> load(params);
            case "project/classpaths" -> classpaths(params);
            case "schema/manifest" -> Map.of("schema", Jsonc.parse(SchemaCommand.text()), "version", Main.VERSION);
            case "check/run" -> check(params);
            case "extensions/list" -> Map.of("extensions", ExtensionList.list(params.get("dir") instanceof String ? Project.find(dir(params)) : null));
            default -> throw new ApiError(-32601, "no method \"" + method + "\"; this jpm has " + String.join(", ", METHODS), null);
        };
    }

    private Object initialize(Map<?, ?> params) {
        Object v = params.get("protocolVersion");
        String theirs = v == null ? null : v.toString();
        try {
            Protocol.major(theirs);
        } catch (IllegalArgumentException e) {
            throw new ApiError(-32602, e.getMessage(), null);
        }
        if (!Protocol.compatible(theirs, Protocol.VERSION)) {
            String client = params.get("client") instanceof Map<?, ?> c ? c.get("name") + " " + c.get("version") : "this client";
            boolean older = Protocol.major(theirs) < Protocol.major(Protocol.VERSION);
            throw new ApiError(Protocol.PROTOCOL_MISMATCH, "jpm " + Main.VERSION + " speaks protocol " + Protocol.VERSION + ", " + client
                    + " speaks protocol " + theirs + ": " + (older ? "upgrade " + client.split(" ")[0] : "upgrade jpm with `jpm upgrade`"), null);
        }
        if (params.get("logLevel") instanceof String level) {
            try {
                Log.configure(Log.Level.valueOf(level.toUpperCase(Locale.ROOT)), Log.Color.NEVER);
            } catch (IllegalArgumentException e) {
                throw new ApiError(-32602, "logLevel must be error, warn, info, debug or trace, not \"" + level + "\"", null);
            }
        }
        initialized = true;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", Protocol.VERSION);
        out.put("jpmVersion", Main.VERSION);
        out.put("methods", METHODS);
        return out;
    }

    private Path dir(Map<?, ?> params) {
        if (!(params.get("dir") instanceof String d)) throw new ApiError(-32602, "\"dir\" is required: the project's folder", null);
        return cwd.resolve(d).toAbsolutePath().normalize();
    }

    private Object load(Map<?, ?> params) {
        Project p = Project.find(dir(params));
        MavenRepo repo = new MavenRepo(p.root.repositories);
        String before = lock(p);
        Graph g = Install.run(p, repo, new Install.Options(Boolean.TRUE.equals(params.get("frozen")), false, Set.of(), true));
        boolean changed = !java.util.Objects.equals(before, lock(p));
        return new ProjectModel(p, repo, g).project(Boolean.TRUE.equals(params.get("sources")), changed);
    }

    private Object classpaths(Map<?, ?> params) {
        Project p = Project.find(dir(params));
        MavenRepo repo = new MavenRepo(p.root.repositories);
        Graph g = Install.run(p, repo, new Install.Options(false, false, Set.of(), true));
        List<String> names = new ArrayList<>();
        if (params.get("modules") instanceof List<?> l) for (Object o : l) names.add(String.valueOf(o));
        return Map.of("modules", new ProjectModel(p, repo, g).classpaths(names));
    }

    private Object check(Map<?, ?> params) {
        java.util.Set<String> only = new java.util.LinkedHashSet<>();
        if (params.get("checks") instanceof List<?> l) for (Object x : l) only.add(String.valueOf(x));
        boolean tests = !Boolean.FALSE.equals(params.get("tests"));
        Checker c = new Checker(dir(params), new Checker.Options(tests, only, List.of(), false));
        List<dev.jpm.check.CheckResult> results = c.run();
        List<Object> problems = new ArrayList<>();
        List<Object> ran = new ArrayList<>();
        for (dev.jpm.check.CheckResult r : results) {
            if (r.skipped() == null) ran.add(r.id());
            for (dev.jpm.check.Problem p : r.problems()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("severity", p.severity() == dev.jpm.check.Problem.Severity.ERROR ? "error" : "warning");
                m.put("message", p.message());
                if (p.file() != null) {
                    Map<String, Object> loc = new LinkedHashMap<>();
                    loc.put("file", p.file().toAbsolutePath().normalize().toString());
                    if (p.line() != null) loc.put("line", p.line());
                    m.put("location", loc);
                }
                if (p.hint() != null) m.put("hint", p.hint());
                m.put("check", p.check());
                problems.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("problems", problems);
        out.put("ran", ran);
        return out;
    }

    private static String lock(Project p) {
        return Files.exists(p.lockFile()) ? Fs.read(p.lockFile()) : null;
    }

    static Map<String, Object> problem(JpmException e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("severity", "error");
        out.put("message", e.getMessage());
        if (e.location() != null) {
            Map<String, Object> loc = new LinkedHashMap<>();
            String l = e.location();
            int colon = l.lastIndexOf(':');
            if (colon > 0 && l.substring(colon + 1).matches("\\d+")) {
                loc.put("file", l.substring(0, colon));
                loc.put("line", Integer.parseInt(l.substring(colon + 1)));
            } else {
                loc.put("file", l);
            }
            out.put("location", loc);
        }
        if (e.hint() != null) out.put("hint", e.hint());
        return out;
    }

    private static Map<String, Object> result(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> error(Object id, int code, String message, Object data) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code);
        e.put("message", message);
        if (data != null) e.put("data", data);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", e);
        return m;
    }

    private void notify(String method, Object params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("method", method);
        m.put("params", params);
        send(m);
    }

    private void send(Map<String, Object> msg) {
        try {
            Frames.write(out, Jsonc.write(msg));
        } catch (IOException e) {
            throw new JpmException("jpm api could not write to its client: " + e.getMessage());
        }
    }

    private static String stackTrace(Throwable t) {
        java.io.StringWriter w = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }

    private static final class ApiError extends RuntimeException {
        final int code;
        final Object data;

        ApiError(int code, String message, Object data) {
            super(message);
            this.code = code;
            this.data = data;
        }
    }
}
