package dev.jpm.build;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.log.Log;
import dev.jpm.resolve.Install;
import dev.jpm.resolve.Resolver;
import dev.jpm.toolchain.Jdk;
import dev.jpm.util.Exec;
import dev.jpm.util.JpmException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Custom commands from the manifest's {@code scripts}, run with {@code jpm run <name>}, and hooks around
 * built-in steps.
 *
 * <p>A {@code .java} script runs directly on the source launcher with the project's runtime
 * classpath, the dependencies listed with it in the manifest, and build information in {@code JPM_*}
 * environment variables. A {@code .sh} script runs with
 * {@code sh}; anything else is a shell command line. Shell scripts are not guaranteed to work on every
 * platform.
 */
public final class Scripts {
    private Scripts() {
    }

    /**
     * Runs a script from {@code "scripts"} and returns its exit code.
     */
    public static int run(Builder b, Project.Module m, String name, List<String> args, Env.Mode mode) {
        String value = m.manifest.scripts.get(name);
        if (value == null) {
            throw new JpmException("No script named \"" + name + "\" in " + m.manifest.file
                    + (m.manifest.scripts.isEmpty() ? "" : ". Scripts: " + String.join(", ", m.manifest.scripts.keySet())));
        }
        return exec(b, m, name, value, args, mode, new LinkedHashSet<>(), Map.of());
    }

    /**
     * Runs the module's generators of one kind ({@code sources} or {@code resources}) that are out of date, and returns
     * the folders they generate into, for every generator of that kind.
     */
    public static List<Path> generate(Builder b, Project.Module m, String kind) {
        List<Path> out = new ArrayList<>();
        for (Manifest.Generator g : m.manifest.generators.values()) {
            if (!g.kind().equals(kind)) continue;
            Path dir = m.cacheDir().resolve("generators").resolve(g.name());
            Path stamp = m.cacheDir().resolve("generators").resolve(g.name() + ".key");
            dev.jpm.util.Hash h = new dev.jpm.util.Hash().add("generate-v1").add(g.name()).add(kind).add(m.manifest.scripts.get(g.name()))
                    .add(String.valueOf(m.manifest.scriptDependencies.getOrDefault(g.name(), Map.of())));
            for (String in : g.inputs()) {
                Path p = m.dir.resolve(in);
                h.add("input " + in);
                List<Path> files = Files.isDirectory(p) ? dev.jpm.util.Fs.files(p, null) : Files.isRegularFile(p) ? List.of(p) : List.of();
                for (Path f : files) h.add(dev.jpm.util.Fs.slash(m.dir.relativize(f)) + " " + dev.jpm.util.Hash.sha256(f));
            }
            String key = h.hex();
            out.add(dir);
            if (Files.isDirectory(dir) && Files.isRegularFile(stamp) && dev.jpm.util.Fs.read(stamp).equals(key)) continue;
            dev.jpm.util.Fs.deleteTree(dir);
            dev.jpm.util.Fs.mkdirs(dir);
            try {
                Files.deleteIfExists(stamp);
            } catch (java.io.IOException ignored) {
            }
            int code = exec(b, m, g.name(), m.manifest.scripts.get(g.name()), new ArrayList<>(), Env.Mode.DEVELOPMENT, new LinkedHashSet<>(),
                    Map.of("JPM_GENERATED", dir.toString()));
            if (code != 0) throw new JpmException(m.name() + ": the generator \"" + g.name() + "\" failed (exit " + code + ")");
            dev.jpm.util.Fs.write(stamp, key);
            Log.step("Generated", m.name() + " " + kind + " with " + g.name());
        }
        return out;
    }

    private static int exec(Builder b, Project.Module m, String name, String value, List<String> args, Env.Mode mode, Set<String> stack,
                            Map<String, String> extra) {
        if (!stack.add(name)) throw new JpmException("Script \"" + name + "\" calls itself");
        String alias = m.manifest.scripts.get(value);
        if (alias != null && !value.equals(name)) return exec(b, m, value, alias, args, mode, stack, extra);

        Map<String, String> env = new LinkedHashMap<>(Env.forChild(b.project.dir, mode));
        env.putAll(info(b, m));
        env.putAll(extra);
        List<String> cmd = new ArrayList<>();
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        Path file = m.dir.resolve(value);
        boolean javaScript = value.endsWith(".java") && Files.exists(file);
        if (!javaScript && !m.manifest.scriptDependencies.getOrDefault(name, Map.of()).isEmpty()) {
            env.put("JPM_SCRIPT_CLASSPATH", Compiler.join(scriptClasspath(b, m, name)));
        }
        if (javaScript) {
            Jdk jdk = b.jdk(m.manifest);
            if (jdk.major() < 11) throw new JpmException("Java scripts need JDK 11 or newer");
            cmd.add(jdk.java());
            List<Path> own = scriptClasspath(b, m, name);
            String cp = env.get("JPM_CLASSPATH");
            String all = own.isEmpty() ? cp : Compiler.join(own) + (cp == null || cp.isEmpty() ? "" : java.io.File.pathSeparator + cp);
            if (all != null && !all.isEmpty()) {
                cmd.add("-cp");
                cmd.add(all);
            }
            cmd.add(file.toString());
        } else if (value.endsWith(".sh") && Files.exists(file)) {
            cmd.add("sh");
            cmd.add(file.toString());
        } else if (win) {
            cmd.add("cmd");
            cmd.add("/c");
            cmd.add(value);
        } else {
            cmd.add("sh");
            cmd.add("-c");
            cmd.add(value + " \"$@\"");
            cmd.add(name);
        }
        cmd.addAll(args);
        Log.debug("script " + name + ": " + String.join(" ", cmd));
        return Exec.inherit(m.dir, cmd, env);
    }

    /**
     * The jars of a script's {@code "dependencies"}: resolved in a graph of their own, so a script's tools never
     * change the versions the project gets.
     */
    static List<Path> scriptClasspath(Builder b, Project.Module m, String name) {
        Map<String, String> deps = m.manifest.scriptDependencies.getOrDefault(name, Map.of());
        if (deps.isEmpty()) return List.of();
        List<Resolver.Root> roots = new ArrayList<>();
        for (Map.Entry<String, String> d : deps.entrySet()) {
            roots.add(new Resolver.Root("script " + name, DepKey.parse(d.getKey()), d.getValue()));
        }
        return Install.classpath(roots, b.repo);
    }

    /**
     * Build information passed to scripts and extensions.
     */
    public static Map<String, String> info(Builder b, Project.Module m) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JPM_EXE", dev.jpm.util.Self.exe());
        env.put("JPM_VERSION_OF_JPM", dev.jpm.BuildConfig.VERSION);
        env.putAll(Log.childEnv());
        env.put("JPM_PROJECT_DIR", b.project.dir.toString());
        env.put("JPM_MODULE_DIR", m.dir.toString());
        env.put("JPM_NAME", m.name());
        env.put("JPM_VERSION", m.version());
        env.put("JPM_MANIFEST", m.manifest.file.toString());
        Path classes = Builder.classesDir(m);
        env.put("JPM_CLASSES_DIR", classes.toString());
        Path jar = b.jarPath(m);
        if (Files.exists(jar)) env.put("JPM_JAR", jar.toString());
        try {
            List<Path> cp = new ArrayList<>();
            if (Files.isDirectory(classes)) cp.add(classes);
            for (Path p : b.classpathNoBuild(m)) cp.add(p);
            env.put("JPM_CLASSPATH", Compiler.join(Compiler.distinct(cp)));
        } catch (JpmException e) {
            Log.debug("no classpath for scripts: " + e.getMessage());
        }
        try {
            Jdk jdk = b.jdk(m.manifest);
            env.put("JPM_JAVA_HOME", jdk.home().toString());
        } catch (JpmException ignored) {
        }
        return env;
    }

    /**
     * Runs the hooks for a step: {@code pre<step>} / {@code post<step>} and their {@code :<profile>} variants.
     */
    public static void hooks(Builder b, Project.Module m, Manifest manifest, String phase, String step, String profile, Env.Mode mode) {
        List<String> names = new ArrayList<>();
        names.add(phase + step);
        if (profile != null) names.add(phase + step + ":" + profile);
        for (String h : names) {
            String target = manifest.hooks.get(h);
            if (target == null) continue;
            Log.step("Hook", h + " -> " + target);
            int code;
            if (manifest.scripts.containsKey(target)) code = exec(b, m, target, manifest.scripts.get(target), List.of(), mode, new LinkedHashSet<>(), Map.of());
            else code = exec(b, m, h, target, List.of(), mode, new LinkedHashSet<>(), Map.of());
            if (code != 0) throw new JpmException("Hook " + h + " failed with exit code " + code, code);
        }
    }
}
