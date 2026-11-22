package dev.jpm.resolve;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.util.Hash;
import dev.jpm.util.JpmException;
import dev.jpm.version.VersionSpecs;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What resolution starts from: each module's root edges, the hash of everything that feeds resolution, and package ids. */
public final class ResolveInputs {
    private ResolveInputs() {}

    /** JUnit added to tests when no test framework is declared. */
    public static final String JUNIT_JUPITER = "org.junit.jupiter:junit-jupiter";
    public static final String JUNIT_VERSION = "5.11.4";

    /** The manifests that may hold patches, by the id their patch dependencies are requested from. */
    static Map<String, Manifest> patchManifests(Project project) {
        Map<String, Manifest> out = new LinkedHashMap<>();
        if (project.root.isWorkspaceRoot()) out.put("workspace root", project.root);
        for (Project.Module m : project.modules) out.put(m.id(), m.manifest);
        return out;
    }

    /** Effective dependencies of a module, including implicit ones (JUnit for tests). */
    public static List<Manifest.Dependency> effectiveDeps(Project.Module m) {
        List<Manifest.Dependency> deps = new ArrayList<>(m.manifest.dependencies);
        boolean hasTests = m.testDirs().stream().anyMatch(Files::isDirectory);
        boolean hasFramework = deps.stream().anyMatch(d -> d.scope() == Manifest.Scope.TEST && d.key().isMaven()
                && (d.key().group().startsWith("org.junit") || d.key().group().equals("junit")
                || d.key().group().equals("org.testng") || d.key().group().startsWith("io.kotest")
                || d.key().group().startsWith("net.jqwik")));
        if (hasTests && !hasFramework) {
            deps.add(new Manifest.Dependency(DepKey.parse(JUNIT_JUPITER), JUNIT_VERSION, Manifest.Scope.TEST, 0));
        }
        return deps;
    }

    /** A hash of everything that feeds resolution; the lockfile is reused while it matches. */
    public static String inputs(Project project) {
        Hash h = new Hash().add("v1");
        for (String r : project.root.repositories) h.add("repo " + r);
        for (Project.Module m : project.modules) {
            h.add("module " + m.name() + (m.manifest.isolated ? " isolated" : ""));
            for (Manifest.Dependency d : effectiveDeps(m)) {
                h.add(d.scope().key + " " + d.key() + " " + d.spec() + " " + d.exclusions() + (d.runtimeOnly() ? " rt" : ""));
            }
        }
        for (Map.Entry<String, Manifest> e : patchManifests(project).entrySet()) {
            for (Map.Entry<String, Manifest.PatchOptions> po : e.getValue().patchOptions.entrySet()) {
                for (Manifest.Dependency d : po.getValue().dependencies()) {
                    h.add("patch " + e.getKey() + " " + po.getKey() + " " + d.key() + " " + d.spec() + " " + d.exclusions());
                }
            }
        }
        return h.hex().substring(0, 32);
    }

    /** Root edges of a module for one scope. Workspace siblings map to their LOCAL packages. */
    public static List<Graph.Edge> rootEdges(Project.Module m, Manifest.Scope scope) {
        List<Graph.Edge> out = new ArrayList<>();
        for (Manifest.Dependency d : effectiveDeps(m)) {
            if (d.scope() != scope) continue;
            out.add(new Graph.Edge(idOf(d.key()), d.runtimeOnly(), d.exclusions()));
        }
        return out;
    }

    public static String idOf(DepKey k) {
        return k.toString();
    }

    static Graph.Kind kindOf(DepKey k) {
        return k.isGit() ? Graph.Kind.GIT : k.isGradle() ? Graph.Kind.GRADLE : k.isExtension() ? Graph.Kind.EXTENSION : Graph.Kind.MAVEN;
    }

    /** Adds LOCAL packages for the project's own modules so classpaths can be walked through them. */
    public static void addLocal(Graph g, Project project) {
        for (Project.Module m : project.sharedModules()) {
            Graph.Pkg p = new Graph.Pkg(m.id(), Graph.Kind.LOCAL);
            p.version = m.version();
            p.dir = m.dir;
            for (Manifest.Dependency d : m.manifest.deps(Manifest.Scope.RUNTIME)) {
                String id = idOf(d.key());
                p.deps.put(id, new Graph.Edge(id, d.runtimeOnly(), d.exclusions()));
            }
            for (Manifest.Dependency d : m.manifest.deps(Manifest.Scope.DEV)) {
                if (!d.api()) continue;
                String id = idOf(d.key());
                p.deps.putIfAbsent(id, new Graph.Edge(id, false, d.exclusions(), true));
            }
            g.put(p);
        }
    }

    static void validateSpec(Manifest.Dependency d) {
        try {
            if (d.key().isMaven()) VersionSpecs.parse(d.spec());
        } catch (IllegalArgumentException e) {
            throw new JpmException(d.key() + " (" + Manifest.FILE + " line " + d.line() + "): " + e.getMessage());
        }
    }
}
