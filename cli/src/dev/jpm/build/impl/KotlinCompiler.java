package dev.jpm.build.impl;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.api.json.Jsonc;
import dev.jpm.build.Builder;
import dev.jpm.build.Compiler;
import dev.jpm.log.Log;
import dev.jpm.resolve.Resolver;
import dev.jpm.toolchain.Jdk;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.Hash;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

public final class KotlinCompiler {
    private KotlinCompiler() {
    }

    public static final String MAIN = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler";

    public record Request(String label, String moduleName, Manifest.Kotlin kotlin, Jdk jdk, int release, List<Path> sourceRoots,
                          List<Path> excludes, List<Path> classpath, List<Path> friends, Path outDir, Path stateFile) {}

    record State(String key, Map<String, String> hashes, Map<String, List<String>> classes, Map<String, Set<String>> refs,
                 Map<String, String> bytes) {
        static State empty(String key) {
            return new State(key, new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), new TreeMap<>());
        }
    }

    public static List<Path> sources(List<Path> roots, List<Path> excludes) {
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            for (Path p : Fs.files(root, ".kt")) {
                if (excludes.stream().noneMatch(p::startsWith)) out.add(p);
            }
        }
        return out;
    }

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
        Hash h = new Hash().add("kotlinc-v2").add(r.kotlin.toString()).add(r.jdk.id()).add(Integer.toString(r.release))
                .add(r.moduleName);
        for (Path p : r.classpath) h.add("cp " + p + " " + stamp(p));
        for (Path p : r.friends) h.add("friend " + p);
        for (Path p : java) h.add(p + " " + Hash.sha256(p));
        String key = h.hex();
        Map<String, String> hashes = new TreeMap<>();
        for (Path p : kt) hashes.put(p.toString(), Hash.sha256(p));

        State before = Files.isDirectory(r.outDir) ? readState(r.stateFile) : null;
        if (before != null && before.key().equals(key) && before.hashes().equals(hashes)) return false;

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

        if (before != null && before.key().equals(key) && java.isEmpty() && incremental(r, compiler, plugins, before, hashes)) {
            return true;
        }
        full(r, compiler, plugins, kt, java, key, hashes);
        return true;
    }

    private static boolean incremental(Request r, List<Path> compiler, List<Path> plugins, State st, Map<String, String> hashes) {
        Set<String> changed = new TreeSet<>();
        for (Map.Entry<String, String> e : hashes.entrySet()) {
            if (!e.getValue().equals(st.hashes().get(e.getKey()))) changed.add(e.getKey());
        }
        for (String old : st.hashes().keySet()) if (!hashes.containsKey(old)) return false;
        Map<String, Integer> names = new TreeMap<>();
        for (String f : hashes.keySet()) names.merge(Path.of(f).getFileName().toString(), 1, Integer::sum);
        Set<String> compiled = new TreeSet<>();
        Set<String> batch = changed;
        Path tmp = r.outDir.resolveSibling(r.outDir.getFileName() + ".tmp");
        while (!batch.isEmpty()) {
            for (String f : batch) if (names.get(Path.of(f).getFileName().toString()) > 1) return false;
            Log.debug(r.label + ": kotlinc on " + batch.size() + " changed file(s)");
            List<Path> cp = new ArrayList<>(r.classpath);
            cp.add(r.outDir);
            for (String f : batch) for (String c : st.classes().getOrDefault(f, List.of())) delete(r.outDir, c);
            run(r, compiler, plugins, cp, batch.stream().map(Path::of).toList(), List.of(), tmp);
            Map<String, List<String>> produced = classesOf(tmp, batch);
            if (facades(tmp, produced)) {
                Fs.deleteTree(tmp);
                return false;
            }
            Set<String> changedClasses = new TreeSet<>();
            for (String f : batch) {
                List<String> now = produced.getOrDefault(f, List.of());
                for (String c : st.classes().getOrDefault(f, List.of())) {
                    st.refs().remove(c);
                    if (!now.contains(c)) {
                        changedClasses.add(c);
                        st.bytes().remove(c);
                    }
                }
                for (String c : now) {
                    ClassInfo info = read(tmp, c);
                    String bytes = Hash.sha256(tmp.resolve(c + ".class"));
                    if (!bytes.equals(st.bytes().get(c))) changedClasses.add(c);
                    st.refs().put(c, info.refs());
                    st.bytes().put(c, bytes);
                    Path to = r.outDir.resolve(c + ".class");
                    Fs.mkdirs(to.getParent());
                    try {
                        Files.move(tmp.resolve(c + ".class"), to, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new JpmException("Could not move " + c + " into " + r.outDir + ": " + e.getMessage());
                    }
                }
                st.classes().put(f, new ArrayList<>(now));
                st.hashes().put(f, hashes.get(f));
            }
            Fs.deleteTree(tmp);
            compiled.addAll(batch);
            Set<String> next = new TreeSet<>();
            for (Map.Entry<String, List<String>> e : st.classes().entrySet()) {
                if (compiled.contains(e.getKey())) continue;
                for (String c : e.getValue()) {
                    Set<String> refs = st.refs().getOrDefault(c, Set.of());
                    if (changedClasses.stream().anyMatch(refs::contains)) {
                        next.add(e.getKey());
                        break;
                    }
                }
            }
            batch = next;
        }
        writeState(r.stateFile, new State(st.key(), hashes, st.classes(), st.refs(), st.bytes()));
        return true;
    }

    private static void full(Request r, List<Path> compiler, List<Path> plugins, List<Path> kt, List<Path> java, String key,
                             Map<String, String> hashes) {
        Log.debug(r.label + ": kotlinc " + r.kotlin.version() + " on " + kt.size() + " Kotlin and " + java.size() + " Java file(s)");
        Path tmp = r.outDir.resolveSibling(r.outDir.getFileName() + ".tmp");
        try {
            Files.deleteIfExists(r.stateFile);
        } catch (IOException ignored) {
        }
        run(r, compiler, plugins, r.classpath, kt, java, tmp);
        Map<String, List<String>> produced = classesOf(tmp, hashes.keySet());
        State st = State.empty(key);
        for (Map.Entry<String, List<String>> e : produced.entrySet()) {
            st.classes().put(e.getKey(), e.getValue());
            for (String c : e.getValue()) {
                st.refs().put(c, read(tmp, c).refs());
                st.bytes().put(c, Hash.sha256(tmp.resolve(c + ".class")));
            }
        }
        Fs.deleteTree(r.outDir);
        try {
            Files.move(tmp, r.outDir);
        } catch (IOException e) {
            throw new JpmException("Could not move " + tmp + " to " + r.outDir + ": " + e.getMessage());
        }
        writeState(r.stateFile, new State(key, hashes, st.classes(), st.refs(), st.bytes()));
    }

    private static void run(Request r, List<Path> compiler, List<Path> plugins, List<Path> classpath, List<Path> kt,
                            List<Path> java, Path tmp) {
        Fs.deleteTree(tmp);
        Fs.mkdirs(tmp);
        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(tmp.toString());
        if (!classpath.isEmpty()) {
            args.add("-classpath");
            args.add(Compiler.join(classpath));
        }
        args.add("-jdk-home");
        args.add(r.jdk.home().toString());
        args.add("-jvm-target");
        args.add(r.release <= 8 ? "1.8" : Integer.toString(r.release));
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
    }

    private static Map<String, List<String>> classesOf(Path dir, Set<String> files) {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (String f : files) byName.computeIfAbsent(Path.of(f).getFileName().toString(), k -> new ArrayList<>()).add(f);
        Map<String, List<String>> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path c : s.filter(x -> x.toString().endsWith(".class")).sorted().toList()) {
                String name = dir.relativize(c).toString().replace('\\', '/');
                name = name.substring(0, name.length() - ".class".length());
                String finalName = name;
                for (String src : byName.getOrDefault(read(dir, name).sourceFile(), List.of())) {
                    out.computeIfAbsent(src, k -> new ArrayList<>()).add(finalName);
                }
            }
        } catch (IOException e) {
            throw new JpmException("Could not read " + dir + ": " + e.getMessage());
        }
        return out;
    }

    private static boolean facades(Path dir, Map<String, List<String>> produced) {
        Path meta = dir.resolve("META-INF");
        if (!Files.isDirectory(meta)) return false;
        for (Path m : Fs.files(meta, ".kotlin_module")) {
            String text;
            try {
                text = new String(Files.readAllBytes(m), StandardCharsets.ISO_8859_1);
            } catch (IOException e) {
                return true;
            }
            for (List<String> cs : produced.values()) {
                for (String c : cs) if (text.contains(c.substring(c.lastIndexOf('/') + 1))) return true;
            }
        }
        return false;
    }

    private static ClassInfo read(Path dir, String c) {
        try {
            return ClassInfo.read(Files.readAllBytes(dir.resolve(c + ".class")));
        } catch (IOException e) {
            throw new JpmException("Could not read class " + c + ": " + e.getMessage());
        }
    }

    private static void delete(Path dir, String c) {
        try {
            Files.deleteIfExists(dir.resolve(c + ".class"));
        } catch (IOException e) {
            throw new JpmException("Could not delete " + c + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static State readState(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            if (!(Jsonc.parse(Fs.read(file)) instanceof Map<?, ?> root)) return null;
            State st = State.empty(String.valueOf(root.get("key")));
            for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("hashes")).entrySet()) {
                st.hashes().put(e.getKey(), String.valueOf(e.getValue()));
            }
            for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("classes")).entrySet()) {
                st.classes().put(e.getKey(), new ArrayList<>(((List<Object>) e.getValue()).stream().map(String::valueOf).toList()));
            }
            for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("refs")).entrySet()) {
                st.refs().put(e.getKey(), new TreeSet<>(((List<Object>) e.getValue()).stream().map(String::valueOf).toList()));
            }
            for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("bytes")).entrySet()) {
                st.bytes().put(e.getKey(), String.valueOf(e.getValue()));
            }
            return st;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void writeState(Path file, State st) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("key", st.key());
        root.put("hashes", st.hashes());
        root.put("classes", st.classes());
        Map<String, Object> refs = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : st.refs().entrySet()) refs.put(e.getKey(), new ArrayList<>(e.getValue()));
        root.put("refs", refs);
        root.put("bytes", st.bytes());
        Fs.write(file, Jsonc.write(root) + "\n");
    }

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

    private static String quote(String s) {
        if (s.isEmpty() || s.chars().anyMatch(c -> c == ' ' || c == '"' || c == '\'' || c == '\\' || c == '\t')) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return s;
    }
}
