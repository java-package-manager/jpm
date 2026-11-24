package dev.jpm;

import dev.jpm.api.json.Jsonc;
import dev.jpm.impl.ManifestReader;
import dev.jpm.impl.ScopeChecks;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parsed {@code project.jsonc}.
 */
public final class Manifest {
    public static final String FILE = "project.jsonc";
    public static final String LOCK = "project.lock";
    /**
     * The JSON Schema of project.jsonc, src/assets/dev/jpm/project.schema.json, as attached to every release.
     */
    public static final String SCHEMA_URL = "https://github.com/java-package-manager/jpm/releases/latest/download/project.schema.json";

    /**
     * The schema of one jpm release, which never changes: what a manifest pins to describe exactly that jpm's fields.
     */
    public static String versionedSchemaUrl(String version) {
        return "https://github.com/java-package-manager/jpm/releases/download/" + version + "/project.schema.json";
    }

    /**
     * Every top-level field jpm reads. The schema describes the same set (SchemaTest checks).
     */
    public static final Set<String> FIELDS = Set.of("name", "version", "group", "java", "target", "main", "runtime", "dev", "test",
            "define", "scripts", "hooks", "patches", "workspace", "visibility", "isolated", "repositories", "defineClass",
            "javacArgs", "nativeArgs", "testJvmArgs", "kotlin", "groovy", "scala", "exclude", "layout", "sources", "resources", "filter", "relocate", "package", "description", "license", "url", "extensions", "extension", "minimumReleaseAge", "releaseAgeExceptions", "trustedKeys", "$schema");

    /**
     * The dependency sections of a manifest; {@link #key} is the section's name in {@code project.jsonc}.
     */
    public enum Scope {
        RUNTIME("runtime"), DEV("dev"), TEST("test");
        public final String key;

        Scope(String key) {
            this.key = key;
        }
    }

    /**
     * A script with {@code "generates"}: jpm runs it before compiling, into a folder of its own ({@code JPM_GENERATED}),
     * whenever its inputs or its definition changed, and adds the folder to the module's sources or resources.
     *
     * @param name   the script's name
     * @param kind   {@code sources} or {@code resources}
     * @param inputs files and folders, relative to the module, whose changes make it run again
     */
    public record Generator(String name, String kind, List<String> inputs) {}

    /**
     * The type of a define, which is the type of its constant in the generated class.
     */
    public enum DefineType {
        STRING, INT, BOOLEAN
    }

    /**
     * A {@code define} entry. {@code value}/{@code env}/{@code defaultValue} may be null.
     */
    public record Define(String name, DefineType type, Object value, String env, Object defaultValue, int line) {
        public boolean hasSource() {
            return value != null || env != null;
        }
    }

    /**
     * Options of a patch: {@code dependencies} are compile-only for the patched classes (resolved with the
     * project, never on anyone's runtime classpath); {@code release} compiles them for a newer Java than
     * the library's own bytecode level.
     */
    public record PatchOptions(List<Dependency> dependencies, Integer release) {}

    /**
     * A declared dependency. Written as a version string, or as an object
     * {@code {"version": "...", "exclude": [...], "runtimeOnly": true}}. {@code api}: a dev dependency that
     * consumers compile against too (Gradle's compileOnlyApi); never at run time.
     *
     * <p>{@code tests}: a {@code test} dependency on a workspace module that brings that module's compiled tests too
     * (Gradle's test fixtures), so tests can share base classes and helpers.
     */
    public record Dependency(DepKey key, String spec, Scope scope, int line, Set<String> exclusions, boolean runtimeOnly, boolean api,
                             boolean tests) {
        /**
         * A dependency with no options.
         */
        public Dependency(DepKey key, String spec, Scope scope, int line) {
            this(key, spec, scope, line, Set.of(), false, false, false);
        }

        /**
         * A dependency with exclusions, and runtime-only or not.
         */
        public Dependency(DepKey key, String spec, Scope scope, int line, Set<String> exclusions, boolean runtimeOnly) {
            this(key, spec, scope, line, exclusions, runtimeOnly, false, false);
        }

        /**
         * A dependency with exclusions, runtime-only or not, and {@code "api"} or not.
         */
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
        /**
         * Kotlin's own compiler plugins, by the short names "plugins" accepts.
         */
        public static final Set<String> BUILT_IN = Set.of("assignment", "sam-with-receiver", "allopen", "noarg", "serialization",
                "lombok", "power-assert", "parcelize", "atomicfu", "js-plain-objects", "compose");

        /**
         * Maven coordinates (group:artifact:version) of each plugin.
         */
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
    private final ManifestReader reader;

    public final String name;
    public final String version;
    public final String group;
    public final String java;
    public final String target;
    /**
     * Entry points: name to class. A single {@code main} string is stored under "main".
     */
    public final Map<String, String> mains;

    public final List<Dependency> dependencies;
    public final Map<String, Define> defines;
    /**
     * Values this project provides for defines declared by its dependencies: dep key to (name to value).
     */
    public final Map<String, Map<String, Object>> libraryDefines;

    public final Map<String, String> scripts;
    /**
     * Dependencies of {@code .java} scripts, by script name: dependency key to version, resolved apart from the project.
     */
    public final Map<String, Map<String, String>> scriptDependencies;
    /**
     * Scripts that generate code, by name: run before compiling, their output added to the sources or resources.
     */
    public final Map<String, Generator> generators;

    public final Map<String, String> hooks;
    /**
     * Patched dependency to its patch file.
     */
    public final Map<String, String> patches;
    /**
     * Patched dependency to the options of its patch (a patch given as an object); absent for a plain path.
     */
    public final Map<String, PatchOptions> patchOptions;

    public final List<String> workspace;
    public final String visibility;
    /**
     * Resolved in its own graph, so its dependencies do not move the other modules' versions.
     */
    public final boolean isolated;

    public final List<String> repositories;
    /**
     * How old a version must be before jpm picks it and {@code jpm check} accepts it locked (ADR 0018); null for no minimum.
     */
    public final Duration minimumReleaseAge;
    /**
     * Package id to the one version of it that may be younger than {@link #minimumReleaseAge}.
     */
    public final Map<String, String> releaseAgeExceptions;
    /**
     * OpenPGP key fingerprint (upper case, no spaces) to the package patterns the key may sign; empty when signatures
     * are not checked (see {@code dev.jpm.signing.Signatures}).
     */
    public final Map<String, List<String>> trustedKeys;

    public final String defineClass;
    public final List<String> javacArgs;
    public final List<String> nativeArgs;
    /**
     * Options for the JVM that runs the tests, e.g. {@code --add-opens} or {@code -Xmx2g}.
     */
    public final List<String> testJvmArgs;
    /**
     * Packages left out of what this module's {@code runtime} and {@code dev} dependencies bring in, like an exclusion on
     * each of them (Gradle's {@code configurations.all { exclude(...) }} for what the platform provides). {@code test}
     * dependencies are not affected. A workspace module adds its own to the root's.
     */
    public final Set<String> exclude;
    /**
     * Kotlin settings, or null for a module without Kotlin.
     */
    public final Kotlin kotlin;
    /**
     * {@code "groovy"}: the Groovy version that compiles the module's .groovy sources (joint with its Java), or null.
     */
    public final String groovy;
    /**
     * {@code "scala"}: the Scala version (2.13 or 3) that compiles the module's .scala sources (seeing its Java), or null.
     */
    public final String scala;

    public final Layout layout;
    /**
     * Resources (globs relative to the resource folder) copied with ${NAME} replaced by define values.
     */
    public final List<String> filter;
    /**
     * Source folders relative to the module, replacing the layout's one; empty for the layout's.
     */
    public final List<String> sources;
    /**
     * Resource folders relative to the module, replacing the layout's one; empty for the layout's.
     */
    public final List<String> resources;
    /**
     * Packages moved to new names in flat bundles (Shadow's relocation): package to new package.
     */
    public final Map<String, String> relocate;
    /**
     * What `jpm build` produces: "jar" (default) or "bundle" (jar-in-jar with isolated modules).
     */
    public final String packaging;

    public final String description;
    public final String license;
    public final String url;
    /**
     * Settings for extensions, by extension name (e.g. "publish"); jpm passes them through untouched.
     */
    public final Map<String, Object> extensions;
    /**
     * The extensions this project uses, declared in {@code "extensions"} by dependency key; only in a root manifest.
     */
    public final List<ExtensionDecl> declaredExtensions;
    /**
     * True when this manifest is an extension's own ({@code "extension": {...}}), which {@code jpm add} detects.
     */
    public final boolean isExtension;
    /**
     * What an extension's manifest says it adds ({@code "extension"}); null for other manifests.
     */
    public final ExtensionDescriptor extension;

    /**
     * An extension's description of itself: its commands with their options, and the checks it adds to {@code jpm check}.
     */
    public record ExtensionDescriptor(String description, List<ExtensionCommand> commands, List<ExtensionCheck> checks) {}

    /**
     * A command an extension adds: {@code name} is what follows {@code jpm} ({@code publish}, {@code format check}).
     */
    public record ExtensionCommand(String name, String description, List<ExtensionOption> options) {}

    /**
     * An option of an extension's command; {@code argument} names its value, null for a flag.
     */
    public record ExtensionOption(String name, String argument, String description) {}

    /**
     * A check an extension adds to {@code jpm check}, by id; {@code jpm check} runs the extension with {@code run} as its
     * arguments ({@code check <id>} when the descriptor gives none).
     */
    public record ExtensionCheck(String id, String description, List<String> run) {}

    /**
     * An extension declared in {@code "extensions"}: {@code "github:me/jpm-foo": "^1.0"}.
     */
    public record ExtensionDecl(DepKey key, String spec, int line) {
        /**
         * The command it adds: its artifact without the {@code jpm-} prefix ({@code jpm-publish} gives {@code publish}).
         */
        public String name() {
            return nameOf(key);
        }
    }

    /**
     * The command an extension adds, from its dependency key.
     */
    public static String nameOf(DepKey key) {
        return key.mavenArtifact().replaceFirst("^jpm-", "");
    }

    /**
     * Fields a workspace module takes from the workspace root when it does not set them itself.
     * {@code javacArgs}, {@code nativeArgs} and {@code testJvmArgs} are the root's followed by the module's own.
     */
    public static final List<String> INHERITED = List.of("java", "target", "group", "license", "url", "layout", "javacArgs", "nativeArgs", "testJvmArgs", "kotlin", "groovy", "scala", "exclude");

    private Manifest(Path file, String source) {
        this(file, source, null);
    }

    /**
     * {@code parent}: the workspace root this module belongs to, whose settings it inherits; or null.
     */
    private Manifest(Path file, String source, Manifest parent) {
        this.file = file;
        this.source = source;
        try {
            this.root = Jsonc.parseNode(source);
            this.reader = new ManifestReader(file, source, root);
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

        this.name = reader.str("name", true);
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) throw err(root.get("name"), "invalid name \"" + name + "\"");
        this.version = reader.str("version", false);
        this.group = inherit(reader.str("group", false), parent == null ? null : parent.group);
        this.java = inherit(reader.str("java", false), parent == null ? null : parent.java);
        this.target = inherit(reader.str("target", false), parent == null ? null : parent.target);
        this.visibility = reader.str("visibility", false) == null ? "public" : reader.str("visibility", false);
        if (!visibility.equals("public") && !visibility.equals("internal")) {
            throw err(root.get("visibility"), "visibility must be \"public\" or \"internal\"");
        }
        Jsonc.Node iso = root.get("isolated");
        if (iso != null && !(iso.value instanceof Boolean)) throw err(iso, "isolated must be true or false");
        this.isolated = iso != null && Boolean.TRUE.equals(iso.value);
        this.defineClass = reader.str("defineClass", false);

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
        if (exNode != null) moduleExcludes.addAll(reader.exclusions(exNode));
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
                deps.add(reader.dependency(key, e, s, exclude));
            }
        }
        this.dependencies = ScopeChecks.dedupe(file, deps);

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
                defs.put(e.key, reader.parseDefine(e));
            }
        }
        this.defines = defs;
        this.libraryDefines = libDefs;

        ManifestReader.Scripts sc = reader.scripts();
        this.scripts = sc.commands();
        this.scriptDependencies = sc.dependencies();
        this.generators = sc.generators();
        this.hooks = reader.stringMap("hooks");
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
                        pd.add(reader.dependency(key, d, Scope.DEV, exclude));
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
        this.workspace = reader.stringList("workspace");
        this.repositories = reader.stringList("repositories");
        this.minimumReleaseAge = releaseAge(parent);
        this.releaseAgeExceptions = releaseAgeExceptions(parent);
        this.trustedKeys = trustedKeys(parent);
        this.javacArgs = parent == null ? reader.stringList("javacArgs") : concat(parent.javacArgs, reader.stringList("javacArgs"));
        this.nativeArgs = parent == null ? reader.stringList("nativeArgs") : concat(parent.nativeArgs, reader.stringList("nativeArgs"));
        this.testJvmArgs = parent == null ? reader.stringList("testJvmArgs") : concat(parent.testJvmArgs, reader.stringList("testJvmArgs"));
        Kotlin ownKotlin = reader.parseKotlin();
        this.kotlin = ownKotlin != null || parent == null ? ownKotlin : parent.kotlin;
        String ownGroovy = reader.languageVersion("groovy"), ownScala = reader.languageVersion("scala");
        this.groovy = ownGroovy != null || parent == null ? ownGroovy : parent.groovy;
        this.scala = ownScala != null || parent == null ? ownScala : parent.scala;
        this.packaging = reader.str("package", false) == null ? "jar" : reader.str("package", false);
        this.description = reader.str("description", false);
        this.license = inherit(reader.str("license", false), parent == null ? null : parent.license);
        this.url = inherit(reader.str("url", false), parent == null ? null : parent.url);
        Jsonc.Node ext = root.get("extensions");
        if (ext != null && !ext.isObject()) throw err(ext, "extensions must be an object of extension name to its settings");
        @SuppressWarnings("unchecked") Map<String, Object> extMap = ext == null ? Map.of() : (Map<String, Object>) ext.value;
        this.extensions = extMap;
        List<ExtensionDecl> decls = new ArrayList<>();
        if (ext != null) {
            for (Jsonc.Member e : ext.members.values()) {
                if (!e.key.contains(":")) {
                    if (!e.value.isObject()) throw err(e.value, "extensions." + e.key + " must be an object of settings; declare an extension by"
                            + " its dependency key instead (\"github:me/jpm-" + e.key + "\": \"^1.0\")");
                    continue;
                }
                if (parent != null) throw JpmException.at(file, line(e.keyStart), "declare extensions in the workspace root's " + FILE + ", not in a module's");
                DepKey key;
                try {
                    key = DepKey.parse(e.key);
                } catch (JpmException ex) {
                    throw JpmException.at(file, line(e.keyStart), ex.getMessage());
                }
                if (key.isWorkspace() || key.isExtension() || key.isGradle()) {
                    throw JpmException.at(file, line(e.keyStart), e.key + " cannot be an extension: use a Maven or Git dependency key");
                }
                if (!(e.value.value instanceof String spec)) throw err(e.value, "the extension " + e.key + " needs a version, such as \"^1.0\"");
                ExtensionDecl d = new ExtensionDecl(key, spec, line(e.keyStart));
                for (ExtensionDecl o : decls) {
                    if (o.name().equals(d.name())) throw JpmException.at(file, d.line(), o.key() + " and " + key + " are both the extension " + d.name());
                }
                decls.add(d);
            }
        }
        this.declaredExtensions = List.copyOf(decls);
        Jsonc.Node marker = root.get("extension");
        if (marker != null && !marker.isObject()) throw err(marker, "extension must be an object describing the extension, such as {}");
        this.isExtension = marker != null;
        this.extension = marker == null ? null : descriptor(marker);
        if (!packaging.equals("jar") && !packaging.equals("bundle")) throw err(root.get("package"), "package must be \"jar\" or \"bundle\"");
        String layoutName = reader.str("layout", false);
        this.layout = layoutName == null && parent != null ? parent.layout : Layout.named(layoutName);
        if (layout == null) throw err(root.get("layout"), "layout must be \"jpm\" or \"maven\"");
        this.filter = reader.stringList("filter");
        this.sources = reader.folders("sources");
        this.resources = reader.folders("resources");
        this.relocate = reader.stringMap("relocate");
        for (Map.Entry<String, String> e : relocate.entrySet()) {
            if (!e.getKey().matches("[\\w$]+(\\.[\\w$]+)*") || !e.getValue().matches("[\\w$]+(\\.[\\w$]+)*")) {
                throw err(root.get("relocate"), "relocate maps a package to a package, e.g. \"org.yaml.snakeyaml\": \"my.shaded.snakeyaml\"");
            }
        }
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

    /**
     * Loads {@code project.jsonc} from a folder; fails when there is none.
     */
    public static Manifest load(Path dir) {
        Path f = dir.resolve(FILE);
        if (!Files.exists(f)) {
            throw new JpmException("No " + FILE + " found in " + dir.toAbsolutePath()
                    + ". Run `jpm init` to create one. Importing Maven and Gradle projects is not supported yet.");
        }
        return new Manifest(f, Fs.read(f));
    }

    /**
     * Parses manifest text; {@code file} is only used in messages.
     */
    public static Manifest parse(Path file, String source) {
        return new Manifest(file, source);
    }

    /**
     * A workspace module's manifest, with the settings it inherits from {@code root} (see {@link #INHERITED}).
     */
    public static Manifest parse(Path file, String source, Manifest root) {
        return new Manifest(file, source, root);
    }

    /**
     * Loads a workspace module's manifest, with the settings it inherits from {@code root}.
     */
    public static Manifest load(Path dir, Manifest root) {
        Manifest plain = load(dir);
        return root == null ? plain : new Manifest(plain.file, plain.source, root);
    }

    /**
     * The folder the manifest is in.
     */
    public Path dir() {
        return file.getParent();
    }

    /**
     * True when the manifest lists {@code "workspace"} modules.
     */
    public boolean isWorkspaceRoot() {
        return !workspace.isEmpty();
    }

    private ExtensionDescriptor descriptor(Jsonc.Node n) {
        for (Jsonc.Member m : n.members.values()) {
            if (!Set.of("description", "commands", "checks").contains(m.key)) {
                throw JpmException.at(file, line(m.keyStart), "unknown field \"" + m.key + "\" in extension; it has description, commands and checks");
            }
        }
        String prefix = name.replaceFirst("^jpm-", "");
        List<ExtensionCommand> commands = new ArrayList<>();
        Jsonc.Node cs = n.get("commands");
        if (cs != null) {
            if (!cs.isObject()) throw err(cs, "extension.commands must be an object of command to its description");
            for (Jsonc.Member c : cs.members.values()) {
                if (!c.value.isObject()) throw err(c.value, "extension.commands." + c.key + " must be an object");
                if (!c.key.equals(prefix) && !c.key.startsWith(prefix + " ")) {
                    throw JpmException.at(file, line(c.keyStart), "the command \"" + c.key + "\" must start with \"" + prefix
                            + "\": jpm runs this extension as `jpm " + prefix + "`");
                }
                List<ExtensionOption> options = new ArrayList<>();
                Jsonc.Node os = c.value.get("options");
                if (os != null) {
                    if (!os.isObject()) throw err(os, "options must be an object of option to its description");
                    for (Jsonc.Member o : os.members.values()) {
                        if (!o.key.startsWith("-")) throw JpmException.at(file, line(o.keyStart), "the option \"" + o.key + "\" must start with -");
                        if (!o.value.isObject()) throw err(o.value, "the option " + o.key + " must be an object");
                        options.add(new ExtensionOption(o.key, text(o.value, "argument"), text(o.value, "description")));
                    }
                }
                commands.add(new ExtensionCommand(c.key, text(c.value, "description"), List.copyOf(options)));
            }
        }
        List<ExtensionCheck> checks = new ArrayList<>();
        Jsonc.Node ks = n.get("checks");
        if (ks != null) {
            if (!ks.isObject()) throw err(ks, "extension.checks must be an object of check id to its description");
            for (Jsonc.Member k : ks.members.values()) {
                if (!k.key.matches("[a-z][a-z0-9-]*")) throw JpmException.at(file, line(k.keyStart), "a check id is lowercase letters, digits and -, not \"" + k.key + "\"");
                if (!k.value.isObject()) throw err(k.value, "the check " + k.key + " must be an object");
                List<String> run = List.of("check", k.key);
                Jsonc.Node r = k.value.get("run");
                if (r != null) {
                    if (!(r.value instanceof List<?> l) || l.stream().anyMatch(x -> !(x instanceof String))) {
                        throw err(r, "run must be a list of the arguments jpm passes to the extension, such as [\"--check\"]");
                    }
                    run = l.stream().map(String::valueOf).toList();
                }
                checks.add(new ExtensionCheck(k.key, text(k.value, "description"), run));
            }
        }
        return new ExtensionDescriptor(text(n, "description"), List.copyOf(commands), List.copyOf(checks));
    }

    private String text(Jsonc.Node obj, String key) {
        Jsonc.Node v = obj.get(key);
        if (v == null) return null;
        if (!(v.value instanceof String s)) throw err(v, key + " must be a string");
        return s;
    }

    /**
     * The 1-based line of an offset in the manifest's text.
     */
    public int line(int offset) {
        return reader.line(offset);
    }

    private Duration releaseAge(Manifest parent) {
        Jsonc.Node n = root.get("minimumReleaseAge");
        if (n == null) return null;
        if (parent != null) throw err(n, "set minimumReleaseAge in the workspace root's " + FILE + ", not in a module's");
        Duration d = n.isString() ? parseAge((String) n.value) : null;
        if (d == null) throw err(n, "minimumReleaseAge must be a number and a unit, such as \"7 days\", \"12h\" or \"2 weeks\"");
        return d;
    }

    /**
     * A duration such as {@code 7 days}, {@code 7d}, {@code 12 hours} or {@code 2w}, or null when it is not one.
     */
    public static Duration parseAge(String text) {
        Matcher m = Pattern.compile("(\\d{1,6})\\s*(m|min|mins|minutes?|h|hours?|d|days?|w|weeks?)")
                .matcher(text.strip().toLowerCase(Locale.ROOT));
        if (!m.matches()) return null;
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2).charAt(0)) {
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            default -> Duration.ofDays(7 * n);
        };
    }

    private Map<String, List<String>> trustedKeys(Manifest parent) {
        Jsonc.Node n = root.get("trustedKeys");
        if (n == null) return Map.of();
        if (parent != null) throw err(n, "set trustedKeys in the workspace root's " + FILE + ", not in a module's");
        String shape = "trustedKeys must be an object of OpenPGP key fingerprint to the packages the key signs, such as"
                + " {\"7A1E 4B8C ... 9F02\": \"com.example\"} or [\"com.example\", \"org.other:lib\"]";
        if (!n.isObject()) throw err(n, shape);
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Jsonc.Member e : n.members.values()) {
            String fpr = dev.jpm.signing.Signatures.fingerprint(e.key);
            if (!fpr.matches("[0-9A-F]{40}")) {
                throw JpmException.at(file, line(e.keyStart), "trustedKeys: \"" + e.key + "\" is not a key fingerprint (40 hexadecimal digits)")
                        .withHint("`gpg --fingerprint <key>` shows it; a short key id is not enough to trust a key by");
            }
            List<String> patterns = new ArrayList<>();
            if (e.value.isString()) patterns.add((String) e.value.value);
            else if (e.value.isArray() && e.value.items.stream().allMatch(Jsonc.Node::isString)) {
                for (Jsonc.Node x : e.value.items) patterns.add((String) x.value);
            } else throw err(e.value, shape);
            if (patterns.isEmpty() || patterns.stream().anyMatch(String::isBlank)) throw err(e.value, shape);
            out.put(fpr, List.copyOf(patterns));
        }
        return out;
    }

    private Map<String, String> releaseAgeExceptions(Manifest parent) {
        Jsonc.Node n = root.get("releaseAgeExceptions");
        if (n == null) return Map.of();
        if (parent != null) throw err(n, "set releaseAgeExceptions in the workspace root's " + FILE + ", not in a module's");
        if (!n.isObject()) throw err(n, "releaseAgeExceptions must be an object of package to the version that may be younger, such as"
                + " {\"com.example:lib\": \"1.4.2\"}");
        Map<String, String> out = new LinkedHashMap<>();
        for (Jsonc.Member e : n.members.values()) {
            DepKey key;
            try {
                key = DepKey.parse(e.key);
            } catch (JpmException ex) {
                throw JpmException.at(file, line(e.keyStart), ex.getMessage());
            }
            if (!key.isMaven() && !key.isGit()) throw JpmException.at(file, line(e.keyStart), e.key + " has no releases to wait for: name a Maven or Git package");
            if (!e.value.isString() || ((String) e.value.value).isBlank()) {
                throw err(e.value, "releaseAgeExceptions." + e.key + " must be the one version that may be younger, such as \"1.4.2\"");
            }
            out.put(key.toString(), ((String) e.value.value).strip());
        }
        return out;
    }

    /**
     * An error at the line of a value in the manifest.
     */
    public JpmException err(Jsonc.Node n, String msg) {
        return reader.err(n, msg);
    }

    /**
     * The module's source folders: {@code "sources"}, or the layout's one.
     */
    public List<Path> sourceDirs(Path moduleDir) {
        if (!sources.isEmpty()) return sources.stream().map(moduleDir::resolve).toList();
        List<Path> out = new java.util.ArrayList<>(List.of(layout.sources(moduleDir)));
        if (layout == Layout.MAVEN) {
            if (kotlin != null) out.add(moduleDir.resolve("src/main/kotlin"));
            if (groovy != null) out.add(moduleDir.resolve("src/main/groovy"));
            if (scala != null) out.add(moduleDir.resolve("src/main/scala"));
        }
        return List.copyOf(out);
    }

    /**
     * The module's test source folders: the layout's, and {@code src/test/kotlin}, {@code groovy} or {@code scala} for those languages in the Maven layout.
     */
    public List<Path> testSourceDirs(Path moduleDir) {
        List<Path> out = new java.util.ArrayList<>(List.of(layout.tests(moduleDir)));
        if (layout == Layout.MAVEN) {
            if (kotlin != null) out.add(moduleDir.resolve("src/test/kotlin"));
            if (groovy != null) out.add(moduleDir.resolve("src/test/groovy"));
            if (scala != null) out.add(moduleDir.resolve("src/test/scala"));
        }
        return List.copyOf(out);
    }

    /**
     * The module's resource folders: {@code "resources"}, or the layout's one.
     */
    public List<Path> resourceDirs(Path moduleDir) {
        if (resources.isEmpty()) return List.of(layout.resources(moduleDir));
        return resources.stream().map(moduleDir::resolve).toList();
    }

    /**
     * Major Java version requested ("graalvm-21" gives 21), or null for "any".
     */
    public Integer javaMajor() {
        if (java == null) return null;
        String digits = java.replaceAll(".*?(\\d+).*", "$1");
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new JpmException(file + ": invalid java version \"" + java + "\"");
        }
    }

    /**
     * Bytecode target, defaults to the java version.
     */
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
     * The dependencies of one section.
     */
    public List<Dependency> deps(Scope s) {
        return dependencies.stream().filter(d -> d.scope == s).toList();
    }
}
