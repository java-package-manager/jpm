package dev.jpm;

import dev.jpm.api.json.Jsonc;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** The parsed {@code project.jsonc}. */
public final class Manifest {
    public static final String FILE = "project.jsonc";
    public static final String LOCK = "project.lock";
    /** The JSON Schema of project.jsonc, src/assets/dev/jpm/project.schema.json, as attached to every release. */
    public static final String SCHEMA_URL = "https://github.com/java-package-manager/jpm/releases/latest/download/project.schema.json";
    /** Every top-level field jpm reads. The schema describes the same set (SchemaTest checks). */
    public static final Set<String> FIELDS = Set.of("name", "version", "group", "java", "target", "main", "runtime", "dev", "test",
            "define", "scripts", "hooks", "patches", "workspace", "visibility", "isolated", "repositories", "defineClass",
            "javacArgs", "nativeArgs", "testJvmArgs", "kotlin", "exclude", "layout", "sources", "resources", "filter", "relocate", "package", "description", "license", "url", "extensions", "$schema");

    public enum Scope {
        RUNTIME("runtime"), DEV("dev"), TEST("test");
        public final String key;
        Scope(String key) { this.key = key; }
    }

    public enum DefineType { STRING, INT, BOOLEAN }

    /** A {@code define} entry. {@code value}/{@code env}/{@code defaultValue} may be null. */
    public record Define(String name, DefineType type, Object value, String env, Object defaultValue, int line) {
        public boolean hasSource() { return value != null || env != null; }
    }

    /**
     * A declared dependency. Written as a version string, or as an object
     * {@code {"version": "...", "exclude": [...], "runtimeOnly": true}}. {@code api}: a dev dependency that
     * consumers compile against too (Gradle's compileOnlyApi); never at run time.
     */
    /**
     * Options of a patch: {@code dependencies} are compile-only for the patched classes (resolved with the
     * project, never on anyone's runtime classpath); {@code release} compiles them for a newer Java than
     * the library's own bytecode level.
     */
    public record PatchOptions(List<Dependency> dependencies, Integer release) {}

    /**
     * {@code tests}: a {@code test} dependency on a workspace module that brings that module's compiled tests too
     * (Gradle's test fixtures), so tests can share base classes and helpers.
     */
    public record Dependency(DepKey key, String spec, Scope scope, int line, Set<String> exclusions, boolean runtimeOnly, boolean api,
                             boolean tests) {
        public Dependency(DepKey key, String spec, Scope scope, int line) {
            this(key, spec, scope, line, Set.of(), false, false, false);
        }

        public Dependency(DepKey key, String spec, Scope scope, int line, Set<String> exclusions, boolean runtimeOnly) {
            this(key, spec, scope, line, exclusions, runtimeOnly, false, false);
        }

        public Dependency(DepKey key, String spec, Scope scope, int line, Set<String> exclusions, boolean runtimeOnly, boolean api) {
            this(key, spec, scope, line, exclusions, runtimeOnly, api, false);
        }
    }

    /**
     * {@code "kotlin"}: the module has Kotlin sources, compiled with this Kotlin version. {@code plugins} are compiler
     * plugins as {@code group:artifact} (version: the Kotlin version) or {@code group:artifact:version}, or the short
     * name of one of Kotlin's own ({@code "assignment"} is {@code org.jetbrains.kotlin:kotlin-assignment-compiler-plugin-embeddable});
     * {@code args} go to kotlinc, {@code -J} entries to the JVM running it.
     */
    public record Kotlin(String version, List<String> plugins, List<String> args) {
        /** Kotlin's own compiler plugins, by the short names "plugins" accepts. */
        public static final Set<String> BUILT_IN = Set.of("assignment", "sam-with-receiver", "allopen", "noarg", "serialization",
                "lombok", "power-assert", "parcelize", "atomicfu", "js-plain-objects", "compose");

        /** Maven coordinates (group:artifact:version) of each plugin. */
        public List<String> pluginCoordinates() {
            List<String> out = new ArrayList<>();
            for (String p : plugins) {
                if (!p.contains(":")) out.add("org.jetbrains.kotlin:kotlin-" + p + "-compiler-plugin-embeddable:" + version);
                else out.add(p.split(":").length == 2 ? p + ":" + version : p);
            }
            return out;
        }
    }

    public final Path file;
    public final String source;
    public final Jsonc.Node root;

    public final String name;
    public final String version;
    public final String group;
    public final String java;
    public final String target;
    /** Entry points: name to class. A single {@code main} string is stored under "main". */
    public final Map<String, String> mains;
    public final List<Dependency> dependencies;
    public final Map<String, Define> defines;
    /** Values this project provides for defines declared by its dependencies: dep key to (name to value). */
    public final Map<String, Map<String, Object>> libraryDefines;
    public final Map<String, String> scripts;
    public final Map<String, String> hooks;
    /** Patched dependency to its patch file. */
    public final Map<String, String> patches;
    /** Patched dependency to the options of its patch (a patch given as an object); absent for a plain path. */
    public final Map<String, PatchOptions> patchOptions;
    public final List<String> workspace;
    public final String visibility;
    /** Resolved in its own graph, so its dependencies do not move the other modules' versions. */
    public final boolean isolated;
    public final List<String> repositories;
    public final String defineClass;
    public final List<String> javacArgs;
    public final List<String> nativeArgs;
    /** Options for the JVM that runs the tests, e.g. {@code --add-opens} or {@code -Xmx2g}. */
    public final List<String> testJvmArgs;
    /**
     * Packages left out of what this module's {@code runtime} and {@code dev} dependencies bring in, like an exclusion on
     * each of them (Gradle's {@code configurations.all { exclude(...) }} for what the platform provides). {@code test}
     * dependencies are not affected. A workspace module adds its own to the root's.
     */
    public final Set<String> exclude;
    /** Kotlin settings, or null for a module without Kotlin. */
    public final Kotlin kotlin;
    public final Layout layout;
    /** Resources (globs relative to the resource folder) copied with ${NAME} replaced by define values. */
    public final List<String> filter;
    /** Source folders relative to the module, replacing the layout's one; empty for the layout's. */
    public final List<String> sources;
    /** Resource folders relative to the module, replacing the layout's one; empty for the layout's. */
    public final List<String> resources;
    /** Packages moved to new names in flat bundles (Shadow's relocation): package to new package. */
    public final Map<String, String> relocate;
    /** What `jpm build` produces: "jar" (default) or "bundle" (jar-in-jar with isolated modules). */
    public final String packaging;
    public final String description;
    public final String license;
    public final String url;
    /** Settings for extensions, by extension name (e.g. "publish"); jpm passes them through untouched. */
    public final Map<String, Object> extensions;

    /**
     * Fields a workspace module takes from the workspace root when it does not set them itself.
     * {@code javacArgs}, {@code nativeArgs} and {@code testJvmArgs} are the root's followed by the module's own.
     */
    public static final List<String> INHERITED = List.of("java", "target", "group", "license", "url", "layout", "javacArgs", "nativeArgs", "testJvmArgs", "kotlin", "exclude");

    private Manifest(Path file, String source) {
        this(file, source, null);
    }

    /** {@code parent}: the workspace root this module belongs to, whose settings it inherits; or null. */
    private Manifest(Path file, String source, Manifest parent) {
        this.file = file;
        this.source = source;
        try {
            this.root = Jsonc.parseNode(source);
        } catch (Jsonc.ParseException e) {
            throw JpmException.at(file, e.line, e.getMessage().replaceFirst(" at line \\d+, column \\d+$", "")
                    + " (column " + e.column + ")");
        }
        if (!root.isObject()) throw err(root, "the manifest must be a JSON object");

        Set<String> known = FIELDS;
        for (Jsonc.Member m : root.members.values()) {
            if (!known.contains(m.key)) {
                JpmException e = JpmException.at(file, line(m.keyStart), "unknown field \"" + m.key + "\"");
                String near = dev.jpm.util.Strings.closest(m.key, known);
                throw near == null ? e : e.withHint("did you mean \"" + near + "\"?");
            }
        }

        this.name = str("name", true);
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) throw err(root.get("name"), "invalid name \"" + name + "\"");
        this.version = str("version", false);
        this.group = inherit(str("group", false), parent == null ? null : parent.group);
        this.java = inherit(str("java", false), parent == null ? null : parent.java);
        this.target = inherit(str("target", false), parent == null ? null : parent.target);
        this.visibility = str("visibility", false) == null ? "public" : str("visibility", false);
        if (!visibility.equals("public") && !visibility.equals("internal")) {
            throw err(root.get("visibility"), "visibility must be \"public\" or \"internal\"");
        }
        Jsonc.Node iso = root.get("isolated");
        if (iso != null && !(iso.value instanceof Boolean)) throw err(iso, "isolated must be true or false");
        this.isolated = iso != null && Boolean.TRUE.equals(iso.value);
        this.defineClass = str("defineClass", false);

        Map<String, String> m = new LinkedHashMap<>();
        Jsonc.Node mainNode = root.get("main");
        if (mainNode != null) {
            if (mainNode.isString()) m.put("main", (String) mainNode.value);
            else if (mainNode.isObject()) {
                for (Jsonc.Member e : mainNode.members.values()) {
                    if (!e.value.isString()) throw err(e.value, "main." + e.key + " must be a class name");
                    m.put(e.key, (String) e.value.value);
                }
            } else throw err(mainNode, "main must be a class name or an object of names to classes");
        }
        this.mains = m;

        Set<String> moduleExcludes = new java.util.TreeSet<>();
        Jsonc.Node exNode = root.get("exclude");
        if (exNode != null) moduleExcludes.addAll(exclusions(exNode));
        if (parent != null) moduleExcludes.addAll(parent.exclude);
        this.exclude = Collections.unmodifiableSet(moduleExcludes);

        List<Dependency> deps = new ArrayList<>();
        for (Scope s : Scope.values()) {
            Jsonc.Node n = root.get(s.key);
            if (n == null) continue;
            if (!n.isObject()) throw err(n, s.key + " must be an object of dependency to version");
            for (Jsonc.Member e : n.members.values()) {
                DepKey key;
                try {
                    key = DepKey.parse(e.key);
                } catch (JpmException ex) {
                    throw JpmException.at(file, line(e.keyStart), ex.getMessage());
                }
                deps.add(dependency(key, e, s));
            }
        }
        this.dependencies = dedupe(file, deps);

        Map<String, Define> defs = new LinkedHashMap<>();
        Map<String, Map<String, Object>> libDefs = new LinkedHashMap<>();
        Jsonc.Node dn = root.get("define");
        if (dn != null) {
            if (!dn.isObject()) throw err(dn, "define must be an object");
            for (Jsonc.Member e : dn.members.values()) {
                if (e.key.contains(":")) {
                    if (!e.value.isObject()) throw err(e.value, "define values for " + e.key + " must be an object of name to value");
                    Map<String, Object> vals = new LinkedHashMap<>();
                    for (Jsonc.Member v : e.value.members.values()) vals.put(v.key, v.value.value);
                    libDefs.put(DepKey.parse(e.key).toString(), vals);
                    continue;
                }
                if (!e.key.matches("[A-Za-z_][A-Za-z0-9_]*")) throw err(e.value, "define name \"" + e.key + "\" is not a valid Java identifier");
                defs.put(e.key, parseDefine(e));
            }
        }
        this.defines = defs;
        this.libraryDefines = libDefs;

        this.scripts = stringMap("scripts");
        this.hooks = stringMap("hooks");
        Map<String, String> pf = new LinkedHashMap<>();
        Map<String, PatchOptions> po = new LinkedHashMap<>();
        Jsonc.Node pn = root.get("patches");
        if (pn != null) {
            if (!pn.isObject()) throw err(pn, "patches must be an object of dependency to patch file");
            for (Jsonc.Member e : pn.members.values()) {
                if (e.value.isString()) {
                    pf.put(e.key, (String) e.value.value);
                    continue;
                }
                if (!e.value.isObject()) {
                    throw err(e.value, "patches." + e.key + " must be a patch file or {\"file\": ..., \"dependencies\": {...}, \"release\": N}");
                }
                for (String k : e.value.members.keySet()) {
                    if (!Set.of("file", "dependencies", "release").contains(k)) throw err(e.value.get(k), "unknown patch option \"" + k + "\"");
                }
                Jsonc.Node f = e.value.get("file");
                if (f == null || !f.isString()) throw err(e.value, "patches." + e.key + " needs a \"file\"");
                pf.put(e.key, (String) f.value);
                List<Dependency> pd = new ArrayList<>();
                Jsonc.Node dn2 = e.value.get("dependencies");
                if (dn2 != null) {
                    if (!dn2.isObject()) throw err(dn2, "patches." + e.key + ".dependencies must be an object of dependency to version");
                    for (Jsonc.Member d : dn2.members.values()) {
                        DepKey key;
                        try {
                            key = DepKey.parse(d.key);
                        } catch (JpmException ex) {
                            throw JpmException.at(file, line(d.keyStart), ex.getMessage());
                        }
                        if (key.isWorkspace()) throw err(d.value, "patch dependencies cannot be workspace modules");
                        pd.add(dependency(key, d, Scope.DEV));
                    }
                }
                Integer release = null;
                Jsonc.Node rn = e.value.get("release");
                if (rn != null) {
                    if (!(rn.value instanceof BigDecimal bd) || bd.scale() > 0 || bd.intValue() < 8) {
                        throw err(rn, "patches." + e.key + ".release must be a Java version (8 or later)");
                    }
                    release = bd.intValue();
                }
                po.put(e.key, new PatchOptions(List.copyOf(pd), release));
            }
        }
        this.patches = pf;
        this.patchOptions = po;
        for (String h : hooks.keySet()) {
            if (!h.matches("(pre|post)(build|test|install|start)(:[A-Za-z0-9_-]+)?")) {
                throw err(root.get("hooks").get(h), "unknown hook \"" + h + "\" (expected e.g. prebuild, postbuild:release, pretest)");
            }
        }
        this.workspace = stringList("workspace");
        this.repositories = stringList("repositories");
        this.javacArgs = parent == null ? stringList("javacArgs") : concat(parent.javacArgs, stringList("javacArgs"));
        this.nativeArgs = parent == null ? stringList("nativeArgs") : concat(parent.nativeArgs, stringList("nativeArgs"));
        this.testJvmArgs = parent == null ? stringList("testJvmArgs") : concat(parent.testJvmArgs, stringList("testJvmArgs"));
        Kotlin ownKotlin = parseKotlin();
        this.kotlin = ownKotlin != null || parent == null ? ownKotlin : parent.kotlin;
        this.packaging = str("package", false) == null ? "jar" : str("package", false);
        this.description = str("description", false);
        this.license = inherit(str("license", false), parent == null ? null : parent.license);
        this.url = inherit(str("url", false), parent == null ? null : parent.url);
        Jsonc.Node ext = root.get("extensions");
        if (ext != null && !ext.isObject()) throw err(ext, "extensions must be an object of extension name to its settings");
        @SuppressWarnings("unchecked")
        Map<String, Object> extMap = ext == null ? Map.of() : (Map<String, Object>) ext.value;
        this.extensions = extMap;
        if (!packaging.equals("jar") && !packaging.equals("bundle")) throw err(root.get("package"), "package must be \"jar\" or \"bundle\"");
        String layoutName = str("layout", false);
        this.layout = layoutName == null && parent != null ? parent.layout : Layout.named(layoutName);
        if (layout == null) throw err(root.get("layout"), "layout must be \"jpm\" or \"maven\"");
        this.filter = stringList("filter");
        this.sources = folders("sources");
        this.resources = folders("resources");
        this.relocate = stringMap("relocate");
        for (Map.Entry<String, String> e : relocate.entrySet()) {
            if (!e.getKey().matches("[\\w$]+(\\.[\\w$]+)*") || !e.getValue().matches("[\\w$]+(\\.[\\w$]+)*")) {
                throw err(root.get("relocate"), "relocate maps a package to a package, e.g. \"org.yaml.snakeyaml\": \"my.shaded.snakeyaml\"");
            }
        }
    }

    private Kotlin parseKotlin() {
        Jsonc.Node n = root.get("kotlin");
        if (n == null) return null;
        if (n.isString()) return new Kotlin(checkKotlinVersion(n, (String) n.value), List.of(), List.of());
        if (!n.isObject()) throw err(n, "kotlin must be a Kotlin version or {\"version\": ..., \"plugins\": [...], \"args\": [...]}");
        for (String k : n.members.keySet()) {
            if (!Set.of("version", "plugins", "args").contains(k)) throw err(n.get(k), "unknown kotlin option \"" + k + "\"");
        }
        Jsonc.Node v = n.get("version");
        if (v == null || !v.isString()) throw err(n, "kotlin needs a \"version\", such as \"2.2.20\"");
        List<String> plugins = strings(n.get("plugins"), "kotlin.plugins");
        for (String p : plugins) {
            boolean coords = p.matches("[^:\\s]+:[^:\\s]+(:[^:\\s]+)?");
            if (!coords && !Kotlin.BUILT_IN.contains(p)) {
                throw err(n.get("plugins"), "kotlin.plugins: \"" + p + "\" is neither group:artifact[:version] nor one of Kotlin's own: "
                        + String.join(", ", new java.util.TreeSet<>(Kotlin.BUILT_IN)));
            }
        }
        return new Kotlin(checkKotlinVersion(v, (String) v.value), plugins, strings(n.get("args"), "kotlin.args"));
    }

    private String checkKotlinVersion(Jsonc.Node n, String v) {
        if (!v.matches("\\d+\\.\\d+\\.\\d+([-.][0-9A-Za-z.-]+)?")) throw err(n, "kotlin version must be exact, such as \"2.2.20\"");
        return v;
    }

    private List<String> strings(Jsonc.Node n, String what) {
        List<String> out = new ArrayList<>();
        if (n == null) return out;
        if (!n.isArray()) throw err(n, what + " must be an array of strings");
        for (Jsonc.Node i : n.items) {
            if (!i.isString()) throw err(i, what + " must be an array of strings");
            out.add((String) i.value);
        }
        return List.copyOf(out);
    }

    private static String inherit(String own, String fromRoot) {
        return own != null ? own : fromRoot;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return List.copyOf(out);
    }

    private Dependency dependency(DepKey key, Jsonc.Member e, Scope s) {
        int ln = line(e.keyStart);
        Jsonc.Node v = e.value;
        Set<String> moduleExcludes = exclude == null || s == Scope.TEST ? Set.of() : exclude;
        if (v.isString()) return new Dependency(key, (String) v.value, s, ln, moduleExcludes, false);
        if (!v.isObject()) {
            throw err(v, "dependency " + e.key + " must be a version string or {\"version\": ..., \"exclude\": [...], \"runtimeOnly\": true}");
        }
        for (String k : v.members.keySet()) {
            if (!Set.of("version", "exclude", "runtimeOnly", "api", "tests").contains(k)) throw err(v.get(k), "unknown dependency option \"" + k + "\"");
        }
        Jsonc.Node ver = v.get("version");
        if (ver == null || !ver.isString()) throw err(v, "dependency " + e.key + " needs a \"version\" string");
        Set<String> excl = new java.util.TreeSet<>();
        excl.addAll(moduleExcludes);
        Jsonc.Node ex = v.get("exclude");
        if (ex != null) excl.addAll(exclusions(ex));
        Jsonc.Node ro = v.get("runtimeOnly");
        boolean runtimeOnly = ro != null && Boolean.TRUE.equals(ro.value);
        if (ro != null && !(ro.value instanceof Boolean)) throw err(ro, "runtimeOnly must be true or false");
        if (runtimeOnly && s != Scope.RUNTIME) throw err(ro, "runtimeOnly applies to \"runtime\" dependencies only");
        Jsonc.Node apiNode = v.get("api");
        boolean api = apiNode != null && Boolean.TRUE.equals(apiNode.value);
        if (apiNode != null && !(apiNode.value instanceof Boolean)) throw err(apiNode, "api must be true or false");
        if (api && s != Scope.DEV) {
            throw err(apiNode, "api applies to \"dev\" dependencies only" + (s == Scope.RUNTIME
                    ? ": runtime dependencies are always passed on to consumers" : ""));
        }
        Jsonc.Node testsNode = v.get("tests");
        boolean tests = testsNode != null && Boolean.TRUE.equals(testsNode.value);
        if (testsNode != null && !(testsNode.value instanceof Boolean)) throw err(testsNode, "tests must be true or false");
        if (tests && (s != Scope.TEST || !key.isWorkspace())) {
            throw err(testsNode, "tests applies to \"test\" dependencies on workspace modules (\"workspace:<module>\") only");
        }
        return new Dependency(key, (String) ver.value, s, ln, excl, runtimeOnly, api, tests);
    }

    private Set<String> exclusions(Jsonc.Node ex) {
        Set<String> excl = new java.util.TreeSet<>();
        if (!ex.isArray()) throw err(ex, "exclude must be an array of \"group:artifact\" (artifact may be *)");
        for (Jsonc.Node i : ex.items) {
            if (!i.isString() || !((String) i.value).matches("[^:\\s]+(:[^:\\s]+)?")) {
                throw err(i, "exclude entries look like \"group:artifact\", \"group:*\" or \"group\"");
            }
            excl.add((String) i.value);
        }
        return excl;
    }

    private Define parseDefine(Jsonc.Member e) {
        Jsonc.Node v = e.value;
        int ln = line(e.keyStart);
        if (!v.isObject()) {
            DefineType t = v.value instanceof Boolean ? DefineType.BOOLEAN
                    : v.value instanceof BigDecimal ? DefineType.INT : DefineType.STRING;
            if (v.value == null) throw err(v, "define " + e.key + " needs a value or {\"type\": ...}");
            return new Define(e.key, t, v.value, null, null, ln);
        }
        Jsonc.Node tn = v.get("type");
        if (tn == null || !tn.isString()) throw err(v, "define " + e.key + " needs a \"type\" (string, int or boolean)");
        DefineType type = switch ((String) tn.value) {
            case "string" -> DefineType.STRING;
            case "int" -> DefineType.INT;
            case "boolean" -> DefineType.BOOLEAN;
            default -> throw err(tn, "define type must be string, int or boolean");
        };
        for (String k : v.members.keySet()) {
            if (!Set.of("type", "value", "env", "default").contains(k)) throw err(v.get(k), "unknown define field \"" + k + "\"");
        }
        Jsonc.Node env = v.get("env");
        if (env != null && !env.isString()) throw err(env, "env must be a variable name");
        return new Define(e.key, type, v.get("value") == null ? null : v.get("value").value,
                env == null ? null : (String) env.value,
                v.get("default") == null ? null : v.get("default").value, ln);
    }

    public static Manifest load(Path dir) {
        Path f = dir.resolve(FILE);
        if (!Files.exists(f)) {
            throw new JpmException("No " + FILE + " found in " + dir.toAbsolutePath()
                    + ". Run `jpm init` to create one. Importing Maven and Gradle projects is not supported yet.");
        }
        return new Manifest(f, Fs.read(f));
    }

    public static Manifest parse(Path file, String source) {
        return new Manifest(file, source);
    }

    /** A workspace module's manifest, with the settings it inherits from {@code root} (see {@link #INHERITED}). */
    public static Manifest parse(Path file, String source, Manifest root) {
        return new Manifest(file, source, root);
    }

    /** Loads a workspace module's manifest, with the settings it inherits from {@code root}. */
    public static Manifest load(Path dir, Manifest root) {
        Manifest plain = load(dir);
        return root == null ? plain : new Manifest(plain.file, plain.source, root);
    }

    public Path dir() {
        return file.getParent();
    }

    public boolean isWorkspaceRoot() {
        return !workspace.isEmpty();
    }

    public int line(int offset) {
        return Jsonc.lineOf(source, offset);
    }

    public JpmException err(Jsonc.Node n, String msg) {
        return JpmException.at(file, n == null ? 1 : line(n.start), msg);
    }

    private String str(String key, boolean required) {
        Jsonc.Node n = root.get(key);
        if (n == null) {
            if (required) throw JpmException.at(file, 1, "missing required field \"" + key + "\"");
            return null;
        }
        if (n.value instanceof BigDecimal bd && (key.equals("java") || key.equals("target"))) return bd.toPlainString();
        if (!n.isString()) throw err(n, key + " must be a string");
        return (String) n.value;
    }

    private Map<String, String> stringMap(String key) {
        Map<String, String> out = new LinkedHashMap<>();
        Jsonc.Node n = root.get(key);
        if (n == null) return out;
        if (!n.isObject()) throw err(n, key + " must be an object");
        for (Jsonc.Member e : n.members.values()) {
            if (!e.value.isString()) throw err(e.value, key + "." + e.key + " must be a string");
            out.put(e.key, (String) e.value.value);
        }
        return out;
    }

    private List<String> folders(String key) {
        List<String> out = stringList(key);
        for (String f : out) {
            Path p = Path.of(f).normalize();
            if (f.isBlank() || p.isAbsolute() || p.startsWith("..")) throw err(root.get(key), key + " are folders inside the module, such as \"src/main/java\"");
        }
        return out;
    }

    /** The module's source folders: {@code "sources"}, or the layout's one. */
    public List<Path> sourceDirs(Path moduleDir) {
        if (!sources.isEmpty()) return sources.stream().map(moduleDir::resolve).toList();
        if (kotlin != null && layout == Layout.MAVEN) return List.of(layout.sources(moduleDir), moduleDir.resolve("src/main/kotlin"));
        return List.of(layout.sources(moduleDir));
    }

    /** The module's test source folders: the layout's, and {@code src/test/kotlin} for Kotlin in the Maven layout. */
    public List<Path> testSourceDirs(Path moduleDir) {
        if (kotlin != null && layout == Layout.MAVEN) return List.of(layout.tests(moduleDir), moduleDir.resolve("src/test/kotlin"));
        return List.of(layout.tests(moduleDir));
    }

    /** The module's resource folders: {@code "resources"}, or the layout's one. */
    public List<Path> resourceDirs(Path moduleDir) {
        if (resources.isEmpty()) return List.of(layout.resources(moduleDir));
        return resources.stream().map(moduleDir::resolve).toList();
    }

    private List<String> stringList(String key) {
        List<String> out = new ArrayList<>();
        Jsonc.Node n = root.get(key);
        if (n == null) return out;
        if (!n.isArray()) throw err(n, key + " must be an array of strings");
        for (Jsonc.Node i : n.items) {
            if (!i.isString()) throw err(i, key + " must be an array of strings");
            out.add((String) i.value);
        }
        return out;
    }

    /** Major Java version requested ("graalvm-21" gives 21), or null for "any". */
    public Integer javaMajor() {
        if (java == null) return null;
        String digits = java.replaceAll(".*?(\\d+).*", "$1");
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new JpmException(file + ": invalid java version \"" + java + "\"");
        }
    }

    /** Bytecode target, defaults to the java version. */
    public Integer targetMajor() {
        if (target != null) {
            try {
                return Integer.parseInt(target.replaceFirst("^1\\.", ""));
            } catch (NumberFormatException e) {
                throw new JpmException(file + ": invalid target \"" + target + "\"");
            }
        }
        return javaMajor();
    }

    /**
     * A dependency may be declared in more than one scope where that means something; one project resolves a
     * package to one version, so the entries must agree on it.
     * <ul>
     *   <li>dev + test: a compile-time-only dependency that tests also run with (a platform API under a mock
     *       server). Both are kept; their versions must match.</li>
     *   <li>runtime + test: the test entry adds nothing, since tests have every runtime dependency. A warning; the
     *       runtime entry is used.</li>
     *   <li>runtime + dev: with the same version the dev entry adds nothing (a warning); with another version
     *       it contradicts the runtime one (an error).</li>
     * </ul>
     */
    private static List<Dependency> dedupe(Path file, List<Dependency> deps) {
        Map<DepKey, Map<Scope, Dependency>> byKey = new TreeMap<>();
        for (Dependency d : deps) byKey.computeIfAbsent(d.key, k -> new EnumMap<>(Scope.class)).put(d.scope, d);
        Set<Dependency> drop = new HashSet<>();
        for (Map<Scope, Dependency> scopes : byKey.values()) {
            if (scopes.size() < 2) continue;
            Dependency runtime = scopes.get(Scope.RUNTIME), dev = scopes.get(Scope.DEV), test = scopes.get(Scope.TEST);
            if (runtime != null && dev != null) {
                if (!sameVersion(runtime, dev)) {
                    throw JpmException.at(file, dev.line, dev.key + " is declared in \"runtime\" (" + runtime.spec
                            + ") and in \"dev\" (" + dev.spec + ") with different versions; runtime dependencies are on the"
                            + " compile classpath already, so remove the \"dev\" entry");
                }
                Log.warn(file + ":" + dev.line + ": " + dev.key + " is in \"runtime\" and \"dev\"; runtime dependencies are on"
                        + " the compile classpath already, so the \"dev\" entry is ignored. Remove it");
                drop.add(dev);
                dev = null;
            }
            if (runtime != null && test != null && test.tests && sameVersion(runtime, test)) {
            } else if (runtime != null && test != null) {
                Log.warn(file + ":" + test.line + ": " + test.key + " is in \"runtime\" and \"test\"; tests have every runtime"
                        + " dependency already, so the \"test\" entry is ignored"
                        + (sameVersion(runtime, test) ? "" : " and " + runtime.spec + " is used, not " + test.spec) + ". Remove it");
                drop.add(test);
            } else if (dev != null && test != null && !sameVersion(dev, test)) {
                throw JpmException.at(file, test.line, test.key + " is declared in \"dev\" (" + dev.spec
                        + ") and in \"test\" (" + test.spec + ") with different versions; a project uses one version of a"
                        + " package, so give both the same");
            }
        }
        if (drop.isEmpty()) return deps;
        List<Dependency> kept = new ArrayList<>(deps);
        kept.removeIf(drop::contains);
        return kept;
    }

    private static boolean sameVersion(Dependency a, Dependency b) {
        return Objects.equals(a.spec == null ? null : a.spec.strip(), b.spec == null ? null : b.spec.strip());
    }

    public List<Dependency> deps(Scope s) {
        return dependencies.stream().filter(d -> d.scope == s).toList();
    }
}
