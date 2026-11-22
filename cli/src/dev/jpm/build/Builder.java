package dev.jpm.build;


import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.GitModule;
import dev.jpm.resolve.GradleDist;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Install;
import dev.jpm.resolve.Lockfile;
import dev.jpm.resolve.Resolver;
import dev.jpm.toolchain.JavaSpec;
import dev.jpm.toolchain.Jdk;
import dev.jpm.toolchain.Toolchain;
import dev.jpm.util.Fs;
import dev.jpm.util.Hash;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Turns a resolved graph into classpaths and build outputs: the project's own modules, Git dependencies
 * (built from source) and patched Maven jars.
 */
public final class Builder {
    public enum Cp { COMPILE, PROCESSOR, RUNTIME, TEST_COMPILE, TEST_RUNTIME }

    public final Project project;
    public final Graph graph;
    public final MavenRepo repo;
    private final Map<String, String> env;
    private final Map<String, List<Path>> artifacts = new HashMap<>();
    private final Set<String> building = new LinkedHashSet<>();
    private final Map<String, Boolean> compiledModules = new HashMap<>();
    private final Env.Mode mode;
    private final Map<String, Builder> isolatedBuilders = new HashMap<>();

    public Builder(Project project, Graph graph, MavenRepo repo, Env.Mode mode) {
        this.project = project;
        this.graph = graph;
        this.repo = repo;
        this.mode = mode;
        this.env = Env.load(project.dir, mode);
    }

    /**
     * The builder for a module: this one, or for an isolated module ({@code "isolated": true}) one over
     * its own graph, so its classpaths come from its own resolution.
     */
    public Builder forModule(Project.Module m) {
        if (!m.manifest.isolated) return this;
        Graph iso = graph.isolated.get(m.name());
        if (iso == null) return this;
        return isolatedBuilders.computeIfAbsent(m.name(), n -> new Builder(project.isolatedView(m), iso, repo, mode));
    }

    public Map<String, String> env() {
        return env;
    }

    /** The JDK a manifest asks for, installed first if it is missing. */
    public Jdk jdk(Manifest m) {
        try {
            return Toolchain.require(JavaSpec.parse(m.java));
        } catch (JpmException e) {
            JpmException at = JpmException.at(m.file, 0, e.getMessage());
            throw e.hint() == null ? at : at.withHint(e.hint());
        }
    }

    /**
     * The GraalVM for native builds of a module: the manifest's JDK if it is GraalVM, otherwise any
     * installed GraalVM of the same major version, otherwise GraalVM Community, installed.
     */
    public Jdk graalvm(Manifest m) {
        JavaSpec spec = JavaSpec.parse(m.java);
        if (spec.version() == null) spec = new JavaSpec(spec.distribution(), Integer.toString(jdk(m).major()));
        Jdk g = null;
        if (spec.graal()) {
            g = Toolchain.find(spec);
        } else {
            int major = spec.major();
            for (Jdk j : Jdk.discover()) if (j.major() == major && j.hasNativeImage()) { g = j; break; }
        }
        if (g == null) g = Toolchain.require(spec.asGraal());
        if (!g.hasNativeImage()) {
            throw new JpmException(g.id() + " (" + g.home() + ") has no native-image. Install a GraalVM with"
                    + " `jpm java install graalvm-" + g.major() + "`.");
        }
        return g;
    }

    /** Builds a native executable of a module's main into {@code dist/}. */
    public Path nativeImage(Project.Module m, String entry) {
        Map<String, String> mains = m.manifest.mains;
        if (mains.isEmpty()) throw new JpmException(m.manifest.file + " has no \"main\", so there is nothing to build natively");
        String main = entry != null ? mains.getOrDefault(entry, entry)
                : mains.getOrDefault("main", mains.values().iterator().next());
        Path jar = jar(m);
        Jdk graal = graalvm(m.manifest);
        List<Path> cp = new ArrayList<>();
        cp.add(jar);
        cp.addAll(classpath(m, Cp.RUNTIME));
        String exe = m.name() + (entry != null && mains.size() > 1 ? "-" + entry : "");
        Path out = m.dir.resolve("dist").resolve(exe);
        List<String> cmd = new ArrayList<>();
        cmd.add(graal.bin("native-image").toString());
        cmd.add("--no-fallback");
        cmd.addAll(m.manifest.nativeArgs);
        cmd.add("-cp");
        cmd.add(Compiler.join(Compiler.distinct(cp)));
        cmd.add("-o");
        cmd.add(out.toString());
        cmd.add(main);
        Log.step("Native", m.name() + " with " + graal.id() + Log.dim(" (this takes a while)"));
        int code = dev.jpm.util.Exec.inherit(m.dir, cmd, Map.of());
        if (code != 0) throw new JpmException("native-image failed for " + m.name() + " (exit " + code + ")", code);
        Path built = Jdk.windowsExe(out);
        Log.step("Built", m.dir.relativize(built).toString());
        return built;
    }

    public List<Path> classpath(Project.Module m, Cp kind) {
        List<Path> out = new ArrayList<>();
        for (String id : classpathIds(m, kind)) out.addAll(paths(id, m));
        return Compiler.distinct(out);
    }

    /** The packages on one of a module's classpaths, in classpath order, without fetching or building any. */
    public List<String> classpathIds(Project.Module m, Cp kind) {
        List<Graph.Edge> runtime = Resolver.rootEdges(m, Manifest.Scope.RUNTIME);
        List<Graph.Edge> dev = Resolver.rootEdges(m, Manifest.Scope.DEV);
        List<Graph.Edge> test = Resolver.rootEdges(m, Manifest.Scope.TEST);
        List<String> ids = new ArrayList<>();
        switch (kind) {
            case COMPILE -> {
                ids.addAll(graph.closure(runtime, Graph.Walk.COMPILE));
                ids.addAll(graph.closure(dev, Graph.Walk.ALL));
            }
            case PROCESSOR -> ids.addAll(graph.closure(dev, Graph.Walk.ALL));
            case RUNTIME -> ids.addAll(graph.closure(runtime, Graph.Walk.RUNTIME));
            case TEST_COMPILE -> {
                ids.addAll(graph.closure(runtime, Graph.Walk.COMPILE));
                ids.addAll(graph.closure(dev, Graph.Walk.ALL));
                ids.addAll(graph.closure(test, Graph.Walk.COMPILE));
            }
            case TEST_RUNTIME -> {
                ids.addAll(graph.closure(runtime, Graph.Walk.RUNTIME));
                ids.addAll(graph.closure(test, Graph.Walk.RUNTIME));
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    /** Whether a manifest of the project patches this package. */
    public boolean isPatched(String id) {
        return patchOwner(id) != null;
    }

    /** Runtime dependency classpath without compiling the project's own modules (for scripts). */
    public List<Path> classpathNoBuild(Project.Module m) {
        List<Path> out = new ArrayList<>();
        for (String id : graph.closure(Resolver.rootEdges(m, Manifest.Scope.RUNTIME), Graph.Walk.RUNTIME)) {
            Graph.Pkg p = graph.get(id);
            if (p != null && p.kind == Graph.Kind.LOCAL) {
                Project.Module mod = project.module(id.substring("workspace:".length()));
                out.add(classesDir(mod));
            } else {
                out.addAll(paths(id, m));
            }
        }
        return Compiler.distinct(out);
    }

    /** Jars or directories for one package. */
    public List<Path> paths(String id, Project.Module consumer) {
        List<Path> cached = artifacts.get(id);
        if (cached != null) return cached;
        Graph.Pkg p = graph.get(id);
        if (p == null) throw new JpmException("Internal error: " + id + " is not in the dependency graph. Run `jpm install`.");
        List<Path> out = switch (p.kind) {
            case LOCAL -> {
                Project.Module mod = project.module(id.substring("workspace:".length()));
                compileMain(mod);
                List<Path> l = new ArrayList<>();
                l.add(classesDir(mod));
                yield l;
            }
            case MAVEN -> p.hasJar ? List.of(mavenJar(p, consumer)) : List.of();
            case GIT -> List.of(buildGit(p));
            case EXTENSION -> dev.jpm.resolve.ExtensionDeps.resolve(DepKey.parse(p.id), p.version).jars();
            case GRADLE -> GradleDist.jars(p.artifact != null ? p.artifact : GradleDist.require(p.version, p.sha256).home());
        };
        artifacts.put(id, out);
        return out;
    }

    public static Path classesDir(Project.Module m) {
        return m.cacheDir().resolve("classes");
    }

    public static Path testClassesDir(Project.Module m) {
        return m.cacheDir().resolve("test-classes");
    }

    public Path jarPath(Project.Module m) {
        return m.dir.resolve("dist").resolve(m.name() + "-" + m.version() + ".jar");
    }

    /** Compiles a module's main sources (and its workspace dependencies first). */
    public boolean compileMain(Project.Module m) {
        Boolean done = compiledModules.get(m.name());
        if (done != null) return done;
        if (!building.add(m.id())) throw new JpmException("Cycle between workspace modules at " + m.name());
        try {
            List<Path> cp = classpath(m, Cp.COMPILE);
            List<Path> proc = m.manifest.deps(Manifest.Scope.DEV).isEmpty() ? List.of() : classpath(m, Cp.PROCESSOR);
            Jdk jdk = jdk(m.manifest);
            int release = release(m.manifest, jdk);
            List<Path> roots = new ArrayList<>();
            roots.addAll(m.srcDirs());
            Path gen = generateSources(m);
            if (gen != null) roots.add(gen);
            Path kotlinOut = m.cacheDir().resolve("kotlin-classes");
            boolean kotlinRan = KotlinCompiler.compile(this, new KotlinCompiler.Request(m.name(), m.name(), m.manifest.kotlin, jdk, release,
                    roots, m.assetsDirs(), cp, List.of(), kotlinOut, m.cacheDir().resolve("kotlin-classes.key")));
            List<Path> javaCp = cp;
            String key = inputsKey(cp, proc);
            if (Files.isDirectory(kotlinOut)) {
                javaCp = new ArrayList<>(cp);
                javaCp.add(0, kotlinOut);
                key += treeKey(List.of(kotlinOut));
            }
            boolean ran = Compiler.compile(new Compiler.Request(m.name(), jdk, release, roots, javaCp, proc,
                    classesDir(m), m.cacheDir().resolve("classes.state.json"), m.manifest.javacArgs, key,
                    m.assetsDirs()));
            ran |= kotlinRan;
            Resources.sync(Files.isDirectory(kotlinOut) ? List.of(kotlinOut) : List.of(), classesDir(m),
                    m.cacheDir().resolve("kotlin-classes.list"));
            Resources.sync(m.assetsDirs(), classesDir(m), m.cacheDir().resolve("resources.list"),
                    m.manifest.filter, filterValues(m));
            if (ran) Log.step("Compiled", m.name() + " " + m.version() + Log.dim(" (" + jdk.id() + ", release " + release + ")"));
            compiledModules.put(m.name(), ran);
            return ran;
        } finally {
            building.remove(m.id());
        }
    }

    /** Compiles and packages a module into {@code dist/}. */
    public Path jar(Project.Module m) {
        compileMain(m);
        Path jar = jarPath(m);
        Map<String, String> attrs = new LinkedHashMap<>();
        if (!m.manifest.mains.isEmpty()) {
            attrs.put("Main-Class", m.manifest.mains.getOrDefault("main", m.manifest.mains.values().iterator().next()));
        }
        attrs.put("Implementation-Title", m.name());
        attrs.put("Implementation-Version", m.version());
        List<Path> dirs = new ArrayList<>();
        dirs.add(classesDir(m));
        Path stamp = m.cacheDir().resolve("jar.key");
        String key = treeKey(dirs) + attrs;
        if (Files.exists(jar) && Files.exists(stamp) && Fs.read(stamp).equals(key)) return jar;
        Fs.mkdirs(jar.getParent());
        Jars.write(jar, dirs, attrs);
        Fs.write(stamp, key);
        Log.step("Packaged", m.dir.relativize(jar).toString());
        return jar;
    }

    /**
     * What {@code "filter"}ed resources can use, later sources winning: the module's NAME and VERSION, the fields of
     * the workspace root's project.jsonc, then the module's (dotted for nested ones, e.g. {@code extensions.publish.repo};
     * {@code version} is the module's version), the {@code *.properties} files next to the root's project.jsonc, then
     * those next to the module's, and last the module's define values.
     */
    private Map<String, String> filterValues(Project.Module m) {
        if (m.manifest.filter.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        out.put("NAME", m.name());
        out.put("VERSION", m.version());
        boolean workspace = !m.dir.equals(m.project.dir);
        if (workspace) Resources.flatten(m.project.root.root, "", out);
        Resources.flatten(m.manifest.root, "", out);
        out.put("version", m.version());
        if (workspace) out.putAll(Resources.properties(m.project.dir));
        out.putAll(Resources.properties(m.dir));
        for (Defines.Value v : Defines.resolve(m.manifest, null, null, null, env)) out.put(v.name(), String.valueOf(v.value()));
        return out;
    }

    /** A module's define values, resolved from the manifest, the environment and .env files. */
    public List<Defines.Value> defineValues(Project.Module m) {
        return Defines.resolve(m.manifest, null, null, null, env);
    }

    /**
     * Writes the module's generated sources (the defines class) and returns their folder, or null if the
     * module has none.
     */
    public Path generateSources(Project.Module m) {
        List<Defines.Value> defs = defineValues(m);
        Path gen = m.cacheDir().resolve("generated");
        if (defs.isEmpty() && m.manifest.defineClass == null) {
            Fs.deleteTree(gen);
            return null;
        }
        String fqcn = Defines.className(m.manifest);
        Fs.writeIfChanged(gen.resolve(fqcn.replace('.', '/') + ".java"), Defines.source(fqcn, defs, m.name(), m.version()));
        return gen;
    }

    /** Compiles test sources. Returns false if the module has no tests. */
    public boolean compileTests(Project.Module m) {
        List<Path> testDirs = m.testDirs();
        List<Path> excludes = List.of(m.testResourcesDir());
        boolean anyJava = false;
        for (Path d : testDirs) anyJava |= Fs.files(d, ".java").stream().anyMatch(f -> !f.startsWith(m.testResourcesDir()));
        if (!anyJava && KotlinCompiler.sources(testDirs, excludes).isEmpty()) return false;
        compileMain(m);
        List<Path> cp = new ArrayList<>();
        cp.add(classesDir(m));
        cp.addAll(testFixtures(m));
        cp.addAll(classpath(m, Cp.TEST_COMPILE));
        cp = Compiler.distinct(cp);
        List<Path> proc = m.manifest.deps(Manifest.Scope.DEV).isEmpty() ? List.of() : classpath(m, Cp.PROCESSOR);
        Jdk jdk = jdk(m.manifest);
        int release = release(m.manifest, jdk);
        Path kotlinOut = m.cacheDir().resolve("test-kotlin-classes");
        boolean kotlinRan = KotlinCompiler.compile(this, new KotlinCompiler.Request(m.name() + " tests", m.name() + "_test",
                m.manifest.kotlin, jdk, release, testDirs, excludes, cp, List.of(classesDir(m)), kotlinOut,
                m.cacheDir().resolve("test-kotlin-classes.key")));
        List<Path> javaCp = cp;
        String key = inputsKey(cp, proc) + treeKey(List.of(classesDir(m)));
        if (Files.isDirectory(kotlinOut)) {
            javaCp = new ArrayList<>(cp);
            javaCp.add(0, kotlinOut);
            key += treeKey(List.of(kotlinOut));
        }
        boolean ran = Compiler.compile(new Compiler.Request(m.name() + " tests", jdk, release, testDirs,
                javaCp, proc, testClassesDir(m), m.cacheDir().resolve("test-classes.state.json"), m.manifest.javacArgs,
                key, excludes));
        ran |= kotlinRan;
        Resources.sync(Files.isDirectory(kotlinOut) ? List.of(kotlinOut) : List.of(), testClassesDir(m),
                m.cacheDir().resolve("test-kotlin-classes.list"));
        Resources.sync(List.of(m.testResourcesDir()), testClassesDir(m), m.cacheDir().resolve("test-resources.list"));
        if (ran) Log.step("Compiled", m.name() + " tests");
        return true;
    }

    /**
     * Compiled tests of the workspace modules this one's tests depend on with {@code "tests": true}: their base
     * classes and helpers, on this module's test classpaths (their tests are not run here).
     */
    public List<Path> testFixtures(Project.Module m) {
        List<Path> out = new ArrayList<>();
        for (Manifest.Dependency d : m.manifest.deps(Manifest.Scope.TEST)) {
            if (!d.tests()) continue;
            Project.Module other = project.module(d.key().artifact());
            if (other == null) throw new JpmException(m.manifest.file + ":" + d.line() + ": no workspace module " + d.key().artifact());
            if (other == m) throw new JpmException(m.manifest.file + ":" + d.line() + ": a module cannot depend on its own tests");
            if (!building.add(other.id() + " tests")) throw new JpmException("Cycle between the tests of workspace modules at " + other.name());
            try {
                if (compileTests(other)) {
                    out.add(testClassesDir(other));
                    out.addAll(testFixtures(other));
                }
            } finally {
                building.remove(other.id() + " tests");
            }
        }
        return out;
    }

    public List<Path> testRuntimeClasspath(Project.Module m) {
        List<Path> cp = new ArrayList<>();
        cp.add(testClassesDir(m));
        cp.add(classesDir(m));
        cp.addAll(testFixtures(m));
        Path metadata = pluginUnderTestMetadata(m);
        if (metadata != null) cp.add(metadata);
        cp.addAll(classpath(m, Cp.TEST_RUNTIME));
        return Compiler.distinct(cp);
    }

    /**
     * For a Gradle plugin (a module with {@code gradle:api}): a folder with {@code plugin-under-test-metadata.properties},
     * the plugin's classes and runtime classpath, which TestKit's {@code GradleRunner.withPluginClasspath()} reads, as
     * Gradle's {@code java-gradle-plugin} writes it. Null for other modules.
     */
    public Path pluginUnderTestMetadata(Project.Module m) {
        boolean plugin = m.manifest.dependencies.stream().anyMatch(d -> d.key().isGradle());
        Path dir = m.cacheDir().resolve("plugin-under-test-metadata");
        if (!plugin) {
            Fs.deleteTree(dir);
            return null;
        }
        List<Path> cp = new ArrayList<>();
        cp.add(classesDir(m));
        cp.addAll(classpath(m, Cp.RUNTIME));
        java.util.Properties props = new java.util.Properties();
        props.setProperty("implementation-classpath", Compiler.join(Compiler.distinct(cp)));
        java.io.StringWriter w = new java.io.StringWriter();
        try {
            props.store(w, null);
        } catch (IOException e) {
            throw new JpmException(e.getMessage());
        }
        String text = w.toString().lines().filter(l -> !l.startsWith("#")).collect(java.util.stream.Collectors.joining("\n")) + "\n";
        Fs.writeIfChanged(dir.resolve("plugin-under-test-metadata.properties"), text);
        return dir;
    }

    public List<Path> runtimeClasspath(Project.Module m) {
        List<Path> cp = new ArrayList<>();
        cp.add(classesDir(m));
        cp.addAll(classpath(m, Cp.RUNTIME));
        return Compiler.distinct(cp);
    }

    private static int release(Manifest m, Jdk jdk) {
        Integer t = m.targetMajor();
        int release = t == null ? jdk.major() : t;
        if (release > jdk.major()) {
            throw new JpmException(m.file + ": target " + release + " needs JDK " + release + " or newer, but " + jdk.id() + " was selected");
        }
        return release;
    }

    /** Cache key part for a classpath: jar checksums by path, directories by their content stamp. */
    private String inputsKey(List<Path> cp, List<Path> proc) {
        Hash h = new Hash();
        for (Path p : cp) h.add("cp " + p + " " + stamp(p));
        for (Path p : proc) h.add("proc " + p + " " + stamp(p));
        return h.hex();
    }

    private static String stamp(Path p) {
        try {
            if (Files.isDirectory(p)) return "dir";
            return Files.size(p) + ":" + Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return "missing";
        }
    }

    /** Content key of directory trees: relative names, sizes and modification times. */
    static String treeKey(List<Path> dirs) {
        Hash h = new Hash();
        for (Path d : dirs) {
            for (Path f : Fs.files(d, null)) {
                try {
                    h.add(Fs.slash(d.relativize(f)) + " " + Files.size(f) + " " + Files.getLastModifiedTime(f).toMillis());
                } catch (IOException e) {
                    h.add(f.toString());
                }
            }
        }
        return h.hex();
    }

    /** True for internal implementation modules, which a bundle loads in their own isolated class loader. */
    private boolean isolatedPkg(Graph.Pkg p) {
        if (p.kind == Graph.Kind.GIT) return !GitModule.locate(DepKey.parse(p.id), p.commit).isPublic();
        if (p.kind == Graph.Kind.LOCAL) {
            Project.Module mod = project.module(p.id.substring("workspace:".length()));
            return mod != null && !mod.isPublic();
        }
        return false;
    }

    /** Jars of a package, packaging the project's own modules as jars. */
    private List<Path> bundleJars(String id, Project.Module consumer) {
        Graph.Pkg p = graph.get(id);
        if (p != null && p.kind == Graph.Kind.LOCAL) return List.of(jar(project.module(id.substring("workspace:".length()))));
        return paths(id, consumer);
    }

    /**
     * Builds {@code dist/<name>-<version>-bundle.jar} as one flat jar: the module's classes and resources with
     * those of every runtime dependency merged in (dev dependencies, which the platform provides, stay out).
     * This is what plugins for platforms that load jars themselves (Paper, Velocity, ...) need, and what a
     * module without a main bundles to.
     */
    public Path flatBundle(Project.Module m) {
        Path appJar = jar(m);
        List<Path> inputs = new ArrayList<>();
        inputs.add(appJar);
        for (String id : graph.closure(Resolver.rootEdges(m, Manifest.Scope.RUNTIME), Graph.Walk.RUNTIME)) inputs.addAll(bundleJars(id, m));
        inputs = Compiler.distinct(inputs);
        Map<String, String> attrs = new LinkedHashMap<>();
        if (!m.manifest.mains.isEmpty()) attrs.put("Main-Class", m.manifest.mains.getOrDefault("main", m.manifest.mains.values().iterator().next()));
        attrs.put("Implementation-Title", m.name());
        attrs.put("Implementation-Version", m.version());
        Path out = m.dir.resolve("dist").resolve(m.name() + "-" + m.version() + "-bundle.jar");
        Hash key = new Hash().add("flat-v1").add(attrs.toString()).add(new java.util.TreeMap<>(m.manifest.relocate).toString());
        for (Path in : inputs) key.add(in.getFileName().toString()).add(Files.isDirectory(in) ? treeKey(List.of(in)) : Hash.sha256(in));
        Path stamp = m.cacheDir().resolve("bundle.key");
        if (Files.exists(out) && Files.exists(stamp) && Fs.read(stamp).equals(key.hex())) {
            Log.step("Up to date", m.dir.relativize(out) + Log.dim(" (" + (inputs.size() - 1) + " dependencies merged)"));
            return out;
        }
        int entries = Jars.merge(out, inputs, attrs, new Relocator(m.manifest.relocate));
        Fs.write(stamp, key.hex());
        Log.step("Bundled", m.dir.relativize(out) + Log.dim(" (flat: " + (inputs.size() - 1) + " dependencies merged, " + entries + " files)"));
        return out;
    }

    /**
     * Builds {@code dist/<name>-<version>-bundle.jar}: the app and its dependencies as nested jars, with each
     * internal implementation module (and the dependencies only it uses) in its own child-first class loader.
     * No shading; runs with {@code java -jar}.
     */
    public Path bundle(Project.Module m) {
        Map<String, String> mains = m.manifest.mains;
        if (mains.isEmpty()) throw new JpmException(m.manifest.file + " has no \"main\", so it cannot be bundled");
        String main = mains.getOrDefault("main", mains.values().iterator().next());
        Path appJar = jar(m);
        List<Graph.Edge> roots = Resolver.rootEdges(m, Manifest.Scope.RUNTIME);
        Set<String> isolated = new java.util.TreeSet<>();
        for (String id : graph.closure(roots, Graph.Walk.RUNTIME)) {
            Graph.Pkg p = graph.get(id);
            if (p != null && isolatedPkg(p)) isolated.add(id);
        }
        List<String> shared = graph.closure(roots, Graph.Walk.RUNTIME, isolated);

        Path stage = m.cacheDir().resolve("bundle");
        Fs.deleteTree(stage);
        Path lib = stage.resolve("META-INF/jpm/lib");
        Hash id = new Hash().add(BundleLauncher.LAUNCHER).add(main);
        List<String> sharedNames = new ArrayList<>();
        int n = 0;
        List<Path> sharedJars = new ArrayList<>();
        sharedJars.add(appJar);
        for (String sid : shared) sharedJars.addAll(bundleJars(sid, m));
        for (Path j : Compiler.distinct(sharedJars)) {
            String name = "shared/" + String.format("%03d-", n++) + j.getFileName();
            copy(j, lib.resolve(name));
            sharedNames.add("lib/" + name);
            id.add(name).add(Hash.sha256(j));
        }
        java.util.Properties props = new java.util.Properties();
        props.setProperty("main", main);
        props.setProperty("shared", String.join(",", sharedNames));
        List<String> isoNames = new ArrayList<>();
        Set<String> sharedSet = new java.util.HashSet<>(shared);
        for (String iso : isolated) {
            String name = Patches.safeName(iso);
            isoNames.add(name);
            Set<String> otherIsolated = new java.util.TreeSet<>(isolated);
            otherIsolated.remove(iso);
            List<String> own = new ArrayList<>();
            for (String x : graph.closure(List.of(new Graph.Edge(iso, false, Set.of())), Graph.Walk.RUNTIME, otherIsolated)) {
                if (!sharedSet.contains(x)) own.add(x);
            }
            Set<String> api = new java.util.TreeSet<>();
            for (Graph.Pkg p : graph.pkgs.values()) {
                if (p.deps.containsKey(iso) && !isolated.contains(p.id)) {
                    for (Path j : bundleJars(p.id, m)) api.addAll(packagesOf(j));
                }
            }
            List<String> jars = new ArrayList<>();
            int k = 0;
            for (String x : own) {
                for (Path j : bundleJars(x, m)) {
                    String jn = name + "/" + String.format("%03d-", k++) + j.getFileName();
                    copy(j, lib.resolve(jn));
                    jars.add("lib/" + jn);
                    id.add(jn).add(Hash.sha256(j));
                }
            }
            props.setProperty("isolated." + name + ".jars", String.join(",", jars));
            props.setProperty("isolated." + name + ".api", String.join(",", api));
            id.add(name).add(String.join(",", api));
        }
        props.setProperty("isolated", String.join(",", isoNames));
        props.setProperty("id", m.name() + "-" + m.version() + "-" + id.hex().substring(0, 16));
        StringBuilder text = new StringBuilder("# Generated by jpm: layout of this bundle\n");
        for (String k : new java.util.TreeSet<>(props.stringPropertyNames())) text.append(k).append('=').append(props.getProperty(k)).append('\n');
        Fs.write(stage.resolve("META-INF/jpm/bundle.properties"), text.toString());

        Jdk jdk = jdk(m.manifest);
        int release = Math.max(8, release(m.manifest, jdk));
        Path launcherSrc = m.cacheDir().resolve("bundle-launcher/src");
        Fs.writeIfChanged(launcherSrc.resolve(BundleLauncher.PACKAGE.replace('.', '/') + "/Launcher.java"), BundleLauncher.LAUNCHER);
        Path launcherClasses = m.cacheDir().resolve("bundle-launcher/classes");
        Compiler.compile(new Compiler.Request("bundle launcher", jdk, release, List.of(launcherSrc), List.of(), List.of(),
                launcherClasses, m.cacheDir().resolve("bundle-launcher/state.json"), List.of("-nowarn"), "launcher"));

        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("Main-Class", BundleLauncher.PACKAGE + ".Launcher");
        attrs.put("Implementation-Title", m.name());
        attrs.put("Implementation-Version", m.version());
        Path out = m.dir.resolve("dist").resolve(m.name() + "-" + m.version() + "-bundle.jar");
        Jars.write(out, List.of(stage, launcherClasses), attrs);
        Log.step("Bundled", m.dir.relativize(out) + Log.dim(" (" + sharedNames.size() + " shared jars"
                + (isolated.isEmpty() ? "" : ", isolated: " + String.join(", ", isolated)) + ")"));
        return out;
    }

    private static void copy(Path from, Path to) {
        try {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new JpmException("Could not copy " + from + ": " + e.getMessage());
        }
    }

    /** Java packages that have classes in a jar. */
    static Set<String> packagesOf(Path jar) {
        Set<String> out = new java.util.TreeSet<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                int slash = n.lastIndexOf('/');
                out.add(slash < 0 ? "" : n.substring(0, slash).replace('/', '.'));
            }
        } catch (IOException e) {
            throw new JpmException("Could not read " + jar + ": " + e.getMessage());
        }
        return out;
    }

    private Path mavenJar(Graph.Pkg p, Project.Module consumer) {
        Path jar = p.artifact != null ? p.artifact : repo.require(p.group(), p.name(), p.version, p.classifier(), "jar");
        Manifest owner = patchOwner(p.id);
        if (owner == null) return jar;
        Path patch = owner.dir().resolve(owner.patches.get(p.id));
        if (!Files.exists(patch)) throw new JpmException(owner.file + ": patch file " + owner.patches.get(p.id) + " for " + p.id + " does not exist");
        Manifest.PatchOptions options = owner.patchOptions.get(p.id);
        List<Path> patchDeps = new ArrayList<>();
        if (options != null && !options.dependencies().isEmpty()) {
            List<Graph.Edge> roots = new ArrayList<>();
            for (Manifest.Dependency d : options.dependencies()) roots.add(new Graph.Edge(Resolver.idOf(d.key()), false, d.exclusions()));
            for (String id : graph.closure(roots, Graph.Walk.ALL)) {
                if (graph.get(id) == null) throw new JpmException("Patch dependency " + id + " of " + p.id + " is not resolved. Run `jpm install`.");
                patchDeps.addAll(paths(id, consumer));
            }
        }
        Hash keyHash = new Hash().add(Hash.sha256(jar)).add(Hash.sha256(Fs.read(patch)));
        if (options != null) {
            keyHash.add("release " + options.release());
            for (Path d : patchDeps) keyHash.add(Files.isDirectory(d) ? d.toString() : Hash.sha256(d));
        }
        String key = keyHash.hex().substring(0, 24);
        Path out = Fs.home().resolve("patched").resolve(key).resolve(p.name() + "-" + p.version + (p.classifier() != null ? "-" + p.classifier() : "") + "-patched.jar");
        if (Files.exists(out)) return out;
        Log.step("Patching", p.id + " " + p.version);
        Path sources = repo.fetch(p.group(), p.name(), p.version, "sources", "jar");
        if (sources == null) throw new JpmException(p.id + " " + p.version + " has no sources jar, so the patch cannot be applied");
        Path work = out.getParent().resolve("src");
        Fs.deleteTree(work);
        Jars.extract(sources, work);
        Patches.apply(work, patch, p.id, p.version);
        Set<String> touched = Patches.touched(patch);
        List<Path> javaFiles = new ArrayList<>();
        Path overlay = out.getParent().resolve("overlay");
        Fs.deleteTree(overlay);
        Fs.mkdirs(overlay);
        Set<String> dropPrefixes = new LinkedHashSet<>();
        for (String t : touched) {
            Path f = work.resolve(t);
            if (t.endsWith(".java")) {
                if (Files.exists(f)) javaFiles.add(f);
                dropPrefixes.add(t.substring(0, t.length() - 5));
            } else if (Files.exists(f)) {
                Fs.write(overlay.resolve(t), "");
                try {
                    Files.copy(f, overlay.resolve(t), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new JpmException(e.getMessage());
                }
            }
        }
        if (!javaFiles.isEmpty()) {
            List<Graph.Edge> self = List.of(new Graph.Edge(p.id, false, Set.of()));
            List<Path> deps = new ArrayList<>();
            for (String id : graph.closure(self, Graph.Walk.ALL)) if (!id.equals(p.id)) deps.addAll(paths(id, consumer));
            deps.addAll(patchDeps);
            deps = Compiler.distinct(deps);
            Jdk jdk = consumer != null ? jdk(consumer.manifest) : Toolchain.require(JavaSpec.any());
            int own = classRelease(jar);
            int release = Math.max(8, Math.min(jdk.major(), own));
            if (options != null && options.release() != null) {
                if (options.release() < own) {
                    throw new JpmException(owner.file + ": patches." + p.id + ".release is " + options.release() + ", lower than "
                            + p.id + "'s own bytecode level (" + own + ")");
                }
                if (options.release() > jdk.major()) {
                    throw new JpmException(owner.file + ": patches." + p.id + ".release is " + options.release() + ", newer than the JDK "
                            + jdk.id() + "; set \"java\" to " + options.release() + " or later");
                }
                release = options.release();
            }
            Path srcRoot = out.getParent().resolve("changed");
            Fs.deleteTree(srcRoot);
            for (Path f : javaFiles) {
                Path rel = work.relativize(f);
                Path target = srcRoot.resolve(rel.toString());
                Fs.mkdirs(target.getParent());
                try {
                    Files.copy(f, target);
                } catch (IOException e) {
                    throw new JpmException(e.getMessage());
                }
            }
            String module = jdk.major() >= 9 ? Modules.descriptorName(jar) : null;
            List<Path> cp = new ArrayList<>();
            List<String> extra = new ArrayList<>(List.of("-nowarn"));
            if (module != null) {
                extra.add("--patch-module");
                extra.add(module + "=" + srcRoot + java.io.File.pathSeparator + jar);
                if (Files.exists(srcRoot.resolve("module-info.java"))) {
                    cp.addAll(deps);
                } else {
                    List<Path> mp = new ArrayList<>();
                    mp.add(jar);
                    mp.addAll(deps);
                    extra.add("--module-path");
                    extra.add(Compiler.join(mp));
                }
            } else {
                cp.add(jar);
                cp.addAll(deps);
            }
            try {
                Compiler.compile(new Compiler.Request("patch " + p.id, jdk, release, List.of(srcRoot), cp, List.of(), overlay,
                        out.getParent().resolve("state.json"), extra, key));
            } catch (JpmException e) {
                String hint = options == null || options.dependencies().isEmpty()
                        ? "\nIf the patched classes use other libraries, list them in the patch's \"dependencies\": "
                        + "\"patches\": { \"" + p.id + "\": { \"file\": \"" + owner.patches.get(p.id) + "\", \"dependencies\": { ... } } }"
                        : "";
                String rel = (options == null || options.release() == null) && release < jdk.major()
                        ? "\nThe patched classes are compiled for Java " + release + " (" + p.id + "'s own level); for newer language"
                        + " features set the patch's \"release\"."
                        : "";
                throw new JpmException(e.getMessage() + hint + rel);
            }
        }
        Jars.rewrite(jar, out, name -> {
            for (String d : dropPrefixes) {
                if (name.equals(d + ".class") || (name.startsWith(d + "$") && name.endsWith(".class"))) return true;
            }
            return false;
        }, overlay);
        return out;
    }

    /** Bytecode level of the first class in a jar (e.g. 52 gives 8). */
    private static int classRelease(Path jar) {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
                try (InputStream in = zf.getInputStream(e)) {
                    byte[] h = in.readNBytes(8);
                    return ((h[6] & 0xff) << 8 | (h[7] & 0xff)) - 44;
                }
            }
        } catch (IOException ignored) {
        }
        return 8;
    }

    private Manifest patchOwner(String id) {
        for (Project.Module m : project.modules) if (m.manifest.patches.containsKey(id)) return m.manifest;
        if (project.root.patches.containsKey(id)) return project.root;
        return null;
    }

    private Path buildGit(Graph.Pkg p) {
        if (!building.add(p.id)) throw new JpmException("Dependency cycle through " + p.id);
        try {
            GitModule mod = GitModule.locate(DepKey.parse(p.id), p.commit);
            Path src = mod.checkout();

            Manifest owner = patchOwner(p.id);
            String patchHash = "";
            if (owner != null) {
                Path patch = owner.dir().resolve(owner.patches.get(p.id));
                patchHash = Hash.sha256(Fs.read(patch));
                Path patched = Fs.home().resolve("git-patched").resolve(Patches.safeName(p.id))
                        .resolve(p.commit.substring(0, 12) + "-" + patchHash.substring(0, 12));
                if (!Files.isDirectory(patched.resolve(".jpm-complete"))) {
                    Log.step("Patching", p.id + " " + p.version);
                    Patches.applyTo(src, patched, patch, p.id, p.version);
                    Fs.mkdirs(patched.resolve(".jpm-complete"));
                }
                src = patched;
            }

            List<Graph.Edge> edges = new ArrayList<>(p.deps.values());
            List<Path> cp = new ArrayList<>();
            for (String id : graph.closure(edges, Graph.Walk.COMPILE)) cp.addAll(paths(id, null));
            List<Path> proc = List.of();
            List<Manifest.Dependency> devDeps = mod.manifest().deps(Manifest.Scope.DEV);
            if (!devDeps.isEmpty()) {
                proc = buildToolClasspath(p, mod, devDeps);
                cp.addAll(proc);
            }
            cp = Compiler.distinct(cp);

            Manifest topConsumer = project.root.isWorkspaceRoot() ? null : project.root;
            Map<String, Object> provided = null;
            for (Project.Module m : project.modules) {
                Map<String, Object> v = m.manifest.libraryDefines.get(p.id);
                if (v != null) { provided = v; topConsumer = m.manifest; break; }
            }
            if (topConsumer == null && !project.modules.isEmpty()) topConsumer = project.modules.get(0).manifest;
            List<Defines.Value> defs = Defines.resolve(mod.manifest(), p.id, provided, topConsumer, env);

            Manifest consumerManifest = project.modules.get(0).manifest;
            Jdk jdk = jdk(consumerManifest);
            Integer t = mod.manifest().targetMajor();
            int release = t == null ? jdk.major() : t;
            if (release > jdk.major()) {
                throw new JpmException(p.id + " targets Java " + release + ", but this project builds with " + jdk.id());
            }

            Hash kh = new Hash().add(p.id).add(p.commit).add(patchHash).add(jdk.id()).add(Integer.toString(release));
            for (Defines.Value v : defs) kh.add(v.name() + "=" + v.value());
            for (Path c : cp) kh.add(c.toString()).add(stamp(c));
            String key = kh.hex().substring(0, 24);
            Path buildDir = Fs.home().resolve("builds").resolve(Patches.safeName(p.id)).resolve(key);
            String artifact = DepKey.parse(p.id).mavenArtifact();
            Path jar = buildDir.resolve(artifact + "-" + p.version + ".jar");
            if (Files.exists(jar)) return jar;

            Log.step("Building", p.id + " " + p.version + Log.dim(" (" + p.commit.substring(0, 7) + ")"));
            List<Path> roots = new ArrayList<>();
            roots.addAll(mod.manifest().sourceDirs(src));
            if (!defs.isEmpty() || mod.manifest().defineClass != null) {
                String fqcn = Defines.className(mod.manifest());
                Path gen = buildDir.resolve("generated");
                Fs.write(gen.resolve(fqcn.replace('.', '/') + ".java"), Defines.source(fqcn, defs, mod.manifest().name, p.version));
                roots.add(gen);
            }
            Path classes = buildDir.resolve("classes");
            Path kotlinOut = buildDir.resolve("kotlin-classes");
            KotlinCompiler.compile(this, new KotlinCompiler.Request(p.id, mod.manifest().name, mod.manifest().kotlin, jdk, release,
                    roots, mod.manifest().resourceDirs(src), cp, List.of(), kotlinOut, buildDir.resolve("kotlin-classes.key")));
            List<Path> javaCp = cp;
            if (Files.isDirectory(kotlinOut)) {
                javaCp = new ArrayList<>(cp);
                javaCp.add(0, kotlinOut);
            }
            Compiler.compile(new Compiler.Request(p.id, jdk, release, roots, javaCp, proc, classes,
                    buildDir.resolve("state.json"), mod.manifest().javacArgs, key,
                    mod.manifest().resourceDirs(src)));
            Resources.sync(Files.isDirectory(kotlinOut) ? List.of(kotlinOut) : List.of(), classes, buildDir.resolve("kotlin-classes.list"));
            Resources.sync(mod.manifest().resourceDirs(src), classes, buildDir.resolve("resources.list"));
            List<Path> dirs = new ArrayList<>();
            dirs.add(classes);
            Map<String, String> attrs = new LinkedHashMap<>();
            if (!mod.manifest().mains.isEmpty()) attrs.put("Main-Class", mod.manifest().mains.values().iterator().next());
            attrs.put("Implementation-Title", p.id);
            attrs.put("Implementation-Version", p.version);
            attrs.put("Git-Commit", p.commit);
            Jars.write(jar, dirs, attrs);
            return jar;
        } finally {
            building.remove(p.id);
        }
    }

    /**
     * Build-only dependencies of a Git dependency (its {@code dev} scope). They never reach this project's
     * classpath, so they are resolved on their own, preferring the versions in that repository's lockfile.
     */
    private List<Path> buildToolClasspath(Graph.Pkg p, GitModule mod, List<Manifest.Dependency> devDeps) {
        List<Resolver.Root> roots = new ArrayList<>();
        for (Manifest.Dependency d : devDeps) roots.add(new Resolver.Root(p.id, d.key(), d.spec(), d.exclusions()));
        Graph prev = null;
        String lockText = dev.jpm.git.GitRepo.of(DepKey.parse(p.id)).show(p.commit, Manifest.LOCK);
        if (lockText != null) {
            try {
                Path tmp = Files.createTempFile("jpm-lock", ".lock");
                Files.writeString(tmp, lockText);
                Lockfile lf = Lockfile.read(tmp);
                Files.deleteIfExists(tmp);
                if (lf != null) prev = lf.graph;
            } catch (IOException | JpmException e) {
                Log.debug("ignoring lockfile of " + p.id + ": " + e.getMessage());
            }
        }
        return resolveTool(roots, prev, Graph.Walk.ALL);
    }

    /** Resolves and fetches a standalone set of dependencies, returning their jars as {@code walk} reaches them. */
    public List<Path> resolveTool(List<Resolver.Root> roots, Graph prev, Graph.Walk walk) {
        Graph g = new Resolver(roots, repo, prev).resolve();
        Install.fetch(g, repo);
        List<Graph.Edge> edges = new ArrayList<>();
        for (Resolver.Root r : roots) edges.add(new Graph.Edge(Resolver.idOf(r.key()), false, Set.of()));
        Builder sub = new Builder(project, g, repo, Env.Mode.PRODUCTION);
        List<Path> out = new ArrayList<>();
        for (String id : g.closure(edges, walk)) out.addAll(sub.paths(id, null));
        return out;
    }
}
