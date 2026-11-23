package dev.jpm.build;

import dev.jpm.Project;
import dev.jpm.build.impl.ReloadAgent;
import dev.jpm.toolchain.Jdk;
import dev.jpm.util.Fs;
import dev.jpm.util.Hash;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hot reload for {@code jpm dev}: the app runs with a small agent of jpm's, and when only a module's code changed, jpm
 * recompiles it and has the agent redefine the classes that changed in the running JVM, instead of restarting it. The
 * JVM can only redefine method bodies; JetBrains Runtime, run with {@code -XX:+AllowEnhancedClassRedefinition}, also
 * takes added and removed members. When redefinition fails, jpm restarts the app.
 */
public final class HotReload {
    private HotReload() {
    }

    /**
     * What a reload did: {@code ok} with how many loaded classes were redefined, or not with the JVM's reason.
     */
    public record Result(boolean ok, int redefined, String reason) {}

    /**
     * The JVM options that load the agent for a module, with {@code channel} as the folder jpm and the agent talk through.
     */
    public static List<String> jvmArgs(Builder b, Project.Module m, Path channel) {
        Jdk jdk = b.jdk(m.manifest);
        Path jar = agentJar(jdk, m);
        Fs.deleteTree(channel);
        Fs.mkdirs(channel);
        List<String> args = new ArrayList<>();
        args.add("-javaagent:" + jar + "=" + channel);
        if (enhanced(jdk)) args.add("-XX:+AllowEnhancedClassRedefinition");
        return args;
    }

    /**
     * Whether a JDK can redefine classes with changed members: JetBrains Runtime.
     */
    public static boolean enhanced(Jdk jdk) {
        String d = jdk.distribution() == null ? "" : jdk.distribution().toLowerCase(java.util.Locale.ROOT);
        return d.contains("jetbrains") || d.contains("jbr");
    }

    private static Path agentJar(Jdk jdk, Project.Module m) {
        Path base = m.cacheDir().resolve("reload-agent");
        Path src = base.resolve("src");
        Fs.writeIfChanged(src.resolve(ReloadAgent.CLASS.replace('.', '/') + ".java"), ReloadAgent.SOURCE);
        Path classes = base.resolve("classes");
        Compiler.compile(new Compiler.Request("reload agent", jdk, Math.min(Math.max(8, jdk.major()), 11), List.of(src), List.of(), List.of(),
                classes, base.resolve("state.json"), List.of("-nowarn"), "reload-agent"));
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("Premain-Class", ReloadAgent.CLASS);
        attrs.put("Can-Redefine-Classes", "true");
        Path jar = base.resolve("jpm-reload-agent.jar");
        Jars.write(jar, List.of(classes), attrs);
        return jar;
    }

    /**
     * A hash of every class file under a folder, by class name.
     */
    public static Map<String, String> classes(Path dir) {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) return out;
        for (Path f : Fs.files(dir, ".class")) {
            String rel = Fs.slash(dir.relativize(f));
            if (rel.equals("module-info.class") || rel.endsWith("/module-info.class") || rel.startsWith("META-INF/")) continue;
            try {
                out.put(rel.substring(0, rel.length() - ".class".length()).replace('/', '.'), Hash.sha256(Files.readAllBytes(f)));
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    /**
     * The classes that are new or different in {@code after}.
     */
    public static List<String> changed(Map<String, String> before, Map<String, String> after) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : after.entrySet()) if (!e.getValue().equals(before.get(e.getKey()))) out.add(e.getKey());
        return out;
    }

    /**
     * Asks the agent to redefine these classes from {@code classesDir}, waiting up to ten seconds for its answer.
     */
    public static Result reload(Path channel, Path classesDir, List<String> classes) throws InterruptedException {
        StringBuilder sb = new StringBuilder();
        for (String c : classes) sb.append(c).append('\t').append(classesDir.resolve(c.replace('.', '/') + ".class").toAbsolutePath()).append('\n');
        Path result = channel.resolve("result");
        try {
            Files.deleteIfExists(result);
            Path tmp = channel.resolve("request.tmp");
            Files.writeString(tmp, sb.toString());
            Files.move(tmp, channel.resolve("request"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            return new Result(false, 0, "cannot reach the reload agent: " + e.getMessage());
        }
        long until = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < until) {
            if (Files.exists(result)) {
                String answer = Fs.read(result).strip();
                try {
                    Files.deleteIfExists(result);
                } catch (IOException ignored) {
                }
                if (answer.startsWith("ok ")) return new Result(true, Integer.parseInt(answer.substring(3).trim()), null);
                return new Result(false, 0, answer.startsWith("fail ") ? answer.substring(5) : answer);
            }
            Thread.sleep(50);
        }
        return new Result(false, 0, "the reload agent did not answer");
    }
}
