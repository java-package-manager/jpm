package dev.jpm.resolve;

import dev.jpm.DepKey;
import dev.jpm.json.Jsonc;
import dev.jpm.util.Fs;
import dev.jpm.util.Hash;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dependencies an extension provides: {@code ext:<extension>/<name>}, such as {@code ext:paperweight/server}. jpm runs
 * {@code jpm-<extension> resolve <name> <version>} (the extension installed with {@code jpm add -g}, or on PATH), which
 * prepares whatever it provides and prints one JSON object on stdout (its log goes to stderr):
 *
 * <pre>{@code
 * {
 *   "version": "26.3.build.257-stable",            // the exact version; asked for again with it later
 *   "jars": ["/home/me/.jpm/paperweight/.../server.jar"],
 *   "sources": "/home/me/.jpm/paperweight/.../server-sources.jar",   // optional, for IDEs
 *   "dependencies": { "io.papermc.paper:paper-api": "26.3.build.257-stable", "group:artifact:classifier": "1.0" }
 * }
 * }</pre>
 *
 * The jars go on the classpaths of the scope the dependency is declared in; the dependencies are Maven packages,
 * resolved and locked with the rest of the graph. The lockfile records the version and a SHA-256 of the jars, so
 * an extension that produced different bytes for the same version fails the install.
 */
public final class ExtensionDeps {
    private ExtensionDeps() {}

    public record Result(String version, List<Path> jars, Path sources, Map<String, String> dependencies) {
        /** One checksum for all the jars. */
        public String sha256() {
            Hash h = new Hash();
            for (Path j : jars) h.add(Hash.sha256(j));
            return jars.size() == 1 ? Hash.sha256(jars.get(0)) : h.hex();
        }
    }

    private static final Map<String, Result> MEMO = new ConcurrentHashMap<>();

    /** {@code ~/.jpm/bin/jpm-<name>} (as {@code jpm add -g} installs it), else {@code jpm-<name>} on PATH; null if neither. */
    public static Path executable(String name) {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        for (String ext : win ? new String[]{".cmd", ".exe", ".bat"} : new String[]{""}) {
            Path p = Fs.home().resolve("bin").resolve("jpm-" + name + ext);
            if (Files.isRegularFile(p)) return p;
        }
        return dev.jpm.util.Exec.which("jpm-" + name);
    }

    /** What {@code key} is at {@code spec}: runs the extension once per key and spec in this process. */
    public static Result resolve(DepKey key, String spec) {
        String memoKey = key + "@" + spec;
        Result cached = MEMO.get(memoKey);
        if (cached != null) return cached;
        Result r = run(key, spec);
        MEMO.put(memoKey, r);
        MEMO.putIfAbsent(key + "@" + r.version(), r);
        return r;
    }

    private static Result run(DepKey key, String spec) {
        String ext = key.group(), name = key.artifact();
        Path exe = executable(ext);
        if (exe == null) {
            throw new JpmException(key + " is provided by the extension jpm-" + ext + ", which is not installed. Install it with"
                    + " `jpm add -g <its dependency>` (it runs as jpm-" + ext + ")");
        }
        List<String> cmd = new ArrayList<>(List.of(exe.toString(), "resolve", name, spec == null ? "" : spec.strip()));
        Log.debug("extension dependency: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.environment().put("JPM_EXE", dev.jpm.util.Self.exe());
        pb.environment().put("JPM_VERSION_OF_JPM", dev.jpm.BuildConfig.VERSION);
        pb.environment().putAll(Log.childEnv());
        pb.environment().put("JPM_HOME", Fs.home().toString());
        if (Fs.offline()) pb.environment().put("JPM_OFFLINE", "1");
        String out;
        int code;
        try {
            Process p = pb.start();
            p.getOutputStream().close();
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            code = p.waitFor();
        } catch (IOException e) {
            throw new JpmException("Could not run " + exe + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JpmException("Interrupted");
        }
        if (code != 0) throw new JpmException("jpm-" + ext + " could not provide " + key + " " + spec + " (exit " + code + ")");
        return parse(key, out);
    }

    @SuppressWarnings("unchecked")
    static Result parse(DepKey key, String out) {
        Object v;
        try {
            v = Jsonc.parse(out.strip());
        } catch (RuntimeException e) {
            throw new JpmException("jpm-" + key.group() + " resolve printed no JSON object for " + key + ": " + e.getMessage());
        }
        if (!(v instanceof Map<?, ?> m) || !(m.get("version") instanceof String version) || !(m.get("jars") instanceof List<?> jarList)) {
            throw new JpmException("jpm-" + key.group() + " resolve must print {\"version\": ..., \"jars\": [...]} for " + key);
        }
        List<Path> jars = new ArrayList<>();
        for (Object j : jarList) {
            Path p = Path.of(String.valueOf(j));
            if (!Files.isRegularFile(p)) throw new JpmException("jpm-" + key.group() + " gave " + p + " for " + key + ", which does not exist");
            jars.add(p);
        }
        Path sources = m.get("sources") instanceof String s && Files.isRegularFile(Path.of(s)) ? Path.of(s) : null;
        Map<String, String> deps = new LinkedHashMap<>();
        if (m.get("dependencies") instanceof Map<?, ?> dm) {
            for (Map.Entry<?, ?> e : dm.entrySet()) {
                DepKey dk = DepKey.parse(String.valueOf(e.getKey()));
                if (!dk.isMaven()) throw new JpmException("jpm-" + key.group() + ": dependencies of " + key + " must be Maven packages, not " + dk);
                deps.put(dk.toString(), String.valueOf(e.getValue()));
            }
        }
        return new Result(version, List.copyOf(jars), sources, deps);
    }
}
