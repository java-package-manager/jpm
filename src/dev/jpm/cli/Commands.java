package dev.jpm.cli;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.build.Compiler;
import dev.jpm.build.Env;
import dev.jpm.build.Jars;
import dev.jpm.build.Patches;
import dev.jpm.build.Scripts;
import dev.jpm.build.TestRunner;
import dev.jpm.git.GitRepo;
import dev.jpm.toolchain.Foojay;
import dev.jpm.toolchain.JavaSpec;
import dev.jpm.toolchain.Jdk;
import dev.jpm.toolchain.Toolchain;
import dev.jpm.json.Jsonc;
import dev.jpm.json.JsoncEdit;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Lockfile;
import dev.jpm.resolve.GitModule;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Install;
import dev.jpm.resolve.Resolver;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.util.Log;
import dev.jpm.version.MavenRange;
import dev.jpm.version.NpmRange;
import dev.jpm.version.Version;
import dev.jpm.version.VersionSpec;
import dev.jpm.version.VersionSpecs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Implementations of the CLI commands. */
final class Commands {
    private final Path cwd;

    Commands() {
        this(Path.of("").toAbsolutePath());
    }

    /** For tests: commands run as if started in {@code cwd}. */
    Commands(Path cwd) {
        this.cwd = cwd;
    }

    // ---------------------------------------------------------------- helpers

    private static boolean flag(List<String> args, String... names) {
        boolean found = false;
        for (String n : names) found |= args.remove(n);
        return found;
    }

    private static String option(List<String> args, String name) {
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals(name) && i + 1 < args.size()) {
                args.remove(i);
                return args.remove(i);
            }
            if (a.startsWith(name + "=")) {
                args.remove(i);
                return a.substring(name.length() + 1);
            }
        }
        return null;
    }

    /** Splits off everything after "--". */
    private static List<String> passthrough(List<String> args) {
        int i = args.indexOf("--");
        if (i < 0) return new ArrayList<>();
        List<String> rest = new ArrayList<>(args.subList(i + 1, args.size()));
        args.subList(i, args.size()).clear();
        return rest;
    }

    private static void noMoreFlags(List<String> args) {
        for (String a : args) if (a.startsWith("-")) throw new JpmException("Unknown option " + a);
    }

    private record Ctx(Project project, MavenRepo repo, Graph graph) {
        Builder builder(Env.Mode mode) {
            return new Builder(project, graph, repo, mode);
        }
    }

    private Ctx open(boolean frozen) {
        Project p = Project.find(cwd);
        MavenRepo repo = new MavenRepo(p.root.repositories);
        Graph g = Install.run(p, repo, new Install.Options(frozen, false, Set.of(), true));
        return new Ctx(p, repo, g);
    }

    /** Modules a command applies to: the one at the working directory, or all of them from the workspace root. */
    private List<Project.Module> targets(Project p, List<String> names) {
        if (!names.isEmpty()) {
            List<Project.Module> out = new ArrayList<>();
            for (String n : names) {
                Project.Module m = p.module(n);
                if (m == null) throw new JpmException("No module named \"" + n + "\"");
                out.add(m);
            }
            return out;
        }
        Project.Module here = p.moduleAt(cwd);
        if (here != null) return List.of(here);
        return p.buildOrder();
    }

    private Project.Module single(Project p, String what) {
        Project.Module here = p.moduleAt(cwd);
        if (here != null) return here;
        List<Project.Module> withMain = p.modules.stream().filter(m -> !m.manifest.mains.isEmpty()).toList();
        if (withMain.size() == 1 && what.equals("run")) return withMain.get(0);
        throw new JpmException("This is a workspace; run `jpm " + what + "` inside a module (" + String.join(", ",
                p.modules.stream().map(Project.Module::relDir).toList()) + ")");
    }

    // ---------------------------------------------------------------- init

    int init(List<String> args) {
        noMoreFlags(args);
        if (Files.exists(cwd.resolve(Manifest.FILE))) throw new JpmException(Manifest.FILE + " already exists here");
        String name = args.isEmpty() ? cwd.getFileName().toString() : args.get(0);
        name = name.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("^[^A-Za-z0-9]+", "");
        if (name.isEmpty()) name = "app";
        String pkg = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (pkg.isEmpty() || !Character.isLetter(pkg.charAt(0))) pkg = "app" + pkg;
        int major = 21;
        try {
            Jdk found = Toolchain.find(JavaSpec.any());
            major = found == null ? 21 : found.major();
        } catch (JpmException ignored) {
        }
        String manifest = """
                {
                  "$schema": "%s",
                  "name": "%s",
                  "version": "0.1.0",
                  %s"main": "%s.App",
                  "runtime": {},
                  "test": {}
                }
                """.formatted(Manifest.SCHEMA_URL, name, "\"java\": \"" + major + "\",\n  ", pkg);
        Fs.write(cwd.resolve(Manifest.FILE), manifest);
        Path app = cwd.resolve("src").resolve(pkg).resolve("App.java");
        if (!Files.exists(app)) {
            Fs.write(app, """
                    package %s;

                    public class App {
                        public static String greeting() {
                            return "Hello from %s!";
                        }

                        public static void main(String[] args) {
                            System.out.println(greeting());
                        }
                    }
                    """.formatted(pkg, name));
        }
        Path test = cwd.resolve("test").resolve(pkg).resolve("AppTest.java");
        if (!Files.exists(test)) {
            Fs.write(test, """
                    package %s;

                    import static org.junit.jupiter.api.Assertions.assertEquals;

                    import org.junit.jupiter.api.Test;

                    class AppTest {
                        @Test
                        void greets() {
                            assertEquals("Hello from %s!", App.greeting());
                        }
                    }
                    """.formatted(pkg, name));
        }
        Path gi = cwd.resolve(".gitignore");
        String ignore = ".cache/\ndist/\n.env.local\n";
        if (!Files.exists(gi)) Fs.write(gi, ignore);
        Log.step("Created", Manifest.FILE + ", src/" + pkg + "/App.java and test/" + pkg + "/AppTest.java");
        Log.info("Next: `jpm start` to run it, `jpm test` to test it.");
        return 0;
    }

    // ---------------------------------------------------------------- add / remove

    private record DepArg(DepKey key, String spec) {}

    static DepArg parseDepArg(String s) {
        String keyPart = s, spec = null;
        int hash = s.indexOf('#');
        int at = s.indexOf('@');
        if (hash > 0 && (at < 0 || hash < at)) {
            keyPart = s.substring(0, hash);
            spec = s.substring(hash);
        } else if (at > 0) {
            keyPart = s.substring(0, at);
            spec = s.substring(at + 1);
        } else if (!s.startsWith("github:") && !s.startsWith("gitlab:") && !s.startsWith("codeberg:")
                && !s.startsWith("workspace:")) {
            // g:a:version, g:a:classifier, or g:a:classifier:version
            String[] parts = s.split(":");
            if ((parts.length == 3 && !parts[2].isEmpty() && Character.isDigit(parts[2].charAt(0))) || parts.length == 4) {
                int last = s.lastIndexOf(':');
                keyPart = s.substring(0, last);
                spec = s.substring(last + 1);
            }
        }
        return new DepArg(DepKey.parse(keyPart), spec == null || spec.isBlank() ? null : spec);
    }

    private String latestSpec(MavenRepo repo, DepKey key) {
        if (key.isGradle()) return dev.jpm.resolve.GradleDist.latest();
        if (key.isExtension()) return dev.jpm.resolve.ExtensionDeps.resolve(key, "latest").version();
        if (key.isMaven()) {
            List<Version> all = repo.versions(key.group(), key.artifact());
            Version best = null;
            for (Version v : all) if (!v.isPreRelease() && !v.isSnapshot() && (best == null || v.compareTo(best) > 0)) best = v;
            if (best == null) best = all.get(all.size() - 1);
            return best.toString();
        }
        if (key.isGit()) {
            GitRepo git = GitRepo.of(key);
            Map<String, String> tags = git.tags();
            Version best = null;
            for (String t : tags.keySet()) {
                String v = tagVersion(key, t, tags.keySet());
                if (v == null) continue;
                Version vv = Version.of(v);
                if (!vv.isPreRelease() && (best == null || vv.compareTo(best) > 0)) best = vv;
            }
            if (best != null) return "^" + best;
            return "#" + git.defaultBranch();
        }
        return "*";
    }

    private static String tagVersion(DepKey key, String tag, Collection<String> all) {
        String module = key.module();
        boolean prefixed = module != null && all.stream().anyMatch(t -> t.startsWith(module + "/"));
        String v = tag;
        if (prefixed) {
            if (!tag.startsWith(module + "/")) return null;
            v = tag.substring(module.length() + 1);
        } else if (tag.contains("/")) return null;
        if (v.startsWith("v")) v = v.substring(1);
        return v.matches("\\d+(\\.\\d+){0,2}(-[0-9A-Za-z.-]+)?") ? v : null;
    }

    /** Collects every value of a repeatable option such as {@code -r url -r url2}. */
    private static List<String> options(List<String> args, String... names) {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            String v;
            while ((v = option(args, n)) != null) out.add(v);
        }
        return out;
    }

    /** Adds Maven repository URLs to a manifest's "repositories" list, keeping the ones already there. */
    static String withRepositories(String src, Manifest m, List<String> urls) {
        List<String> all = new ArrayList<>(m.repositories);
        boolean changed = false;
        for (String u : urls) {
            String url = u.trim();
            if (!url.matches("(?i)(https?|file)://.+")) {
                throw new JpmException("Invalid repository URL \"" + u + "\": expected https://..., http://... or file://...");
            }
            String norm = url.replaceAll("/+$", "");
            boolean present = all.stream().anyMatch(x -> x.replaceAll("/+$", "").equalsIgnoreCase(norm));
            if (!present) {
                all.add(norm);
                changed = true;
                Log.step("Repository", norm);
            }
        }
        if (!changed) return src;
        StringBuilder raw = new StringBuilder("[");
        for (int i = 0; i < all.size(); i++) raw.append(i == 0 ? "" : ", ").append(Jsonc.quote(all.get(i)));
        return JsoncEdit.set(src, List.of(), "repositories", raw.append(']').toString());
    }

    int add(List<String> args) {
        boolean dev = flag(args, "--dev", "-D");
        boolean test = flag(args, "--test", "-T");
        boolean global = flag(args, "-g", "--global");
        List<String> repoUrls = options(args, "-r", "--repo", "--repository");
        noMoreFlags(args);
        if (args.isEmpty()) throw new JpmException("Usage: jpm add [-r <repository url>] <dependency>[@version] [--dev | --test]");
        if (dev && test) throw new JpmException("Choose one of --dev and --test");
        if (global) return addGlobal(args);
        Manifest.Scope scope = dev ? Manifest.Scope.DEV : test ? Manifest.Scope.TEST : Manifest.Scope.RUNTIME;
        Project p = Project.find(cwd);
        Project.Module m = single(p, "add");
        // Repositories are read from the root manifest (the workspace root in a workspace).
        Path rootFile = p.root.file, moduleFile = m.manifest.file;
        String rootOriginal = Fs.read(rootFile);
        String moduleOriginal = Fs.read(moduleFile);
        try {
            if (!repoUrls.isEmpty()) {
                String rootSrc = withRepositories(rootOriginal, p.root, repoUrls);
                if (!rootSrc.equals(rootOriginal)) {
                    Fs.write(rootFile, rootSrc);
                    p = Project.find(cwd);
                    m = single(p, "add");
                }
            }
            MavenRepo repo = new MavenRepo(p.root.repositories);
            String src = Fs.read(moduleFile);
            List<Manifest.Dependency> declared = m.manifest.dependencies;
            for (String a : args) {
                DepArg d = parseDepArg(a);
                if (d.key.isWorkspace() && p.module(d.key.artifact()) == null) {
                    throw new JpmException("No workspace module named \"" + d.key.artifact() + "\"");
                }
                String spec = d.spec != null ? d.spec : d.key.isWorkspace() ? "*" : latestSpec(repo, d.key);
                if (d.key.isMaven()) {
                    try {
                        VersionSpecs.parse(spec);
                    } catch (IllegalArgumentException e) {
                        throw new JpmException(e.getMessage());
                    }
                }
                // adding to a scope moves the dependency there, except that dev and test may share it (a
                // compile-only dependency tests run with); the other entry then gets the same version
                Manifest.Scope pair = scope == Manifest.Scope.DEV ? Manifest.Scope.TEST : scope == Manifest.Scope.TEST ? Manifest.Scope.DEV : null;
                for (Manifest.Scope s : Manifest.Scope.values()) {
                    if (s == scope) continue;
                    if (s == pair && declared.stream().anyMatch(x -> x.key().equals(d.key) && x.scope() == pair)) {
                        src = setSpec(src, s.key, d.key.toString(), spec);
                    } else {
                        src = JsoncEdit.remove(src, List.of(s.key), d.key.toString());
                    }
                }
                src = JsoncEdit.set(src, List.of(scope.key), d.key.toString(), Jsonc.quote(spec));
                Log.step("Adding", d.key + " " + spec + (scope == Manifest.Scope.RUNTIME ? "" : " (" + scope.key + ")"));
            }
            Fs.write(moduleFile, src);
            Install.run(Project.find(cwd), repo, new Install.Options(false, false, Set.of(), false));
        } catch (RuntimeException e) {
            // leave the manifests exactly as they were
            Fs.write(moduleFile, moduleOriginal);
            if (!rootFile.equals(moduleFile)) Fs.write(rootFile, rootOriginal);
            String msg = e instanceof JpmException ? e.getMessage() : "unexpected failure: " + e;
            throw new JpmException(msg + "\n" + Manifest.FILE + " was left unchanged.", e);
        }
        return 0;
    }

    /** Sets a declared dependency's version, inside its options object when it has one. */
    private static String setSpec(String src, String scope, String key, String spec) {
        Jsonc.Node scopeNode = Jsonc.parseNode(src).get(scope);
        Jsonc.Node value = scopeNode == null ? null : scopeNode.get(key);
        return value != null && value.isObject()
                ? JsoncEdit.set(src, List.of(scope, key), "version", Jsonc.quote(spec))
                : JsoncEdit.set(src, List.of(scope), key, Jsonc.quote(spec));
    }

    int remove(List<String> args) {
        boolean global = flag(args, "-g", "--global");
        noMoreFlags(args);
        if (args.isEmpty()) throw new JpmException("Usage: jpm remove <dependency>");
        if (global) return removeGlobal(args);
        Project p = Project.find(cwd);
        Project.Module m = single(p, "remove");
        String src = Fs.read(m.manifest.file);
        for (String a : args) {
            DepKey key = parseDepArg(a).key;
            boolean found = false;
            for (Manifest.Scope s : Manifest.Scope.values()) {
                String next = JsoncEdit.remove(src, List.of(s.key), key.toString());
                if (!next.equals(src)) { found = true; src = next; }
            }
            if (!found) throw new JpmException(key + " is not a dependency in " + m.manifest.file);
            Log.step("Removing", key.toString());
        }
        Fs.write(m.manifest.file, src);
        Project fresh = Project.find(cwd);
        Install.run(fresh, new MavenRepo(fresh.root.repositories), new Install.Options(false, false, Set.of(), false));
        return 0;
    }

    // ---------------------------------------------------------------- install / update / outdated / why

    int install(List<String> args) {
        boolean frozen = flag(args, "--frozen", "--frozen-lockfile");
        noMoreFlags(args);
        Project p = Project.find(cwd);
        String before = Files.exists(p.lockFile()) ? Fs.read(p.lockFile()) : null;
        Graph g = Install.run(p, new MavenRepo(p.root.repositories), new Install.Options(frozen, false, Set.of(), false));
        if (before != null && before.equals(Fs.read(p.lockFile()))) {
            long n = g.pkgs.values().stream().filter(x -> x.kind != Graph.Kind.LOCAL).count();
            Log.step("Up to date", n + " package" + (n == 1 ? "" : "s") + " in " + Manifest.LOCK);
        }
        return 0;
    }

    int update(List<String> args) {
        boolean latest = flag(args, "--latest");
        boolean global = flag(args, "-g", "--global");
        noMoreFlags(args);
        if (global) return updateGlobal(args, latest);
        Project p = Project.find(cwd);
        MavenRepo repo = new MavenRepo(p.root.repositories);
        repo.setRefreshMetadata(true);
        Set<String> only = new LinkedHashSet<>();
        for (String a : args) only.add(parseDepArg(a).key.toString());
        if (latest) {
            for (Project.Module m : p.modules) {
                String src = Fs.read(m.manifest.file);
                for (Manifest.Dependency d : m.manifest.dependencies) {
                    if (d.key().isWorkspace() || (!only.isEmpty() && !only.contains(d.key().toString()))) continue;
                    if (d.key().isGit() && (d.spec().startsWith("#") || d.spec().matches("[0-9a-f]{7,40}"))) continue;
                    String spec = latestSpec(repo, d.key());
                    if (!spec.equals(d.spec())) {
                        Log.step("Updating", d.key() + " " + d.spec() + " -> " + spec);
                        src = JsoncEdit.set(src, List.of(d.scope().key), d.key().toString(), Jsonc.quote(spec));
                    }
                }
                Fs.write(m.manifest.file, src);
            }
            p = Project.find(cwd);
        }
        Install.run(p, repo, new Install.Options(false, only.isEmpty(), only, false));
        return 0;
    }

    int outdated(List<String> args) {
        noMoreFlags(args);
        Ctx c = open(false);
        c.repo.setRefreshMetadata(true);
        List<String[]> rows = new ArrayList<>();
        for (Project.Module m : c.project.modules) {
            for (Manifest.Dependency d : m.manifest.dependencies) {
                if (d.key().isWorkspace()) continue;
                Graph.Pkg pkg = c.graph.get(d.key().toString());
                if (pkg == null) continue;
                String latest, wanted;
                if (d.key().isGradle() || d.key().isExtension()) {
                    latest = latestSpec(c.repo, d.key());
                    wanted = d.spec();
                } else if (d.key().isMaven()) {
                    List<Version> all = c.repo.versions(d.key().group(), d.key().artifact());
                    latest = latestSpec(c.repo, d.key());
                    Optional<Version> w = VersionSpecs.parse(d.spec()).best(all);
                    wanted = w.map(Version::toString).orElse("-");
                } else {
                    String l = latestSpec(c.repo, d.key());
                    latest = l.startsWith("^") ? l.substring(1) : l;
                    if (VersionSpecs.looksLikeSpec(d.spec())) {
                        List<Version> vs = new ArrayList<>();
                        Map<String, String> tags = GitRepo.of(d.key()).tags();
                        for (String t : tags.keySet()) {
                            String v = tagVersion(d.key(), t, tags.keySet());
                            if (v != null) vs.add(Version.of(v));
                        }
                        VersionSpec spec = VersionSpecs.parse(d.spec(), true);
                        wanted = spec.best(vs).map(Version::toString).orElse("-");
                    } else {
                        wanted = pkg.version;
                    }
                }
                if (!Version.of(pkg.version).equals(Version.of(latest)) || !Version.of(pkg.version).equals(Version.of(wanted))) {
                    rows.add(new String[]{d.key().toString(), pkg.version, wanted, latest, m.name() + " (" + d.scope().key + ")"});
                }
            }
        }
        if (rows.isEmpty()) {
            Log.info("All dependencies are up to date.");
            return 0;
        }
        rows.add(0, new String[]{"Package", "Current", "Wanted", "Latest", "Used by"});
        int[] w = new int[5];
        for (String[] r : rows) for (int i = 0; i < 5; i++) w[i] = Math.max(w[i], r[i].length());
        for (int i = 0; i < rows.size(); i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 5; j++) sb.append(String.format("%-" + (w[j] + 2) + "s", rows.get(i)[j]));
            System.out.println(i == 0 ? Log.bold(sb.toString().strip()) : sb.toString().strip());
        }
        return 0;
    }

    int why(List<String> args) {
        noMoreFlags(args);
        if (args.size() != 1) throw new JpmException("Usage: jpm why <dependency>");
        Ctx c = open(false);
        String id = parseDepArg(args.get(0)).key.toString();
        Graph.Pkg target = c.graph.get(id);
        if (target == null) {
            Log.info(id + " is not in the dependency graph.");
            return 1;
        }
        Map<String, Collection<Graph.Edge>> roots = new LinkedHashMap<>();
        for (Project.Module m : c.project.modules) {
            for (Manifest.Scope s : Manifest.Scope.values()) {
                List<Graph.Edge> e = Resolver.rootEdges(m, s);
                if (!e.isEmpty()) roots.put(m.name() + " (" + s.key + ")", e);
            }
        }
        List<List<String>> paths = c.graph.paths(roots, id);
        System.out.println(Log.bold(id + " " + target.version) + (target.commit != null ? " (" + target.commit.substring(0, 7) + ")" : ""));
        for (List<String> path : paths) {
            StringBuilder sb = new StringBuilder("  ").append(path.get(0));
            for (int i = 1; i < path.size(); i++) {
                Graph.Pkg p = c.graph.get(path.get(i));
                sb.append(" > ").append(path.get(i));
                if (p != null && p.kind != Graph.Kind.LOCAL) sb.append(' ').append(p.version);
            }
            System.out.println(sb);
        }
        return 0;
    }

    // ---------------------------------------------------------------- build / start / dev / test / run / clean

    int build(List<String> args) {
        boolean release = flag(args, "--release");
        boolean nativeImage = flag(args, "--native");
        boolean bundleFlag = flag(args, "--bundle");
        boolean flat = flag(args, "--flat");
        boolean frozen = flag(args, "--frozen");
        String profile = option(args, "--profile");
        if (release && profile == null) profile = "release";
        noMoreFlags(args);
        Ctx c = open(frozen);
        Builder b = c.builder(Env.Mode.PRODUCTION);
        List<Project.Module> mods = targets(c.project, args);
        boolean all = mods.size() == c.project.modules.size() && c.project.root.isWorkspaceRoot();
        Project.Module rootMod = c.project.rootModule();
        if (all) Scripts.hooks(b, rootMod, c.project.root, "pre", "build", profile, Env.Mode.PRODUCTION);
        for (Project.Module m : mods) {
            Builder mb = b.forModule(m);
            Scripts.hooks(mb, m, m.manifest, "pre", "build", profile, Env.Mode.PRODUCTION);
            mb.jar(m);
            if (bundleFlag || flat || m.manifest.packaging.equals("bundle")) {
                // a runnable jar-in-jar for apps; one flat jar for modules without a main (plugins) or with --flat
                if (flat || m.manifest.mains.isEmpty()) mb.flatBundle(m);
                else mb.bundle(m);
            }
            if (nativeImage) {
                if (!m.manifest.mains.isEmpty()) mb.nativeImage(m, null);
                else if (mods.size() == 1) throw new JpmException(m.manifest.file + " has no \"main\" to build natively");
            }
            Scripts.hooks(mb, m, m.manifest, "post", "build", profile, Env.Mode.PRODUCTION);
        }
        if (all) Scripts.hooks(b, rootMod, c.project.root, "post", "build", profile, Env.Mode.PRODUCTION);
        return 0;
    }

    int start(List<String> args, boolean dev) {
        List<String> appArgs = passthrough(args);
        boolean trace = flag(args, "--trace");
        noMoreFlags(args);
        if (trace && dev) throw new JpmException("--trace works with `jpm start`, not `jpm dev`");
        String entry = args.isEmpty() ? null : args.remove(0);
        appArgs.addAll(0, args);
        Env.Mode mode = dev ? Env.Mode.DEVELOPMENT : Env.Mode.PRODUCTION;
        if (!dev) {
            Ctx c = open(false);
            Project.Module m = single(c.project, "run");
            Builder b = c.builder(mode).forModule(m);
            Scripts.hooks(b, m, m.manifest, "pre", "start", null, mode);
            List<String> cmd = javaCommand(b, m, entry, appArgs, trace);
            int code = Exec.inherit(m.dir, cmd, Env.forChild(c.project.dir, mode));
            if (trace) {
                Log.step("Traced", "native-image configuration merged into "
                        + m.dir.relativize(traceDir(m)) + "; `jpm build --native` uses it");
            }
            return code;
        }
        return new DevLoop(this, entry, appArgs).run();
    }

    /** Builds and returns the command that runs a module's main. Package-visible for DevLoop. */
    List<String> javaCommand(Builder b, Project.Module m, String entry, List<String> appArgs) {
        return javaCommand(b, m, entry, appArgs, false);
    }

    /** Where the tracing agent writes configuration: inside src/assets/, so it ships in the jar. */
    static Path traceDir(Project.Module m) {
        String group = m.manifest.group != null ? m.manifest.group : m.name();
        return m.assetsDir().resolve("META-INF").resolve("native-image").resolve(group).resolve(m.name());
    }

    List<String> javaCommand(Builder b, Project.Module m, String entry, List<String> appArgs, boolean trace) {
        String main = mainClass(m, entry);
        b.compileMain(m);
        Jdk jdk = trace ? b.graalvm(m.manifest) : b.jdk(m.manifest);
        List<String> cmd = new ArrayList<>();
        cmd.add(jdk.java());
        if (trace) {
            // merge into an earlier trace, or write a fresh one the first time
            boolean earlier = Files.exists(traceDir(m).resolve("reflect-config.json"));
            Fs.mkdirs(traceDir(m));
            cmd.add("-agentlib:native-image-agent=config-" + (earlier ? "merge" : "output") + "-dir=" + traceDir(m));
        }
        String opts = System.getenv("JPM_JVM_ARGS");
        if (opts != null && !opts.isBlank()) cmd.addAll(List.of(opts.trim().split("\\s+")));
        cmd.addAll(launchArgs(b, m, main));
        cmd.addAll(appArgs);
        return cmd;
    }

    /**
     * How to start a module's main class. A named module (module-info.class in its classes) runs as a module,
     * so `provides`, `uses`, exports and strong encapsulation behave as declared; anything else runs on the
     * class path.
     */
    static List<String> launchArgs(Builder b, Project.Module m, String main) {
        Path classes = Builder.classesDir(m);
        String module = dev.jpm.build.Modules.declaredName(classes);
        List<String> args = new ArrayList<>();
        if (module == null) {
            args.add("-cp");
            args.add(Compiler.join(b.runtimeClasspath(m)));
            args.add(main);
            return args;
        }
        Path moduleInfo = dev.jpm.build.Modules.moduleInfo(m.srcDirs());
        dev.jpm.build.Modules.Declaration decl = moduleInfo != null ? dev.jpm.build.Modules.parse(moduleInfo)
                : new dev.jpm.build.Modules.Declaration(module, Set.of());
        List<List<Path>> split = dev.jpm.build.Modules.split(decl, b.classpath(m, Builder.Cp.RUNTIME));
        List<Path> modulePath = new ArrayList<>();
        modulePath.add(classes);
        modulePath.addAll(split.get(0));
        args.add("--module-path");
        args.add(Compiler.join(modulePath));
        if (!split.get(1).isEmpty()) {
            args.add("-cp");
            args.add(Compiler.join(split.get(1)));
            args.add("--add-reads");
            args.add(module + "=ALL-UNNAMED");
        }
        // resolve every module on the path, so services they provide are found too
        args.add("--add-modules");
        args.add("ALL-MODULE-PATH");
        args.add("-m");
        args.add(module + "/" + main);
        return args;
    }

    private static String mainClass(Project.Module m, String entry) {
        Map<String, String> mains = m.manifest.mains;
        if (entry != null) {
            if (mains.containsKey(entry)) return mains.get(entry);
            if (entry.contains(".")) return entry;
            throw new JpmException("No entry point named \"" + entry + "\" in " + m.manifest.file
                    + (mains.isEmpty() ? "" : ". Entry points: " + String.join(", ", mains.keySet())));
        }
        if (mains.isEmpty()) throw new JpmException(m.manifest.file + " has no \"main\"");
        if (mains.size() == 1) return mains.values().iterator().next();
        if (mains.containsKey("main")) return mains.get("main");
        throw new JpmException(m.name() + " has several entry points; pick one: jpm start <" + String.join("|", mains.keySet()) + ">");
    }

    Ctx openForDev() {
        return open(false);
    }

    Project findProject() {
        return Project.find(cwd);
    }

    Project.Module singleForRun(Project p) {
        return single(p, "run");
    }

    Path cwd() {
        return cwd;
    }

    int test(List<String> args) {
        boolean frozen = flag(args, "--frozen");
        String module = option(args, "--module");
        noMoreFlags(args);
        Ctx c = open(frozen);
        Builder b = c.builder(Env.Mode.DEVELOPMENT);
        List<Project.Module> mods = module != null ? targets(c.project, List.of(module)) : targets(c.project, List.of());
        int worst = 0;
        for (Project.Module m : mods) {
            Builder mb = b.forModule(m);
            Scripts.hooks(mb, m, m.manifest, "pre", "test", null, Env.Mode.DEVELOPMENT);
            int code = TestRunner.run(mb, m, args);
            if (code != 0) worst = code;
            else Scripts.hooks(mb, m, m.manifest, "post", "test", null, Env.Mode.DEVELOPMENT);
        }
        return worst;
    }

    int runScript(List<String> args) {
        if (args.isEmpty()) {
            Project p = Project.find(cwd);
            Project.Module m = p.moduleAt(cwd) != null ? p.moduleAt(cwd) : p.rootModule();
            if (m.manifest.scripts.isEmpty()) {
                Log.info("No scripts in " + m.manifest.file);
            } else {
                for (Map.Entry<String, String> e : m.manifest.scripts.entrySet()) {
                    System.out.println(Log.bold(e.getKey()) + "  " + Log.dim(e.getValue()));
                }
            }
            return 0;
        }
        String name = args.remove(0);
        if (!args.isEmpty() && args.get(0).equals("--")) args.remove(0);
        Ctx c = open(false);
        Project.Module m = c.project.moduleAt(cwd);
        // <module>:<script> runs another module's script from anywhere in the workspace
        int colon = name.indexOf(':');
        if (colon > 0 && !name.endsWith(":") && c.project.module(name.substring(0, colon)) != null) {
            m = c.project.module(name.substring(0, colon));
            name = name.substring(colon + 1);
        } else if (m == null || (!m.manifest.scripts.containsKey(name) && c.project.root.scripts.containsKey(name))) {
            m = c.project.rootModule();
        }
        Builder b = c.builder(Env.Mode.DEVELOPMENT).forModule(m);
        return Scripts.run(b, m, name, args, Env.Mode.DEVELOPMENT);
    }

    int clean(List<String> args) {
        noMoreFlags(args);
        Project p = Project.find(cwd);
        Fs.deleteTree(p.dir.resolve(".cache").resolve("build"));
        for (Project.Module m : p.modules) Fs.deleteTree(m.dir.resolve("dist"));
        Log.step("Cleaned", "build output");
        return 0;
    }

    // ---------------------------------------------------------------- info

    /**
     * Packages on a module's classpaths with the narrowest IDE scope that covers them: compile (needed to
     * compile and run), provided (compile only: dev dependencies), runtime (run only) and test.
     */
    private List<Object> ideDependencies(Ctx c, Builder b, Project.Module m) {
        Graph graph = b.graph;
        List<Graph.Edge> runtime = dev.jpm.resolve.Resolver.rootEdges(m, Manifest.Scope.RUNTIME);
        List<Graph.Edge> devOnly = dev.jpm.resolve.Resolver.rootEdges(m, Manifest.Scope.DEV);
        List<Graph.Edge> test = dev.jpm.resolve.Resolver.rootEdges(m, Manifest.Scope.TEST);
        Map<String, String> scopes = new LinkedHashMap<>();
        for (String id : graph.closure(runtime, Graph.Walk.COMPILE)) scopes.putIfAbsent(id, "compile");
        for (String id : graph.closure(runtime, Graph.Walk.RUNTIME)) scopes.putIfAbsent(id, "runtime");
        for (String id : graph.closure(devOnly, Graph.Walk.ALL)) {
            if ("runtime".equals(scopes.get(id))) scopes.put(id, "compile");
            else scopes.putIfAbsent(id, "provided");
        }
        for (String id : graph.closure(test, Graph.Walk.ALL)) scopes.putIfAbsent(id, "test");
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, String> e : scopes.entrySet()) {
            Graph.Pkg p = graph.get(e.getKey());
            if (p == null) continue;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", p.id);
            d.put("scope", e.getValue());
            if (p.kind == Graph.Kind.LOCAL) {
                d.put("module", p.id.substring("workspace:".length()));
            } else {
                d.put("version", p.version);
                List<String> jars = b.paths(p.id, m).stream().map(Path::toString).toList();
                if (jars.isEmpty()) continue;
                d.put("jars", jars);
                Path sources = null;
                if (p.kind == Graph.Kind.MAVEN) {
                    DepKey k = DepKey.parse(p.id);
                    if (k.classifier() == null) sources = c.repo().fetch(k.group(), k.artifact(), p.version, "sources", "jar");
                } else if (p.kind == Graph.Kind.EXTENSION) {
                    sources = dev.jpm.resolve.ExtensionDeps.resolve(DepKey.parse(p.id), p.version).sources();
                } else if (p.dir != null) {
                    for (String rel : List.of("src/main/java", "src")) {
                        if (Files.isDirectory(p.dir.resolve(rel))) { sources = p.dir.resolve(rel); break; }
                    }
                }
                if (sources != null) d.put("sources", sources.toString());
            }
            out.add(d);
        }
        return out;
    }

    /**
     * {@code --classpath}: each of a module's classpaths (compile, processor, runtime, testCompile,
     * testRuntime) as packages in classpath order. A package has its id, kind (maven, git, patched or
     * workspace), version, Maven coordinates, its jars (for workspace modules, their classes directory and
     * jar) and its dependency edges as in project.lock. No sources jars are
     * downloaded.
     */
    private Map<String, Object> classpaths(Ctx c, Builder b, Project.Module m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Builder.Cp cp : Builder.Cp.values()) {
            String name = switch (cp) {
                case COMPILE -> "compile";
                case PROCESSOR -> "processor";
                case RUNTIME -> "runtime";
                case TEST_COMPILE -> "testCompile";
                case TEST_RUNTIME -> "testRuntime";
            };
            List<Object> pkgs = new ArrayList<>();
            for (String id : b.classpathIds(m, cp)) {
                Graph.Pkg p = c.graph.get(id);
                if (p == null) continue;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", p.id);
                if (p.kind == Graph.Kind.LOCAL) {
                    Project.Module sib = c.project.module(p.id.substring("workspace:".length()));
                    d.put("kind", "workspace");
                    d.put("module", sib.name());
                    if (sib.manifest.group != null) d.put("group", sib.manifest.group);
                    d.put("artifact", sib.name());
                    d.put("version", sib.version());
                    d.put("classes", Builder.classesDir(sib).toString());
                    d.put("jar", b.jarPath(sib).toString());
                } else {
                    boolean patched = b.isPatched(p.id);
                    d.put("kind", patched ? "patched" : p.kind == Graph.Kind.GIT ? "git" : p.kind == Graph.Kind.GRADLE ? "gradle" : p.kind == Graph.Kind.EXTENSION ? "extension" : "maven");
                    d.put("version", p.version);
                    if (p.kind == Graph.Kind.MAVEN) {
                        d.put("group", p.group());
                        d.put("artifact", p.name());
                        if (p.classifier() != null) d.put("classifier", p.classifier());
                    } else {
                        if (p.commit != null) d.put("commit", p.commit);
                        if (p.tag != null) d.put("tag", p.tag);
                    }
                    List<String> jars = b.paths(p.id, m).stream().map(Path::toString).toList();
                    d.put("jars", jars);
                    if (jars.size() == 1) d.put("jar", jars.get(0));
                }
                List<Object> edges = new ArrayList<>();
                for (Graph.Edge e : p.deps.values()) {
                    Map<String, Object> ed = new LinkedHashMap<>();
                    ed.put("id", e.target());
                    ed.put("scope", e.compileOnly() ? "provided" : e.runtimeOnly() ? "runtime" : "compile");
                    if (!e.exclusions().isEmpty()) ed.put("exclude", new ArrayList<>(new java.util.TreeSet<>(e.exclusions())));
                    edges.add(ed);
                }
                d.put("dependencies", edges);
                pkgs.add(d);
            }
            out.put(name, pkgs);
        }
        return out;
    }

    /** A module's resolved define values, by name (what its defines class holds). */
    private static Map<String, Object> defines(Builder b, Project.Module m) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            for (dev.jpm.build.Defines.Value v : b.defineValues(m)) out.put(v.name(), v.value());
        } catch (JpmException e) {
            out.put("$error", e.getMessage());
        }
        return out;
    }

    /**
     * {@code jpm info --json}: the project as extensions see it. The output is a stable contract: fields are
     * only ever added. With {@code --classpath}, each module also lists its compile classpath (which may
     * build Git dependencies first), every classpath as packages with coordinates, jars and edges (see
     * {@link #classpaths}), and its resolved define values. With {@code --ide}, each module also lists its test folders, the folders
     * an IDE should exclude, its scripts, the folder of its generated sources (written on the way, so the
     * defines class resolves without a build), and {@code ideDependencies}: every package on its classpaths with
     * the narrowest scope it is needed in, workspace modules as module references (nothing of the project is
     * compiled) and jars with their sources where available.
     */
    int info(List<String> args) {
        boolean json = flag(args, "--json");
        boolean withClasspath = flag(args, "--classpath");
        boolean ide = flag(args, "--ide");
        noMoreFlags(args);
        Ctx c = open(false);
        Builder b = c.builder(Env.Mode.PRODUCTION);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("infoVersion", 1);
        out.put("jpmVersion", Main.VERSION);
        out.put("projectDir", c.project.dir.toString());
        out.put("name", c.project.root.name);
        String rootVersion = Project.versionOverride() != null ? Project.versionOverride() : c.project.root.version;
        if (rootVersion != null) out.put("version", rootVersion);
        out.put("workspace", c.project.root.isWorkspaceRoot());
        out.put("repositories", c.project.root.repositories);
        out.put("extensions", c.project.root.extensions);
        if (ide) out.put("scripts", new ArrayList<>(c.project.root.scripts.keySet()));
        List<Object> modules = new ArrayList<>();
        Builder shared = b;
        for (Project.Module m : c.project.buildOrder()) {
            b = shared.forModule(m);
            Map<String, Object> mod = new LinkedHashMap<>();
            Manifest mf = m.manifest;
            if (mf.isolated) mod.put("isolated", true);
            mod.put("name", m.name());
            if (mf.group != null) mod.put("group", mf.group);
            mod.put("version", m.version());
            mod.put("dir", m.dir.toString());
            mod.put("relativeDir", m.relDir());
            mod.put("visibility", mf.visibility);
            mod.put("package", mf.packaging);
            if (mf.description != null) mod.put("description", mf.description);
            if (mf.license != null) mod.put("license", mf.license);
            if (mf.url != null) mod.put("url", mf.url);
            mod.put("main", new LinkedHashMap<>(mf.mains));
            mod.put("layout", mf.layout.name());
            mod.put("sourceDir", m.srcDir().toString());
            mod.put("resourceDir", m.assetsDir().toString());
            mod.put("sourceDirs", m.srcDirs().stream().map(Path::toString).toList());
            mod.put("resourceDirs", m.assetsDirs().stream().map(Path::toString).toList());
            mod.put("jar", b.jarPath(m).toString());
            mod.put("java", mf.java);
            try {
                Jdk jdk = b.jdk(mf);
                mod.put("javaHome", jdk.home().toString());
                Integer t = mf.targetMajor();
                mod.put("release", t == null ? jdk.major() : t);
            } catch (JpmException e) {
                mod.put("javaError", e.getMessage());
            }
            List<Object> deps = new ArrayList<>();
            for (Manifest.Dependency d : mf.dependencies) {
                Map<String, Object> dep = new LinkedHashMap<>();
                DepKey k = d.key();
                dep.put("key", k.toString());
                dep.put("kind", k.kind().name().toLowerCase());
                dep.put("scope", d.scope().key);
                dep.put("spec", d.spec());
                if (d.runtimeOnly()) dep.put("runtimeOnly", true);
                if (d.api()) dep.put("api", true);
                if (!d.exclusions().isEmpty()) dep.put("exclude", new ArrayList<>(d.exclusions()));
                switch (k.kind()) {
                    case MAVEN -> {
                        dep.put("group", k.group());
                        dep.put("artifact", k.artifact());
                        if (k.classifier() != null) dep.put("classifier", k.classifier());
                    }
                    case GIT -> dep.put("repository", k.repoKey().toString());
                    case WORKSPACE -> {
                        Project.Module sib = c.project.module(k.artifact());
                        dep.put("module", k.artifact());
                        if (sib != null) {
                            if (sib.manifest.group != null) dep.put("group", sib.manifest.group);
                            dep.put("artifact", sib.name());
                            dep.put("version", sib.version());
                        }
                    }
                }
                Graph.Pkg p = b.graph.get(k.toString());
                if (p != null && p.kind != Graph.Kind.LOCAL) {
                    dep.put("resolved", p.version);
                    if (p.commit != null) dep.put("commit", p.commit);
                }
                deps.add(dep);
            }
            mod.put("dependencies", deps);
            mod.put("extensions", mf.extensions);
            if (withClasspath) {
                mod.put("compileClasspath", b.classpath(m, Builder.Cp.COMPILE).stream().map(Path::toString).toList());
                mod.put("classpaths", classpaths(c, b, m));
                mod.put("defines", defines(b, m));
            }
            if (ide) {
                mod.put("testDir", m.testDir().toString());
                mod.put("testDirs", m.testDirs().stream().map(Path::toString).toList());
                mod.put("testResourceDir", m.testResourcesDir().toString());
                mod.put("excludeDirs", List.of(m.dir.resolve(".cache").toString(), m.dir.resolve("dist").toString()));
                mod.put("scripts", new ArrayList<>(mf.scripts.keySet()));
                try {
                    Path gen = b.generateSources(m);
                    if (gen != null) mod.put("generatedSourceDir", gen.toString());
                } catch (JpmException e) {
                    mod.put("generatedSourceError", e.getMessage());
                }
                mod.put("ideDependencies", ideDependencies(c, b, m));
            }
            modules.add(mod);
        }
        out.put("modules", modules);
        if (json) {
            System.out.println(Jsonc.write(out));
        } else {
            for (Object o : modules) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mod = (Map<String, Object>) o;
                System.out.println(Log.bold(mod.get("name") + " " + mod.get("version")) + "  " + mod.get("dir"));
                for (Object d : (List<?>) mod.get("dependencies")) {
                    Map<?, ?> dm = (Map<?, ?>) d;
                    System.out.println("  " + dm.get("scope") + "  " + dm.get("key") + " " + dm.get("spec")
                            + (dm.get("resolved") != null ? Log.dim(" -> " + dm.get("resolved")) : ""));
                }
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------- import

    int importBuild(List<String> args) {
        boolean dryRun = flag(args, "--dry-run", "-n");
        boolean force = flag(args, "--force", "-f");
        boolean noInstall = flag(args, "--no-install");
        boolean staticRead = flag(args, "--static");
        noMoreFlags(args);
        Path dir = args.isEmpty() ? cwd : cwd.resolve(args.get(0)).normalize();
        dev.jpm.importer.Imported imp = dev.jpm.importer.Importer.run(dir, new MavenRepo(List.of()), staticRead);
        if (!dryRun && !force) {
            for (String rel : imp.manifests.keySet()) {
                Path f = dir.resolve(rel).resolve(Manifest.FILE);
                if (Files.exists(f)) throw new JpmException(dir.relativize(f) + " already exists; use --force to overwrite it");
            }
        }
        for (String rel : imp.manifests.keySet()) {
            String text = imp.render(rel);
            Path f = dir.resolve(rel).resolve(Manifest.FILE);
            if (dryRun) {
                System.out.println(Log.bold("// " + (rel.isEmpty() ? "" : rel + "/") + Manifest.FILE));
                System.out.println(text);
            } else {
                Fs.write(f, text);
                Log.step("Imported", (rel.isEmpty() ? "" : rel + "/") + Manifest.FILE + " from " + imp.tool);
            }
        }
        for (String w : imp.warnings) Log.warn(w);
        if (dryRun || noInstall) return 0;
        Project p = Project.load(dir);
        Install.run(p, new MavenRepo(p.root.repositories), new Install.Options(false, false, Set.of(), false));
        Log.info("Next: `jpm build` and `jpm test`. Sources stay where they are (\"layout\": \"maven\").");
        return 0;
    }

    // ---------------------------------------------------------------- patch

    int patch(List<String> args) {
        boolean commit = flag(args, "--commit");
        noMoreFlags(args);
        if (args.size() != 1) throw new JpmException("Usage: jpm patch <dependency>  |  jpm patch --commit <dependency>");
        if (commit) {
            Patches.commit(Project.find(cwd), args.get(0));
            return 0;
        }
        Ctx c = open(false);
        Patches.start(c.project, c.graph, c.repo, args.get(0));
        return 0;
    }

    // ---------------------------------------------------------------- java

    int java(List<String> args, boolean asJvm) {
        return new JavaCommands(cwd, asJvm).run(args);
    }

    // ---------------------------------------------------------------- extensions

    static Path globalBin() {
        return Fs.home().resolve("bin");
    }

    int extension(String cmd, List<String> args) {
        if (!cmd.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) throw new JpmException("Unknown command \"" + cmd + "\". Run `jpm help`.");
        Path exe = dev.jpm.resolve.ExtensionDeps.executable(cmd);
        if (exe == null) {
            throw new JpmException("Unknown command \"" + cmd + "\". Run `jpm help`, or install an extension that provides it"
                    + " with `jpm add -g <dependency>` (it runs as jpm-" + cmd + ").");
        }
        Map<String, String> env = new LinkedHashMap<>();
        // so the extension can call back into this jpm (jpm build, jpm info --json)
        env.put("JPM_EXE", dev.jpm.util.Self.exe());
        env.put("JPM_VERSION_OF_JPM", Main.VERSION);
        try {
            Project p = Project.find(cwd);
            Project.Module m = p.moduleAt(cwd) != null ? p.moduleAt(cwd) : p.rootModule();
            env.put("JPM_PROJECT_DIR", p.dir.toString());
            env.put("JPM_MODULE_DIR", m.dir.toString());
            env.put("JPM_MANIFEST", m.manifest.file.toString());
            env.put("JPM_CONFIG", Jsonc.write(m.manifest.root.value).replaceAll("\n\\s*", " "));
            env.put("JPM_NAME", m.name());
            env.put("JPM_VERSION", m.version());
        } catch (JpmException e) {
            env.put("JPM_PROJECT_DIR", cwd.toString());
        }
        List<String> full = new ArrayList<>();
        full.add(exe.toString());
        full.addAll(args);
        return Exec.inherit(cwd, full, env);
    }

    private int addGlobal(List<String> args) {
        for (String a : args) {
            DepArg d = parseDepArg(a);
            if (d.key.isWorkspace()) throw new JpmException("Workspace modules cannot be installed globally");
            String spec = d.spec != null ? d.spec : latestSpec(new MavenRepo(List.of()), d.key);
            Graph.Pkg pkg = installGlobal(d.key, spec);
            String name = globalName(d.key);
            Log.step("Installed", "jpm-" + name + " (" + d.key + " " + describe(pkg) + "); run it with `jpm " + name + "`");
        }
        return 0;
    }

    /** The command name of a global extension: github:me/jpm-publish is `jpm publish`. */
    private static String globalName(DepKey key) {
        return key.mavenArtifact().replaceFirst("^jpm-", "");
    }

    private static String describe(Graph.Pkg pkg) {
        if (pkg == null) return "?";
        return pkg.version + (pkg.commit != null && !pkg.version.startsWith(pkg.commit.substring(0, 7)) ? " (" + pkg.commit.substring(0, 7) + ")" : "");
    }

    /**
     * Installs (or reinstalls) a global extension: a one-dependency project in ~/.jpm/global/<name>,
     * resolved afresh, and a jpm-<name> launcher in ~/.jpm/bin running its main class.
     */
    private Graph.Pkg installGlobal(DepKey key, String spec) {
        String name = globalName(key);
        Path dir = Fs.home().resolve("global").resolve(name);
        MavenRepo repo = new MavenRepo(List.of());
        repo.setRefreshMetadata(true);
        Fs.mkdirs(dir);
        Fs.write(dir.resolve(Manifest.FILE), "{\n  \"name\": " + Jsonc.quote("jpm-global-" + name) + ",\n  \"version\": \"0.0.0\",\n"
                + "  \"runtime\": { " + Jsonc.quote(key.toString()) + ": " + Jsonc.quote(spec) + " }\n}\n");
        Project p = Project.load(dir);
        Graph g = Install.run(p, repo, new Install.Options(false, true, Set.of(), true));
        Builder b = new Builder(p, g, repo, Env.Mode.PRODUCTION);
        List<Path> cp = b.classpath(p.modules.get(0), Builder.Cp.RUNTIME);
        Graph.Pkg pkg = g.get(key.toString());
        String main = null;
        if (pkg.kind == Graph.Kind.GIT) {
            Map<String, String> mains = GitModule.locate(key, pkg.commit).manifest().mains;
            if (!mains.isEmpty()) main = mains.getOrDefault("main", mains.values().iterator().next());
        } else if (!b.paths(pkg.id, null).isEmpty()) {
            main = Jars.mainClass(b.paths(pkg.id, null).get(0));
        }
        if (main == null) throw new JpmException(key + " has no main class, so it cannot run as an extension");
        Jdk jdk = Toolchain.require(JavaSpec.any());
        Fs.mkdirs(globalBin());
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        Path launcher = globalBin().resolve("jpm-" + name + (win ? ".cmd" : ""));
        String classpath = Compiler.join(cp);
        if (win) {
            Fs.write(launcher, "@echo off\r\n\"" + jdk.java() + "\" -cp \"" + classpath + "\" " + main + " %*\r\n");
        } else {
            Fs.write(launcher, "#!/bin/sh\nexec \"" + jdk.java() + "\" -cp \"" + classpath + "\" " + main + " \"$@\"\n");
            try {
                launcher.toFile().setExecutable(true);
            } catch (SecurityException ignored) {
            }
        }
        return pkg;
    }

    /**
     * {@code jpm update -g [name...] [--latest]}: updates global extensions, all of them or the named ones.
     * Each moves to the newest version its range allows (a branch to its newest commit); --latest moves it
     * past the range to the newest release, and from a branch to the newest release once there is one.
     */
    private int updateGlobal(List<String> names, boolean latest) {
        Path root = Fs.home().resolve("global");
        List<Path> dirs = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (var s = Files.list(root)) {
                s.filter(d -> Files.isRegularFile(d.resolve(Manifest.FILE))).sorted().forEach(dirs::add);
            } catch (IOException e) {
                throw new JpmException("Could not read " + root + ": " + e.getMessage());
            }
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String n : names) wanted.add(n.contains(":") ? globalName(parseDepArg(n).key) : n.replaceFirst("^jpm-", ""));
        for (String n : wanted) {
            if (dirs.stream().noneMatch(d -> d.getFileName().toString().equals(n))) {
                throw new JpmException("No global extension named " + n + ". Install it with `jpm add -g <dependency>`.");
            }
        }
        if (dirs.isEmpty()) {
            Log.info("No global extensions are installed. Install one with `jpm add -g <dependency>`.");
            return 0;
        }
        MavenRepo repo = new MavenRepo(List.of());
        repo.setRefreshMetadata(true);
        for (Path dir : dirs) {
            String name = dir.getFileName().toString();
            if (!wanted.isEmpty() && !wanted.contains(name)) continue;
            Manifest m = Manifest.load(dir);
            if (m.dependencies.size() != 1) {
                Log.warn("skipping " + dir + ": it is not a global extension made by `jpm add -g`");
                continue;
            }
            Manifest.Dependency d = m.dependencies.get(0);
            Lockfile lock = Lockfile.read(dir.resolve(Manifest.LOCK));
            Graph.Pkg before = lock == null ? null : lock.graph.get(d.key().toString());
            String spec = d.spec();
            boolean pinned = d.key().isGit() && spec.matches("[0-9a-f]{7,40}");
            String newest = pinned ? spec : latestSpec(repo, d.key());
            if (latest && !pinned) spec = newest;
            Graph.Pkg after = installGlobal(d.key(), spec);
            boolean changed = before == null || !Objects.equals(before.version, after.version) || !Objects.equals(before.commit, after.commit);
            if (changed) {
                Log.step("Updated", "jpm-" + name + " " + (before == null ? "" : describe(before) + " -> ") + describe(after)
                        + (spec.equals(d.spec()) ? "" : Log.dim(" (" + d.spec() + " -> " + spec + ")")));
            } else {
                Log.step("Up to date", "jpm-" + name + " " + describe(after)
                        + (spec.equals(d.spec()) ? "" : Log.dim(" (now follows " + spec + " instead of " + d.spec() + ")")));
            }
            if (!latest && !pinned && !newest.equals(spec)) {
                Log.info(spec.startsWith("#")
                        ? "  jpm-" + name + " has releases now (" + newest + "). Run `jpm update -g --latest " + name + "` to follow them instead of the "
                                + spec.substring(1) + " branch."
                        : "  newer outside " + spec + ": " + newest + ". Run `jpm update -g --latest " + name + "` to move to it.");
            }
        }
        return 0;
    }

    private int removeGlobal(List<String> args) {
        for (String a : args) {
            String name = a.contains(":") ? parseDepArg(a).key.mavenArtifact().replaceFirst("^jpm-", "") : a.replaceFirst("^jpm-", "");
            Path dir = Fs.home().resolve("global").resolve(name);
            boolean any = Files.exists(dir);
            Fs.deleteTree(dir);
            for (String ext : new String[]{"", ".cmd"}) {
                try {
                    any |= Files.deleteIfExists(globalBin().resolve("jpm-" + name + ext));
                } catch (IOException ignored) {
                }
            }
            if (!any) throw new JpmException("No global extension named " + name);
            Log.step("Removed", "jpm-" + name);
        }
        return 0;
    }
}
