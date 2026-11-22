package dev.jpm.build;

import dev.jpm.build.impl.ClassInfo;

import dev.jpm.api.json.Jsonc;
import dev.jpm.toolchain.Jdk;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.Hash;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Incremental javac driver.
 *
 * <ul>
 *   <li>Per module: if the inputs key (classpath, defines, JDK, flags) changes, everything is rebuilt.</li>
 *   <li>Per file: changed sources are recompiled; when their public signature changes, every source
 *       that refers to them is recompiled too, repeatedly. A change to a compile-time constant
 *       rebuilds the module, since javac inlines constants without leaving a reference.</li>
 * </ul>
 */
public final class Compiler {
    private Compiler() {}

    /** One javac run. */
    public record Request(String label, Jdk jdk, int release, List<Path> sourceRoots, List<Path> classpath,
                          List<Path> processorPath, Path outDir, Path stateFile, List<String> extraArgs,
                          String inputsKey, List<Path> excludes) {
        /** A request without folders to leave out of the sources. */
        public Request(String label, Jdk jdk, int release, List<Path> sourceRoots, List<Path> classpath,
                       List<Path> processorPath, Path outDir, Path stateFile, List<String> extraArgs, String inputsKey) {
            this(label, jdk, release, sourceRoots, classpath, processorPath, outDir, stateFile, extraArgs, inputsKey, List.of());
        }
    }

    private static final class State {
        String key;
        /** source key -> hash */
        final Map<String, String> hashes = new TreeMap<>();
        /** source key -> class names */
        final Map<String, Set<String>> classes = new TreeMap<>();
        /** class name -> info */
        final Map<String, ClassInfo> info = new TreeMap<>();
    }

    /** Sources inside an excluded folder (the assets inside the source folder) are not compiled. */
    private static boolean excluded(Request r, Path p) {
        for (Path e : r.excludes) if (p.startsWith(e)) return true;
        return false;
    }

    /** Compiles if needed. Returns true if javac ran. */
    public static boolean compile(Request r) {
        Map<String, Path> sources = new TreeMap<>();
        for (int i = 0; i < r.sourceRoots.size(); i++) {
            Path root = r.sourceRoots.get(i);
            for (Path p : Fs.files(root, ".java")) if (!excluded(r, p)) sources.put(i + ":" + Fs.slash(root.relativize(p)), p);
        }
        String key = new Hash().add(r.inputsKey).add(r.jdk.id()).add(Integer.toString(r.release))
                .add(String.join("\u0001", r.extraArgs)).hex();
        Map<String, String> hashes = new TreeMap<>();
        for (Map.Entry<String, Path> e : sources.entrySet()) hashes.put(e.getKey(), Hash.sha256(e.getValue()));

        State old = readState(r.stateFile);
        if (sources.isEmpty()) {
            Fs.deleteTree(r.outDir);
            Fs.mkdirs(r.outDir);
            State s = new State();
            s.key = key;
            writeState(r.stateFile, s);
            return false;
        }
        boolean processors = !r.processorPath.isEmpty() || Modules.moduleInfo(r.sourceRoots) != null;
        if (old == null || !key.equals(old.key) || !Files.isDirectory(r.outDir)) {
            full(r, sources, hashes, key);
            return true;
        }
        Set<String> changed = new TreeSet<>();
        for (Map.Entry<String, String> e : hashes.entrySet()) {
            if (!e.getValue().equals(old.hashes.get(e.getKey()))) changed.add(e.getKey());
        }
        Set<String> removed = new TreeSet<>(old.hashes.keySet());
        removed.removeAll(hashes.keySet());
        if (changed.isEmpty() && removed.isEmpty()) return false;
        if (processors) {
            full(r, sources, hashes, key);
            return true;
        }

        State st = old;
        st.key = key;
        Set<String> affected = new TreeSet<>();
        for (String s : removed) {
            for (String c : st.classes.getOrDefault(s, Set.of())) {
                ClassInfo ci = st.info.remove(c);
                if (ci != null && !ci.constants().equals(new Hash().hex())) {
                    full(r, sources, hashes, key);
                    return true;
                }
                affected.add(c);
                deleteClass(r.outDir, c);
            }
            st.classes.remove(s);
            st.hashes.remove(s);
        }
        Set<String> compiled = new TreeSet<>();
        Set<String> batch = new TreeSet<>(changed);
        batch.addAll(dependents(st, affected, batch));
        affected.clear();
        int rounds = 0;
        while (!batch.isEmpty()) {
            if (++rounds > 20) {
                full(r, sources, hashes, key);
                return true;
            }
            Log.debug(r.label + ": recompiling " + batch.size() + " file(s)");
            Map<String, ClassInfo> before = new LinkedHashMap<>();
            for (String s : batch) {
                for (String c : st.classes.getOrDefault(s, Set.of())) {
                    ClassInfo ci = st.info.remove(c);
                    if (ci != null) before.put(c, ci);
                    deleteClass(r.outDir, c);
                }
                st.classes.remove(s);
            }
            List<Path> files = batch.stream().map(sources::get).toList();
            Map<String, Set<String>> produced = javac(r, files, batch, sources, true);
            Set<String> after = new TreeSet<>();
            for (Map.Entry<String, Set<String>> e : produced.entrySet()) {
                st.classes.put(e.getKey(), e.getValue());
                st.hashes.put(e.getKey(), hashes.get(e.getKey()));
                after.addAll(e.getValue());
            }
            for (String s : batch) st.hashes.put(s, hashes.get(s));
            for (String c : after) st.info.put(c, readInfo(r.outDir, c));
            compiled.addAll(batch);

            Set<String> abiChanged = new TreeSet<>();
            for (Map.Entry<String, ClassInfo> e : before.entrySet()) {
                ClassInfo now = st.info.get(e.getKey());
                if (now == null || !now.abi().equals(e.getValue().abi())) abiChanged.add(e.getKey());
                if (now == null ? !e.getValue().constants().equals(new Hash().hex())
                        : !now.constants().equals(e.getValue().constants())) {
                    Log.debug(r.label + ": constant changed in " + e.getKey() + ", rebuilding module");
                    full(r, sources, hashes, key);
                    return true;
                }
            }
            for (String c : after) if (!before.containsKey(c)) abiChanged.add(c);
            affected.addAll(abiChanged);
            batch = dependents(st, affected, compiled);
            affected.clear();
        }
        writeState(r.stateFile, st);
        return true;
    }

    /** Sources (other than {@code skip}) with a class that refers to any of the affected classes. */
    private static Set<String> dependents(State st, Set<String> affected, Set<String> skip) {
        Set<String> out = new TreeSet<>();
        if (affected.isEmpty()) return out;
        for (Map.Entry<String, Set<String>> e : st.classes.entrySet()) {
            if (skip.contains(e.getKey()) || !st.hashes.containsKey(e.getKey())) continue;
            for (String c : e.getValue()) {
                ClassInfo ci = st.info.get(c);
                if (ci != null && intersects(ci.refs(), affected)) {
                    out.add(e.getKey());
                    break;
                }
            }
        }
        return out;
    }

    private static boolean intersects(Collection<String> a, Set<String> b) {
        for (String s : a) if (b.contains(s)) return true;
        return false;
    }

    private static void full(Request r, Map<String, Path> sources, Map<String, String> hashes, String key) {
        Log.debug(r.label + ": full compile of " + sources.size() + " file(s)");
        Fs.deleteTree(r.outDir);
        Fs.mkdirs(r.outDir);
        Map<String, Set<String>> produced = javac(r, new ArrayList<>(sources.values()), sources.keySet(), sources, false);
        State st = new State();
        st.key = key;
        st.hashes.putAll(hashes);
        for (Map.Entry<String, Set<String>> e : produced.entrySet()) {
            st.classes.put(e.getKey(), e.getValue());
            for (String c : e.getValue()) st.info.put(c, readInfo(r.outDir, c));
        }
        writeState(r.stateFile, st);
    }

    /** Runs javac into a temporary directory, moves the classes into place and maps them to sources. */
    private static Map<String, Set<String>> javac(Request r, List<Path> files, Collection<String> keys,
                                                  Map<String, Path> sources, boolean outDirOnClasspath) {
        Path tmp = r.outDir.resolveSibling(r.outDir.getFileName() + ".tmp");
        Fs.deleteTree(tmp);
        Fs.mkdirs(tmp);
        Path declared = Modules.moduleInfo(r.sourceRoots);
        if (declared != null && r.release < 9) {
            throw JpmException.at(declared, 0, r.label + " is a Java module (module-info.java), and modules need target 9 or newer;"
                    + " the target is " + r.release).withHint("set \"target\" to 9 or newer, or remove module-info.java to build for "
                    + r.release);
        }
        List<String> args = new ArrayList<>();
        if (r.jdk.major() >= 9) {
            String refused = r.release < r.jdk.major() ? refusedWithRelease(r.extraArgs) : null;
            if (refused != null) {
                throw new JpmException(r.label + ": \"javacArgs\" has " + refused + ", which javac allows only when the JDK is the"
                        + " target version; build with JDK " + r.release + " (" + r.jdk.id() + " targets " + r.release + " with --release)");
            }
            if (r.release < r.jdk.major()) {
                args.add("--release");
                args.add(Integer.toString(r.release));
            }
        } else {
            args.add("-source");
            args.add(Integer.toString(r.release));
            args.add("-target");
            args.add(Integer.toString(r.release));
        }
        args.add("-encoding");
        args.add("UTF-8");
        args.add("-g");
        args.add("-parameters");
        args.add("-implicit:none");
        args.add("-Xlint:-options");
        args.add("-d");
        args.add(tmp.toString());
        List<Path> cp = new ArrayList<>();
        if (outDirOnClasspath) cp.add(r.outDir);
        cp.addAll(r.classpath);
        Path moduleInfo = Modules.moduleInfo(r.sourceRoots);
        if (moduleInfo != null && r.jdk.major() >= 9) {
            Modules.Declaration decl = Modules.declaration(r.jdk, moduleInfo);
            List<List<Path>> split = Modules.split(decl, cp);
            if (!split.get(0).isEmpty()) {
                args.add("--module-path");
                args.add(join(split.get(0)));
            }
            if (!split.get(1).isEmpty()) {
                args.add("-cp");
                args.add(join(split.get(1)));
                if (decl.name() != null) {
                    args.add("--add-reads");
                    args.add(decl.name() + "=ALL-UNNAMED");
                }
            }
        } else if (!cp.isEmpty()) {
            args.add("-cp");
            args.add(join(cp));
        }
        if (r.processorPath.isEmpty()) {
            args.add("-proc:none");
        } else {
            args.add("-processorpath");
            args.add(join(r.processorPath));
            if (r.jdk.major() >= 21) args.add("-proc:full");
            Path gen = r.outDir.resolveSibling(r.outDir.getFileName() + "-generated");
            Fs.deleteTree(gen);
            Fs.mkdirs(gen);
            args.add("-s");
            args.add(gen.toString());
        }
        List<String> command = new ArrayList<>();
        command.add(r.jdk.javac());
        for (String a : r.extraArgs) {
            if (a.startsWith("-J")) command.add(a);
            else args.add(a);
        }
        for (Path f : files) args.add(f.toString());
        Path argFile = r.stateFile.resolveSibling(r.outDir.getFileName() + ".args");
        Fs.write(argFile, String.join("\n", args.stream().map(Compiler::argQuote).toList()) + "\n");
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
            throw new JpmException(r.label + ": compilation failed");
        }
        Log.tool(out);

        Map<String, Set<String>> produced = new TreeMap<>();
        Map<String, String> byPath = new LinkedHashMap<>();
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (String k : keys) {
            String rel = k.substring(k.indexOf(':') + 1);
            byPath.put(rel, k);
            String fn = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
            byName.computeIfAbsent(fn, x -> new ArrayList<>()).add(k);
        }
        for (Path cf : Fs.files(tmp, ".class")) {
            String cname = Fs.slash(tmp.relativize(cf));
            cname = cname.substring(0, cname.length() - 6);
            ClassInfo ci;
            try {
                ci = ClassInfo.read(Files.readAllBytes(cf));
            } catch (IOException e) {
                throw new JpmException("Could not read " + cf + ": " + e.getMessage());
            }
            String pkg = cname.contains("/") ? cname.substring(0, cname.lastIndexOf('/') + 1) : "";
            String src = ci.sourceFile() == null ? null : byPath.get(pkg + ci.sourceFile());
            if (src == null && ci.sourceFile() != null) {
                List<String> cands = byName.get(ci.sourceFile());
                if (cands != null && !cands.isEmpty()) src = cands.get(0);
            }
            if (src == null) src = "?generated";
            produced.computeIfAbsent(src, x -> new TreeSet<>()).add(cname);
            Path target = r.outDir.resolve(cname + ".class");
            try {
                Files.createDirectories(target.getParent());
                Files.move(cf, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new JpmException("Could not move " + cf + ": " + e.getMessage());
            }
        }
        for (String k : keys) produced.computeIfAbsent(k, x -> new TreeSet<>());
        Fs.deleteTree(tmp);
        return produced;
    }

    private static String argQuote(String s) {
        if (s.isEmpty() || s.chars().anyMatch(c -> c == ' ' || c == '"' || c == '\'' || c == '#' || c == '\\')) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return s;
    }

    /** Options that javac refuses together with {@code --release}: the JDK's own modules, opened or replaced. */
    private static final Set<String> SYSTEM_MODULE_OPTIONS = Set.of("--add-exports", "--add-reads", "--patch-module");
    private static final Set<String> SYSTEM_OPTIONS = Set.of("--system", "--upgrade-module-path");

    /** The first of {@code args} that javac refuses with {@code --release} (ADR 0002), or null. */
    static String refusedWithRelease(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i), option = a, value = null;
            int eq = a.indexOf('=');
            if (a.startsWith("--") && eq > 0) {
                option = a.substring(0, eq);
                value = a.substring(eq + 1);
            } else if (i + 1 < args.size()) {
                value = args.get(i + 1);
            }
            if (SYSTEM_OPTIONS.contains(option)) return option;
            if (SYSTEM_MODULE_OPTIONS.contains(option) && value != null) {
                String module = value.split("[/=]", 2)[0];
                if (module.startsWith("java.") || module.startsWith("jdk.")) return option + " " + value;
            }
        }
        return null;
    }

    /** Paths joined with the platform's path separator, for {@code -cp}. */
    public static String join(List<Path> paths) {
        return String.join(File.pathSeparator, paths.stream().map(Path::toString).toList());
    }

    private static void deleteClass(Path outDir, String c) {
        try {
            Files.deleteIfExists(outDir.resolve(c + ".class"));
        } catch (IOException ignored) {
        }
    }

    private static ClassInfo readInfo(Path outDir, String c) {
        try {
            return ClassInfo.read(Files.readAllBytes(outDir.resolve(c + ".class")));
        } catch (IOException e) {
            throw new JpmException("Could not read class " + c + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static State readState(Path file) {
        if (!Files.exists(file)) return null;
        try {
            Map<String, Object> root = (Map<String, Object>) Jsonc.parse(Fs.read(file));
            State s = new State();
            s.key = (String) root.get("key");
            Map<String, Object> srcs = (Map<String, Object>) root.get("sources");
            for (Map.Entry<String, Object> e : srcs.entrySet()) {
                Map<String, Object> v = (Map<String, Object>) e.getValue();
                String h = (String) v.get("hash");
                if (h != null && !h.isEmpty()) s.hashes.put(e.getKey(), h);
                s.classes.put(e.getKey(), new TreeSet<>((List<String>) v.get("classes")));
            }
            Map<String, Object> cls = (Map<String, Object>) root.get("classes");
            for (Map.Entry<String, Object> e : cls.entrySet()) {
                Map<String, Object> v = (Map<String, Object>) e.getValue();
                s.info.put(e.getKey(), new ClassInfo(e.getKey(), (String) v.get("source"),
                        new TreeSet<>((List<String>) v.get("refs")), (String) v.get("abi"), (String) v.get("constants")));
            }
            return s;
        } catch (RuntimeException e) {
            Log.debug("ignoring unreadable build state " + file + ": " + e);
            return null;
        }
    }

    private static void writeState(Path file, State s) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("key", s.key);
        Map<String, Object> srcs = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : s.hashes.entrySet()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("hash", e.getValue());
            v.put("classes", new ArrayList<>(s.classes.getOrDefault(e.getKey(), Set.of())));
            srcs.put(e.getKey(), v);
        }
        for (Map.Entry<String, Set<String>> e : s.classes.entrySet()) {
            if (!srcs.containsKey(e.getKey())) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("hash", "");
                v.put("classes", new ArrayList<>(e.getValue()));
                srcs.put(e.getKey(), v);
            }
        }
        root.put("sources", srcs);
        Map<String, Object> cls = new LinkedHashMap<>();
        for (ClassInfo ci : s.info.values()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("source", ci.sourceFile() == null ? "" : ci.sourceFile());
            v.put("abi", ci.abi());
            v.put("constants", ci.constants());
            v.put("refs", new ArrayList<>(ci.refs()));
            cls.put(ci.name(), v);
        }
        root.put("classes", cls);
        Fs.write(file, Jsonc.write(root) + "\n");
    }

    /** Helper for callers to collect a set of class directories and jars. */
    public static List<Path> distinct(Collection<Path> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }
}
