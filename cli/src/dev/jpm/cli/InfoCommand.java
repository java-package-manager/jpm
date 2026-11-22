package dev.jpm.cli;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.build.Compiler;
import dev.jpm.build.Env;
import dev.jpm.build.Jars;
import dev.jpm.toolchain.Jdk;
import dev.jpm.api.json.Jsonc;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Resolver;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class InfoCommand extends Command {

    InfoCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "info";
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.PROJECT, "info [--json]", "Describe the project (modules, dependencies); --json for extensions",
                        "Describe the project: modules, versions, folders, dependencies; `--json` is a stable format for extensions, `--ide` adds test folders, scripts and scoped dependencies with sources (used by the IntelliJ plugin), `--classpath` adds every classpath (compile, processor, runtime, testCompile, testRuntime) as packages with coordinates, kind, jars and edges, and the resolved defines, without downloading sources"));
    }

    @Override
    List<String> flags() {
        return List.of("--json", "--classpath", "--ide");
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
    @Override
    int run(List<String> args) {
        boolean json = flag(args, "--json");
        boolean withClasspath = flag(args, "--classpath");
        boolean ide = flag(args, "--ide");
        noMoreFlags(args);
        Ctx c = open(false);
        Builder b = c.builder(Env.Mode.PRODUCTION);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("infoVersion", 1);
        out.put("jpmVersion", Main.VERSION);
        out.put("projectDir", c.project().dir.toString());
        out.put("name", c.project().root.name);
        String rootVersion = Project.versionOverride() != null ? Project.versionOverride() : c.project().root.version;
        if (rootVersion != null) out.put("version", rootVersion);
        out.put("workspace", c.project().root.isWorkspaceRoot());
        out.put("repositories", c.project().root.repositories);
        out.put("extensions", c.project().root.extensions);
        if (ide) out.put("scripts", new ArrayList<>(c.project().root.scripts.keySet()));
        List<Object> modules = new ArrayList<>();
        Builder shared = b;
        for (Project.Module m : c.project().buildOrder()) {
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
                        Project.Module sib = c.project().module(k.artifact());
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
                List<Path> compileCp = b.classpath(m, Builder.Cp.COMPILE);
                mod.put("compileClasspath", compileCp.stream().map(Path::toString).toList());
                Map<String, Object> compileModule = compileModule(m, compileCp);
                if (compileModule != null) mod.put("compileModule", compileModule);
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
            Log.out(Jsonc.write(out));
        } else {
            for (Object o : modules) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mod = (Map<String, Object>) o;
                Log.out(Log.bold(mod.get("name") + " " + mod.get("version")) + "  " + mod.get("dir"));
                for (Object d : (List<?>) mod.get("dependencies")) {
                    Map<?, ?> dm = (Map<?, ?>) d;
                    Log.out("  " + dm.get("scope") + "  " + dm.get("key") + " " + dm.get("spec")
                            + (dm.get("resolved") != null ? Log.dim(" -> " + dm.get("resolved")) : ""));
                }
            }
        }
        return 0;
    }

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
     * {@code --classpath}, for a module with {@code module-info.java}: how javac is given the compile
     * classpath, so tools that read the same sources (javadoc, for one) can pass it the same way. Jars
     * that are not modules stay on the class path, which the module reads: on the module path they would
     * become automatic modules, and two of them sharing a package stop the tool. Null without module-info.
     */
    static Map<String, Object> compileModule(Project.Module m, List<Path> compileClasspath) {
        Path moduleInfo = dev.jpm.build.Modules.moduleInfo(m.srcDirs());
        if (moduleInfo == null) return null;
        dev.jpm.build.Modules.Declaration decl = dev.jpm.build.Modules.parse(moduleInfo);
        List<List<Path>> split = dev.jpm.build.Modules.split(decl, compileClasspath);
        Map<String, Object> out = new LinkedHashMap<>();
        if (decl.name() != null) out.put("name", decl.name());
        out.put("modulePath", split.get(0).stream().map(Path::toString).toList());
        out.put("classPath", split.get(1).stream().map(Path::toString).toList());
        List<String> options = new ArrayList<>();
        if (!split.get(0).isEmpty()) {
            options.add("--module-path");
            options.add(Compiler.join(split.get(0)));
        }
        if (!split.get(1).isEmpty()) {
            options.add("-cp");
            options.add(Compiler.join(split.get(1)));
            if (decl.name() != null) {
                options.add("--add-reads");
                options.add(decl.name() + "=ALL-UNNAMED");
            }
        }
        out.put("options", options);
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
                Graph.Pkg p = c.graph().get(id);
                if (p == null) continue;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", p.id);
                if (p.kind == Graph.Kind.LOCAL) {
                    Project.Module sib = c.project().module(p.id.substring("workspace:".length()));
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
}
