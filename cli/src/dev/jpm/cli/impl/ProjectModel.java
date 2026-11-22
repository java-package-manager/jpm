package dev.jpm.cli.impl;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.build.Compiler;
import dev.jpm.build.Defines;
import dev.jpm.build.Env;
import dev.jpm.build.Modules;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.ExtensionDeps;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.ResolveInputs;
import dev.jpm.toolchain.Jdk;
import dev.jpm.util.JpmException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class ProjectModel {
    private final Project project;
    private final MavenRepo repo;
    private final Graph graph;
    private final Builder builder;

    ProjectModel(Project project, MavenRepo repo, Graph graph) {
        this.project = project;
        this.repo = repo;
        this.graph = graph;
        this.builder = new Builder(project, graph, repo, Env.Mode.PRODUCTION);
    }

    Map<String, Object> project(boolean sources, boolean lockfileChanged) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dir", project.dir.toString());
        out.put("name", project.root.name);
        String version = Project.versionOverride() != null ? Project.versionOverride() : project.root.version;
        if (version != null) out.put("version", version);
        out.put("workspace", project.root.isWorkspaceRoot());
        out.put("repositories", project.root.repositories);
        out.put("extensions", project.root.extensions);
        out.put("scripts", scripts(project.root));
        List<Object> modules = new ArrayList<>();
        for (Project.Module m : project.buildOrder()) modules.add(module(m, sources));
        out.put("modules", modules);
        out.put("lockfileChanged", lockfileChanged);
        return out;
    }

    private Map<String, Object> module(Project.Module m, boolean sources) {
        Builder b = builder.forModule(m);
        Manifest mf = m.manifest;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", m.name());
        if (mf.group != null) out.put("group", mf.group);
        out.put("version", m.version());
        if (mf.description != null) out.put("description", mf.description);
        if (mf.license != null) out.put("license", mf.license);
        if (mf.url != null) out.put("url", mf.url);
        out.put("dir", m.dir.toString());
        out.put("relativeDir", m.relDir());
        out.put("visibility", mf.visibility);
        out.put("package", mf.packaging);
        out.put("layout", mf.layout.name());
        out.put("main", new LinkedHashMap<>(mf.mains));
        Map<String, Object> folders = new LinkedHashMap<>();
        folders.put("sources", paths(m.srcDirs()));
        folders.put("resources", paths(m.assetsDirs()));
        folders.put("tests", paths(m.testDirs()));
        folders.put("testResources", List.of(m.testResourcesDir().toString()));
        List<String> generated = new ArrayList<>();
        try {
            Path gen = b.generateSources(m);
            if (gen != null) generated.add(gen.toString());
        } catch (JpmException ignored) {
        }
        folders.put("generatedSources", generated);
        folders.put("exclude", List.of(m.dir.resolve(".cache").toString(), m.dir.resolve("dist").toString()));
        out.put("folders", folders);
        out.put("jar", b.jarPath(m).toString());
        Map<String, Object> jdk = new LinkedHashMap<>();
        if (mf.java != null) jdk.put("spec", mf.java);
        Jdk found = null;
        try {
            found = b.jdk(mf);
            jdk.put("home", found.home().toString());
            Integer t = mf.targetMajor();
            jdk.put("release", t == null ? found.major() : t);
        } catch (JpmException e) {
            jdk.put("error", e.getMessage());
        }
        out.put("jdk", jdk);
        out.put("dependencies", dependencies(m));
        out.put("packages", packages(b, m, sources));
        out.put("defines", defines(b, m));
        out.put("scripts", scripts(mf));
        out.put("extensions", mf.extensions);
        if (found != null && Modules.moduleInfo(m.srcDirs()) != null) {
            Map<String, Object> split = moduleSplit(m, found, b.classpath(m, Builder.Cp.COMPILE));
            if (split != null) out.put("modulePath", split);
        }
        return out;
    }

    private static List<String> paths(List<Path> ps) {
        return ps.stream().map(Path::toString).toList();
    }

    private List<Object> dependencies(Project.Module m) {
        List<Object> out = new ArrayList<>();
        for (Manifest.Dependency d : m.manifest.dependencies) {
            Map<String, Object> dep = new LinkedHashMap<>();
            DepKey k = d.key();
            dep.put("key", k.toString());
            dep.put("kind", k.kind().name().toLowerCase());
            dep.put("scope", d.scope().key);
            dep.put("spec", d.spec());
            Graph.Pkg p = graph.get(k.toString());
            if (p != null && p.kind != Graph.Kind.LOCAL) {
                dep.put("resolved", p.version);
                if (p.commit != null) dep.put("commit", p.commit);
            }
            dep.put("exclude", new ArrayList<>(new TreeSet<>(d.exclusions())));
            dep.put("runtimeOnly", d.runtimeOnly());
            dep.put("api", d.api());
            dep.put("tests", d.tests());
            switch (k.kind()) {
                case MAVEN -> {
                    dep.put("group", k.group());
                    dep.put("artifact", k.artifact());
                    if (k.classifier() != null) dep.put("classifier", k.classifier());
                }
                case GIT -> {
                    dep.put("host", k.host());
                    dep.put("group", k.group());
                    dep.put("artifact", k.artifact());
                    if (k.module() != null) dep.put("module", k.module());
                }
                case WORKSPACE -> {
                    Project.Module sib = project.module(k.artifact());
                    dep.put("module", sib != null ? sib.name() : k.artifact());
                    if (sib != null && sib.manifest.group != null) dep.put("group", sib.manifest.group);
                    if (sib != null) dep.put("artifact", sib.name());
                }
                default -> {
                }
            }
            out.add(dep);
        }
        return out;
    }

    private List<Object> packages(Builder b, Project.Module m, boolean download) {
        List<Graph.Edge> runtime = ResolveInputs.rootEdges(m, Manifest.Scope.RUNTIME);
        List<Graph.Edge> devOnly = ResolveInputs.rootEdges(m, Manifest.Scope.DEV);
        List<Graph.Edge> test = ResolveInputs.rootEdges(m, Manifest.Scope.TEST);
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
            Map<String, Object> d = pkg(b, m, p);
            if (p.kind != Graph.Kind.LOCAL && ((List<?>) d.get("jars")).isEmpty()) continue;
            d.put("scope", e.getValue());
            Path sources = sources(p, download);
            if (sources != null) d.put("sources", sources.toString());
            out.add(d);
        }
        return out;
    }

    private Map<String, Object> pkg(Builder b, Project.Module m, Graph.Pkg p) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", p.id);
        if (p.kind == Graph.Kind.LOCAL) {
            Project.Module sib = project.module(p.id.substring("workspace:".length()));
            d.put("kind", "workspace");
            d.put("module", sib.name());
            d.put("version", sib.version());
            d.put("jars", List.of());
            d.put("classes", Builder.classesDir(sib).toString());
        } else {
            d.put("kind", b.isPatched(p.id) ? "patched" : p.kind.name().toLowerCase());
            d.put("version", p.version);
            if (p.commit != null) d.put("commit", p.commit);
            d.put("jars", b.paths(p.id, m).stream().map(Path::toString).toList());
        }
        d.put("dependencies", new ArrayList<>(p.deps.keySet()));
        return d;
    }

    private Path sources(Graph.Pkg p, boolean download) {
        if (p.kind == Graph.Kind.MAVEN) {
            DepKey k = DepKey.parse(p.id);
            if (k.classifier() != null) return null;
            if (download) return repo.fetch(k.group(), k.artifact(), p.version, "sources", "jar");
            Path local = repo.local(k.group(), k.artifact(), p.version, "sources", "jar");
            return Files.exists(local) ? local : null;
        }
        if (p.kind == Graph.Kind.EXTENSION) return ExtensionDeps.resolve(DepKey.parse(p.id), p.version).sources();
        if (p.dir != null) {
            for (String rel : List.of("src/main/java", "src")) if (Files.isDirectory(p.dir.resolve(rel))) return p.dir.resolve(rel);
        }
        return null;
    }

    private static List<Object> defines(Builder b, Project.Module m) {
        List<Object> out = new ArrayList<>();
        String cls = Defines.className(m.manifest);
        for (Defines.Value v : b.defineValues(m)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", v.name());
            d.put("type", v.type().name().toLowerCase());
            d.put("value", v.value());
            d.put("class", cls);
            out.add(d);
        }
        return out;
    }

    private static List<Object> scripts(Manifest mf) {
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, String> e : mf.scripts.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<>();
            String run = e.getValue();
            s.put("name", e.getKey());
            s.put("run", run);
            s.put("kind", run.endsWith(".java") ? "java" : run.endsWith(".sh") ? "sh" : "command");
            s.put("dependencies", new LinkedHashMap<>(mf.scriptDependencies.getOrDefault(e.getKey(), Map.of())));
            out.add(s);
        }
        return out;
    }

    static Map<String, Object> moduleSplit(Project.Module m, Jdk jdk, List<Path> compileClasspath) {
        Path moduleInfo = Modules.moduleInfo(m.srcDirs());
        if (moduleInfo == null) return null;
        Modules.Declaration decl = Modules.declaration(jdk, moduleInfo);
        List<List<Path>> split = Modules.split(decl, compileClasspath);
        Map<String, Object> out = new LinkedHashMap<>();
        if (decl.name() != null) out.put("name", decl.name());
        out.put("modulePath", paths(split.get(0)));
        out.put("classPath", paths(split.get(1)));
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

    Map<String, Object> classpaths(List<String> names) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Project.Module m : project.buildOrder()) {
            if (!names.isEmpty() && !names.contains(m.name())) continue;
            Builder b = builder.forModule(m);
            Map<String, Object> cps = new LinkedHashMap<>();
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
                    Graph.Pkg p = graph.get(id);
                    if (p != null) pkgs.add(pkg(b, m, p));
                }
                cps.put(name, pkgs);
            }
            try {
                Map<String, Object> split = Modules.moduleInfo(m.srcDirs()) == null ? null
                        : moduleSplit(m, b.jdk(m.manifest), b.classpath(m, Builder.Cp.COMPILE));
                if (split != null) cps.put("modulePath", split);
            } catch (JpmException ignored) {
            }
            out.put(m.name(), cps);
        }
        for (String n : names) {
            if (!out.containsKey(n)) throw new JpmException("No module named \"" + n + "\"");
        }
        return out;
    }
}
