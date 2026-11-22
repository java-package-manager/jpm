package dev.jpm.api.client;

import dev.jpm.api.json.Jsonc;
import dev.jpm.api.model.Classpaths;
import dev.jpm.api.model.Extension;
import dev.jpm.api.model.Project;
import dev.jpm.api.protocol.Frames;
import dev.jpm.api.protocol.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * A connection to the global jpm: it starts {@code jpm api} and talks the jpm protocol (docs/protocol.md) with
 * it. Calls block until jpm answers; they may be made from several threads.
 *
 * <pre>{@code
 * try (JpmClient jpm = JpmClient.start()) {
 *     Project p = jpm.load(Path.of("."), LoadOptions.withSources());
 * }
 * }</pre>
 */
public final class JpmClient implements AutoCloseable {
    private final Process process;
    private final OutputStream out;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final List<BiConsumer<String, String>> logListeners = new CopyOnWriteArrayList<>();
    private volatile String jpmVersion, protocolVersion;
    private volatile List<String> methods = List.of();

    private JpmClient(Process process) {
        this.process = process;
        this.out = process.getOutputStream();
        Thread reader = new Thread(this::read, "jpm-client-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Starts the global jpm and initializes the connection.
     *
     * @return the connected client
     * @throws JpmNotInstalled  when no jpm is found
     * @throws ProtocolMismatch when the installed jpm speaks another major version of the protocol
     */
    public static JpmClient start() {
        return start(List.of(find().toString(), "api"), null, Map.of(), "client", "unknown");
    }

    /**
     * Starts jpm with a given command line, for example a jpm build to test against.
     *
     * @param command       the command, such as {@code [/path/to/jpm, api]}
     * @param workingDir    the folder to start it in, or null for the current one
     * @param env           environment variables to set for it, besides this process's
     * @param clientName    this client's name, which jpm names in error messages
     * @param clientVersion this client's version
     * @return the connected client
     * @throws ProtocolMismatch when that jpm speaks another major version of the protocol
     */
    public static JpmClient start(List<String> command, Path workingDir, Map<String, String> env, String clientName, String clientVersion) {
        ProcessBuilder pb = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.environment().putAll(env);
        if (workingDir != null) pb.directory(workingDir.toFile());
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new JpmNotInstalled("Could not start " + command.get(0) + ": " + e.getMessage());
        }
        JpmClient c = new JpmClient(p);
        try {
            Map<String, Object> client = new LinkedHashMap<>();
            client.put("name", clientName);
            client.put("version", clientVersion);
            Map<String, Object> init = c.call("initialize", Map.of("protocolVersion", Protocol.VERSION, "client", client));
            c.jpmVersion = String.valueOf(init.get("jpmVersion"));
            c.protocolVersion = String.valueOf(init.get("protocolVersion"));
            List<String> ms = new ArrayList<>();
            if (init.get("methods") instanceof List<?> l) for (Object o : l) ms.add(String.valueOf(o));
            c.methods = List.copyOf(ms);
            return c;
        } catch (RuntimeException e) {
            p.destroyForcibly();
            throw e;
        }
    }

    /**
     * Finds the global jpm: {@code JPM_EXE}, then {@code $JPM_HOME/bin/jpm}, {@code ~/.jpm/bin/jpm}, then
     * {@code jpm} on PATH.
     *
     * @return the jpm executable
     * @throws JpmNotInstalled when there is none
     */
    public static Path find() {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        String exe = win ? "jpm.exe" : "jpm";
        List<Path> candidates = new ArrayList<>();
        String env = System.getenv("JPM_EXE");
        if (env != null && !env.isBlank()) candidates.add(Path.of(env));
        String home = System.getenv("JPM_HOME");
        if (home != null && !home.isBlank()) candidates.add(Path.of(home, "bin", exe));
        candidates.add(Path.of(System.getProperty("user.home"), ".jpm", "bin", exe));
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(java.io.File.pathSeparator)) if (!d.isBlank()) candidates.add(Path.of(d, exe));
        for (Path c : candidates) if (Files.isRegularFile(c) && Files.isExecutable(c)) return c;
        throw new JpmNotInstalled("jpm is not installed: looked for " + exe + " in JPM_EXE, $JPM_HOME/bin, ~/.jpm/bin and on PATH");
    }

    /**
     * The version of the jpm this client talks to.
     *
     * @return such as {@code 0.12.0}
     */
    public String jpmVersion() {
        return jpmVersion;
    }

    /**
     * The protocol version that jpm speaks; its major is this client's.
     *
     * @return such as {@code 1.0}
     */
    public String protocolVersion() {
        return protocolVersion;
    }

    /**
     * Whether that jpm serves a method; a newer minor version may add some.
     *
     * @param method such as {@code check/run}
     * @return true when it does
     */
    public boolean supports(String method) {
        return methods.contains(method);
    }

    /**
     * Listens to jpm's log messages (level and message) while it works on requests.
     *
     * @param listener called on the reader thread
     */
    public void onLog(BiConsumer<String, String> listener) {
        logListeners.add(listener);
    }

    /**
     * {@code project/load}: the project at or above {@code dir}, with its dependencies resolved.
     *
     * @param dir     the project's folder, or a module's
     * @param options frozen and sources
     * @return the project
     * @throws JpmError when jpm cannot load it; {@link JpmError#problem()} says where and why
     */
    public Project load(Path dir, LoadOptions options) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("dir", dir.toAbsolutePath().toString());
        params.put("frozen", options.frozen());
        params.put("sources", options.sources());
        return Results.project(call("project/load", params));
    }

    /**
     * {@code project/classpaths}: every classpath of the named modules, or of all modules.
     *
     * @param dir     the project's folder
     * @param modules module names; empty for all
     * @return classpaths by module name
     * @throws JpmError when jpm cannot load the project
     */
    @SuppressWarnings("unchecked")
    public Map<String, Classpaths> classpaths(Path dir, List<String> modules) {
        Map<String, Object> r = call("project/classpaths", Map.of("dir", dir.toAbsolutePath().toString(), "modules", modules));
        Map<String, Classpaths> out = new LinkedHashMap<>();
        if (r.get("modules") instanceof Map<?, ?> m) m.forEach((k, v) -> out.put(k.toString(), Results.classpaths((Map<String, Object>) v)));
        return out;
    }

    /**
     * {@code extensions/list}: the global extensions and, with a {@code dir}, the ones that project declares, which
     * take the place of global ones of the same name. jpm reads their manifests; it runs none of them.
     *
     * @param dir the project's folder, or null for the global extensions only
     * @return the extensions, the project's first
     * @throws JpmError when jpm cannot load the project
     */
    @SuppressWarnings("unchecked")
    public List<Extension> extensions(Path dir) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (dir != null) params.put("dir", dir.toAbsolutePath().toString());
        List<Extension> out = new ArrayList<>();
        if (call("extensions/list", params).get("extensions") instanceof List<?> l) {
            for (Object o : l) if (o instanceof Map<?, ?> m) out.add(Results.extension((Map<String, Object>) m));
        }
        return out;
    }

    /**
     * {@code schema/manifest}: the JSON Schema of {@code project.jsonc} that this jpm reads.
     *
     * @return the schema, as {@link Jsonc#parse(String)} gives JSON
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> manifestSchema() {
        return (Map<String, Object>) call("schema/manifest", Map.of()).get("schema");
    }

    /**
     * Sends a request and waits for its result.
     *
     * @param method the method
     * @param params its params
     * @return the result object (an empty map for a null result)
     * @throws JpmError when jpm answers with an error
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(String method, Map<String, Object> params) {
        int id = ids.incrementAndGet();
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        pending.put(id, f);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        msg.put("params", params);
        try {
            Frames.write(out, Jsonc.write(msg));
            Map<String, Object> response = f.get();
            if (response.get("error") instanceof Map<?, ?> e) {
                int code = e.get("code") instanceof Number n ? n.intValue() : -32603;
                String message = String.valueOf(e.get("message"));
                if (code == Protocol.PROTOCOL_MISMATCH) throw new ProtocolMismatch(message);
                throw new JpmError(code, message, Results.problem(e.get("data")));
            }
            return response.get("result") instanceof Map<?, ?> r ? (Map<String, Object>) r : Map.of();
        } catch (IOException e) {
            throw new JpmError(-32603, "jpm api could not be reached: " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JpmError(Protocol.CANCELLED, "interrupted", null);
        } catch (ExecutionException e) {
            throw new JpmError(-32603, "jpm api stopped: " + e.getCause().getMessage(), null);
        } finally {
            pending.remove(id);
        }
    }

    @SuppressWarnings("unchecked")
    private void read() {
        InputStream in = process.getInputStream();
        try {
            String text;
            while ((text = Frames.read(in)) != null) {
                Map<String, Object> msg = (Map<String, Object>) Jsonc.parse(text);
                if (msg.get("id") instanceof Number n) {
                    CompletableFuture<Map<String, Object>> f = pending.get(n.intValue());
                    if (f != null) f.complete(msg);
                } else if ("log".equals(msg.get("method")) && msg.get("params") instanceof Map<?, ?> p) {
                    for (BiConsumer<String, String> l : logListeners) l.accept(String.valueOf(p.get("level")), String.valueOf(p.get("message")));
                }
            }
        } catch (IOException | RuntimeException e) {
            pending.values().forEach(f -> f.completeExceptionally(e));
            return;
        }
        pending.values().forEach(f -> f.completeExceptionally(new IOException("jpm api closed the connection")));
    }

    /** Shuts jpm down ({@code shutdown}, then {@code exit}) and waits for it to end. */
    @Override
    public void close() {
        try {
            call("shutdown", Map.of());
            Map<String, Object> exit = new LinkedHashMap<>();
            exit.put("jsonrpc", "2.0");
            exit.put("method", "exit");
            Frames.write(out, Jsonc.write(exit));
            out.close();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (IOException | RuntimeException e) {
            process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
