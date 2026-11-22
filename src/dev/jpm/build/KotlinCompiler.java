package dev.jpm.build;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.resolve.Resolver;
import dev.jpm.toolchain.Jdk;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.Hash;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a module's Kotlin sources with kotlinc ({@code org.jetbrains.kotlin:kotlin-compiler-embeddable} of the
 * module's {@code "kotlin"} version, resolved on its own like any tool, run on the module's JDK).
 *
 * <p>Kotlin compiles first, seeing the module's Java sources (and the generated defines class) as sources, so the
 * two languages can refer to each other; javac then compiles the Java sources against its output. A module's Kotlin
 * is compiled as a whole whenever a source, the classpath or a setting changes.
 */
public final class KotlinCompiler {
    private KotlinCompiler() {}

    public static final String MAIN = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler";

    /**
     * One kotlinc run. {@code friends}: class folders whose {@code internal} declarations this one may use (the
     * module's main classes, for its tests).
     */
    public record Request(String label, String moduleName, Manifest.Kotlin kotlin, Jdk jdk, int release, List<Path> sourceRoots,
                          List<Path> excludes, List<Path> classpath, List<Path> friends, Path outDir, Path stateFile) {}

    /** The Kotlin sources under the roots, outside the excluded folders. */
    public static List<Path> sources(List<Path> roots, List<Path> excludes) {
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            for (Path p : Fs.files(root, ".kt")) {
                if (excludes.stream().noneMatch(p::startsWith)) out.add(p);
            }
        }
        return out;
    }

    /**
     * Compiles if anything changed. Returns true if kotlinc ran. Without Kotlin sources, the output folder is
     * removed and nothing runs.
     */
    public static boolean compile(Builder b, Request r) {
        List<Path> kt = sources(r.sourceRoots, r.excludes);
        if (kt.isEmpty()) {
            Fs.deleteTree(r.outDir);
            try {
                Files.deleteIfExists(r.stateFile);
            } catch (IOException ignored) {
            }
            return false;
        }
        if (r.kotlin == null) {
            throw new JpmException(r.label + " has Kotlin sources (" + kt.get(0) + "), but no \"kotlin\" version in its "
                    + Manifest.FILE + ". Add one, e.g. \"kotlin\": \"2.2.20\", and the standard library,"
                    + " org.jetbrains.kotlin:kotlin-stdlib, to \"runtime\"");
        }
        List<Path> java = new ArrayList<>();
        for (Path root : r.sourceRoots) {
            for (Path p : Fs.files(root, ".java")) if (r.excludes.stream().noneMatch(p::startsWith)) java.add(p);
        }
        Hash h = new Hash().add("kotlinc-v1").add(r.kotlin.toString()).add(r.jdk.id()).add(Integer.toString(r.release))
                .add(r.moduleName);
        for (Path p : r.classpath) h.add("cp " + p + " " + stamp(p));
        for (Path p : r.friends) h.add("friend " + p);
        for (Path p : kt) h.add(p + " " + Hash.sha256(p));
        for (Path p : java) h.add(p + " " + Hash.sha256(p));
        String key = h.hex();
        if (Files.isDirectory(r.outDir) && Files.isRegularFile(r.stateFile) && Fs.read(r.stateFile).equals(key)) return false;

        if (r.classpath.stream().noneMatch(p -> p.getFileName().toString().startsWith("kotlin-stdlib"))) {
            throw new JpmException(r.label + ": the Kotlin standard library is not on the classpath. Add"
                    + " \"org.jetbrains.kotlin:kotlin-stdlib\": \"" + r.kotlin.version() + "\" to \"runtime\" (or to \"dev\""
                    + " when the platform provides it, as Gradle does for plugins)");
        }
        List<Path> compiler = b.resolveTool(List.of(new Resolver.Root("kotlinc " + r.kotlin.version(),
                DepKey.maven("org.jetbrains.kotlin", "kotlin-compiler-embeddable"), r.kotlin.version())), null,
                dev.jpm.resolve.Graph.Walk.RUNTIME);
        List<Path> plugins = new ArrayList<>();
        for (String coords : r.kotlin.pluginCoordinates()) plugins.add(plugin(b, coords));

        Path tmp = r.outDir.resolveSibling(r.outDir.getFileName() + ".tmp");
        Fs.deleteTree(tmp);
        Fs.mkdirs(tmp);
        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(tmp.toString());
        if (!r.classpath.isEmpty()) {
            args.add("-classpath");
            args.add(Compiler.join(r.classpath));
        }
        args.add("-jdk-home");
        args.add(r.jdk.home().toString());
        args.add("-jvm-target");
        args.add(r.release <= 8 ? "1.8" : Integer.toString(r.release));
        // as for javac: only when cross-compiling, so the JDK's own API of the target is what is compiled against
        if (r.release < r.jdk.major()) args.add("-Xjdk-release=" + (r.release <= 8 ? "1.8" : Integer.toString(r.release)));
        args.add("-module-name");
        args.add(r.moduleName);
        args.add("-no-stdlib");
        args.add("-no-reflect");
        args.add("-java-parameters");
        for (Path p : plugins) args.add("-Xplugin=" + p);
        if (!r.friends.isEmpty()) args.add("-Xfriend-paths=" + String.join(",", r.friends.stream().map(Path::toString).toList()));
        List<String> command = new ArrayList<>();
        command.add(r.jdk.java());
        for (String a : r.kotlin.args()) {
            if (a.startsWith("-J")) command.add(a.substring(2));
            else args.add(a);
        }
        command.add("-Djava.awt.headless=true");
        command.add("-cp");
        command.add(Compiler.join(compiler));
        command.add(MAIN);
        for (Path p : kt) args.add(p.toString());
        for (Path p : java) args.add(p.toString());
        Path argFile = r.stateFile.resolveSibling(r.outDir.getFileName() + ".args");
        Fs.write(argFile, String.join("\n", args.stream().map(KotlinCompiler::quote).toList()) + "\n");
        command.add("@" + argFile);
        Log.debug(r.label + ": kotlinc " + r.kotlin.version() + " on " + kt.size() + " Kotlin and " + java.size() + " Java file(s)");
        Exec.Result res = Exec.capture(null, command);
        String out = (res.out() + res.err()).strip();
        if (!res.ok()) {
            Fs.deleteTree(tmp);
            try {
                Files.deleteIfExists(r.stateFile);
            } catch (IOException ignored) {
            }
            Log.tool(out);
            throw new JpmException(r.label + ": Kotlin compilation failed");
        }
        Log.tool(out);
        Fs.deleteTree(r.outDir);
        try {
            Files.move(tmp, r.outDir);
        } catch (IOException e) {
            throw new JpmException("Could not move " + tmp + " to " + r.outDir + ": " + e.getMessage());
        }
        Fs.write(r.stateFile, key);
        return true;
    }

    /** A compiler plugin's own jar (its dependencies are the compiler's). */
    private static Path plugin(Builder b, String coords) {
        String[] p = coords.split(":");
        List<Path> jars = b.resolveTool(List.of(new Resolver.Root("kotlin plugin " + coords, DepKey.maven(p[0], p[1]), p[2])), null,
                dev.jpm.resolve.Graph.Walk.RUNTIME);
        String prefix = p[1] + "-" + p[2];
        for (Path j : jars) if (j.getFileName().toString().startsWith(prefix)) return j;
        if (jars.isEmpty()) throw new JpmException("Kotlin compiler plugin " + coords + " has no jar");
        return jars.get(0);
    }

    private static String stamp(Path p) {
        try {
            if (Files.isDirectory(p)) return Builder.treeKey(List.of(p));
            return Files.size(p) + ":" + Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return "missing";
        }
    }

    /** kotlinc's argfiles split on whitespace; quoted arguments keep it. */
    private static String quote(String s) {
        if (s.isEmpty() || s.chars().anyMatch(c -> c == ' ' || c == '"' || c == '\'' || c == '\\' || c == '\t')) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return s;
    }
}
