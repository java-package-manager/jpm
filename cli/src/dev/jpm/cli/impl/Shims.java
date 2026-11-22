package dev.jpm.cli.impl;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.toolchain.JavaSpec;
import dev.jpm.toolchain.Jdk;
import dev.jpm.toolchain.Toolchain;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Shims {
    private Shims() {}

    static final List<String> TOOLS = List.of("java", "javac", "jar", "jarsigner", "javadoc", "javap", "jshell",
            "jlink", "jpackage", "jdeps", "jcmd", "jps", "jstack", "jmap", "jstat", "jfr", "jdb", "keytool",
            "native-image");

    public static boolean isTool(String invokedAs) {
        if (invokedAs == null || invokedAs.isEmpty()) return false;
        String name = invokedAs.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase();
        if (name.endsWith(".exe")) name = name.substring(0, name.length() - 4);
        return TOOLS.contains(name);
    }

    static String toolName(String invokedAs) {
        String name = invokedAs.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.toLowerCase().endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    static Path bin() {
        return Fs.home().resolve("bin");
    }

    static Path defaultFile() {
        return Fs.home().resolve("default-java");
    }

    record Choice(Jdk jdk, String reason, Path systemTool) {}

    static String defaultSpec() {
        Path f = defaultFile();
        if (!Files.exists(f)) return null;
        String s = Fs.read(f).strip();
        return s.isEmpty() ? null : s;
    }

    static Choice choose(Path cwd, String tool) {
        String envSpec = System.getenv("JPM_JAVA");
        if (envSpec != null && !envSpec.isBlank()) {
            return new Choice(Toolchain.require(JavaSpec.parse(envSpec)), "JPM_JAVA=" + envSpec, null);
        }
        Manifest project = projectManifest(cwd);
        if (project != null && project.java != null) {
            Jdk j = Toolchain.require(JavaSpec.parse(project.java));
            return new Choice(j, "\"java\": \"" + project.java + "\" in " + project.file, null);
        }
        String def = defaultSpec();
        if (def != null) {
            return new Choice(Toolchain.require(JavaSpec.parse(def)), "default set with `jvm use " + def + "`", null);
        }
        Path system = Exec.which(tool, bin(), Shims::isSelf);
        if (system != null) return new Choice(null, "the next " + tool + " on PATH (no project or default)", system);
        Jdk any = Toolchain.find(JavaSpec.any());
        if (any != null) return new Choice(any, "the only JDK found (no project or default)", null);
        throw new JpmException("No JDK found for `" + tool + "`. Install one with `jvm install 21`, then `jvm use 21`.");
    }

    static boolean isSelf(Path p) {
        Path self = self();
        if (self == null) return false;
        try {
            return Files.isSameFile(p, self);
        } catch (IOException e) {
            return false;
        }
    }

    static Manifest projectManifest(Path cwd) {
        try {
            Project p = Project.find(cwd);
            Project.Module m = p.moduleAt(cwd);
            if (m != null && m.manifest.java != null) return m.manifest;
            if (p.root.java != null) return p.root;
            for (Project.Module mod : p.modules) if (mod.manifest.java != null) return mod.manifest;
            return p.root;
        } catch (JpmException e) {
            return null;
        }
    }

    static final int MAX_DEPTH = 8;

    public static int run(String invokedAs, List<String> args) {
        String tool = toolName(invokedAs);
        int depth = depth(System.getenv("JPM_SHIM_DEPTH"));
        if (depth >= MAX_DEPTH) {
            throw new JpmException("The " + tool + " shim started itself " + depth + " times. Is a jpm shim on PATH twice? "
                    + "Run `jvm current` to see which " + tool + " is used.");
        }
        Path cwd = Path.of("").toAbsolutePath();
        Choice c = choose(cwd, tool);
        Path exe;
        Map<String, String> env;
        if (c.jdk() != null) {
            exe = c.jdk().bin(tool);
            if (!Files.exists(exe)) {
                throw new JpmException(tool + " is not part of " + c.jdk().id() + " (" + c.jdk().home() + "), chosen by " + c.reason()
                        + (tool.equals("native-image") ? ". Use a GraalVM, e.g. `jvm use graalvm-" + c.jdk().major() + "`." : ""));
            }
            env = JavaCommand.envFor(c.jdk());
        } else {
            exe = c.systemTool();
            env = Map.of();
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.addAll(args);
        Map<String, String> childEnv = new java.util.HashMap<>(env);
        childEnv.put("JPM_SHIM_DEPTH", Integer.toString(depth + 1));
        return execTransparently(cmd, childEnv);
    }

    static int depth(String value) {
        try {
            return value == null ? 0 : Math.max(0, Integer.parseInt(value.strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static int execTransparently(List<String> cmd, Map<String, String> env) {
        ignoreInterrupts();
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        pb.environment().remove("JPM_AS");
        pb.environment().putAll(env);
        try {
            Process p = pb.start();
            return p.waitFor();
        } catch (IOException e) {
            throw new JpmException("Could not run " + cmd.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    @SuppressWarnings("restriction")
    private static void ignoreInterrupts() {
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("INT"), sig -> {});
        } catch (Throwable ignored) {
        }
    }

    static Path self() {
        String cmd = ProcessHandle.current().info().command().orElse(null);
        if (cmd == null) return null;
        Path p = Path.of(cmd);
        String n = p.getFileName().toString().toLowerCase();
        if (n.equals("java") || n.equals("java.exe")) return null;
        return p;
    }

    static List<String> install() {
        Path self = self();
        if (self == null) {
            throw new JpmException("Shims need the native jpm binary (install it with the install script); the jar build"
                    + " cannot stand in for java");
        }
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        Path dir = bin();
        Fs.mkdirs(dir);
        Path target = dir.resolve(win ? "jpm.exe" : "jpm");
        if (!Files.exists(target)) target = self;
        List<String> done = new ArrayList<>();
        for (String t : TOOLS) {
            Path link = dir.resolve(win ? t + ".exe" : t);
            try {
                if (Files.isSymbolicLink(link) || Files.exists(link)) {
                    if (!isOurs(link, target)) {
                        Log.warn("leaving " + link + " alone: it is not a jpm shim");
                        continue;
                    }
                    Files.delete(link);
                }
                if (win) Files.createLink(link, target);
                else Files.createSymbolicLink(link, target.getParent().equals(dir) ? target.getFileName() : target);
                done.add(t);
            } catch (IOException | UnsupportedOperationException e) {
                throw new JpmException("Could not create the " + t + " shim in " + dir + ": " + e.getMessage());
            }
        }
        return done;
    }

    private static boolean isOurs(Path link, Path target) throws IOException {
        if (Files.isSymbolicLink(link)) {
            Path to = link.getParent().resolve(Files.readSymbolicLink(link)).normalize();
            return to.getFileName().toString().startsWith("jpm");
        }
        try {
            return Files.isSameFile(link, target) || Files.size(link) > 10_000_000;
        } catch (IOException e) {
            return false;
        }
    }

    static List<String> remove() {
        List<String> removed = new ArrayList<>();
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        Path dir = bin();
        Path target = dir.resolve(win ? "jpm.exe" : "jpm");
        for (String t : TOOLS) {
            Path link = dir.resolve(win ? t + ".exe" : t);
            try {
                if ((Files.isSymbolicLink(link) || Files.exists(link)) && isOurs(link, target)) {
                    Files.delete(link);
                    removed.add(t);
                }
            } catch (IOException e) {
                throw new JpmException("Could not remove " + link + ": " + e.getMessage());
            }
        }
        return removed;
    }

    static boolean installed() {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        return Files.exists(bin().resolve(win ? "java.exe" : "java"), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    static Set<String> tools() {
        return Set.copyOf(TOOLS);
    }
}
