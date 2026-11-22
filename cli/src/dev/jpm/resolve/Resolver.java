package dev.jpm.resolve;

import dev.jpm.resolve.impl.GitVersions;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.log.Log;
import dev.jpm.maven.MavenRepo;
import dev.jpm.maven.Pom;
import dev.jpm.util.JpmException;
import dev.jpm.version.Version;
import dev.jpm.version.VersionSpec;
import dev.jpm.version.VersionSpecs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Resolves the dependencies of a project into a {@link Graph}.
 *
 * <p>Git and Maven packages live in one graph and conflicts are settled the same way for both: every
 * hard constraint (a version declared in a manifest, or a range anywhere) must hold, and among the
 * versions that satisfy them the highest one wins. Plain versions in POMs are soft requirements.
 * Locked versions are kept when they still satisfy the constraints, so re-resolving after a manifest
 * edit does not move unrelated packages.
 */
public final class Resolver {

    /** One request for a package: its spec, whether it is a requirement (not a preference), whether the project asks for it directly, and who asks. */
    public record Request(String spec, boolean hard, boolean direct, String from) {}

    /** The version chosen for a package, with its commit and tag for Git packages. */
    public record Selected(String version, String commit, String tag) {}

    private record EdgeReq(String target, Graph.Kind kind, String spec, boolean runtimeOnly, Set<String> exclusions, boolean compileOnly) {
        EdgeReq(String target, Graph.Kind kind, String spec, boolean runtimeOnly, Set<String> exclusions) {
            this(target, kind, spec, runtimeOnly, exclusions, false);
        }
    }

    private record Expanded(List<EdgeReq> edges, boolean hasJar, Boolean isPublic) {}

    /** A top-level requirement: a dependency declared by {@code from}. */
    public record Root(String from, DepKey key, String spec, Set<String> exclusions) {
        public Root(String from, DepKey key, String spec) {
            this(from, key, spec, Set.of());
        }
    }

    private final Project project;
    private final List<Root> roots;
    private final MavenRepo repo;
    private final Pom.Loader poms;
    private final Graph previous;
    private final Set<String> unlocked;
    private final boolean unlockAll;
    private final Map<String, Graph.Kind> kinds = new HashMap<>();
    private final Map<String, Expanded> expandMemo = new HashMap<>();
    private final GitVersions gitVersions;

    /** A resolver for a project. Packages of {@code previous} keep their versions unless they are in {@code unlocked} or {@code unlockAll} is true. */
    public Resolver(Project project, MavenRepo repo, Graph previous, Set<String> unlocked, boolean unlockAll) {
        this.project = project;
        this.roots = new ArrayList<>();
        for (Project.Module m : project.sharedModules()) {
            for (Manifest.Dependency d : ResolveInputs.effectiveDeps(m)) {
                if (d.key().isWorkspace()) continue;
                ResolveInputs.validateSpec(d);
                roots.add(new Root(m.id(), d.key(), d.spec(), d.exclusions()));
            }
        }
        for (Map.Entry<String, Manifest> e : ResolveInputs.patchManifests(project).entrySet()) {
            for (Manifest.PatchOptions o : e.getValue().patchOptions.values()) {
                for (Manifest.Dependency d : o.dependencies()) {
                    ResolveInputs.validateSpec(d);
                    roots.add(new Root(e.getKey(), d.key(), d.spec(), d.exclusions()));
                }
            }
        }
        this.repo = repo;
        this.poms = new Pom.Loader(repo);
        this.previous = previous;
        this.gitVersions = new GitVersions(previous, this::locked);
        this.unlocked = unlocked;
        this.unlockAll = unlockAll;
    }

    /** Resolves explicit roots without a project (tools, a dependency's own build dependencies). */
    public Resolver(List<Root> roots, MavenRepo repo, Graph previous) {
        this.project = null;
        this.roots = roots;
        this.repo = repo;
        this.poms = new Pom.Loader(repo);
        this.previous = previous;
        this.gitVersions = new GitVersions(previous, this::locked);
        this.unlocked = Set.of();
        this.unlockAll = false;
    }

    /** Resolves the whole graph. */
    public Graph resolve() {
        Map<String, Selected> selected = new HashMap<>();
        Map<String, List<Request>> requests = null;
        Map<String, Set<String>> visitedFrom = null;
        for (int iteration = 0; iteration < 40; iteration++) {
            requests = new TreeMap<>();
            visitedFrom = new LinkedHashMap<>();
            Map<String, Selected> provisional = new HashMap<>(selected);
            Map<String, List<Set<String>>> seen = new HashMap<>();
            Deque<Object[]> queue = new ArrayDeque<>();

            for (Root r : roots) {
                String id = ResolveInputs.idOf(r.key());
                kinds.put(id, ResolveInputs.kindOf(r.key()));
                requests.computeIfAbsent(id, k -> new ArrayList<>()).add(new Request(r.spec(), true, true, r.from()));
                queue.add(new Object[]{id, new LinkedHashSet<>(r.exclusions())});
            }
            while (!queue.isEmpty()) {
                Object[] item = queue.poll();
                String id = (String) item[0];
                @SuppressWarnings("unchecked")
                Set<String> ex = (Set<String>) item[1];
                List<Set<String>> prior = seen.computeIfAbsent(id, k -> new ArrayList<>());
                boolean covered = false;
                for (Set<String> s : prior) if (ex.containsAll(s)) { covered = true; break; }
                if (covered) continue;
                prior.add(ex);
                visitedFrom.computeIfAbsent(id, k -> new LinkedHashSet<>());
                Selected sel = provisional.get(id);
                if (sel == null) {
                    sel = choose(id, requests.get(id));
                    provisional.put(id, sel);
                }
                Expanded exp = expand(id, sel);
                for (EdgeReq e : exp.edges) {
                    if (Graph.excluded(e.target, ex)) continue;
                    kinds.putIfAbsent(e.target, e.kind);
                    boolean hard = e.kind == Graph.Kind.GIT || !VersionSpecs.isBareVersion(e.spec);
                    requests.computeIfAbsent(e.target, k -> new ArrayList<>()).add(new Request(e.spec, hard, false, id));
                    visitedFrom.computeIfAbsent(e.target, k -> new LinkedHashSet<>()).add(id);
                    Set<String> next = new LinkedHashSet<>(ex);
                    next.addAll(e.exclusions);
                    queue.add(new Object[]{e.target, next});
                }
            }
            Map<String, Selected> next = new HashMap<>();
            for (Map.Entry<String, List<Request>> e : requests.entrySet()) {
                Selected cur = provisional.get(e.getKey());
                Selected s = choose(e.getKey(), e.getValue());
                next.put(e.getKey(), s);
                Log.debug("select " + e.getKey() + " -> " + s.version + (Objects.equals(cur, s) ? "" : " (was " + (cur == null ? "-" : cur.version) + ")"));
            }
            if (next.equals(selected)) break;
            selected = next;
            if (iteration == 39) Log.warn("dependency resolution did not settle; using the last result");
        }

        Graph g = new Graph();
        for (String id : requests.keySet()) {
            Selected s = selected.get(id);
            Graph.Pkg p = new Graph.Pkg(id, kinds.get(id));
            p.version = s.version;
            p.commit = s.commit;
            p.tag = s.tag;
            Expanded exp = expand(id, s);
            p.hasJar = exp.hasJar;
            for (EdgeReq e : exp.edges) {
                if (!requests.containsKey(e.target)) continue;
                p.deps.put(e.target, new Graph.Edge(e.target, e.runtimeOnly, e.exclusions, e.compileOnly));
            }
            g.put(p);
        }
        checkVisibility(g, requests);
        if (project != null) ResolveInputs.addLocal(g, project);
        return g;
    }

    private void checkVisibility(Graph g, Map<String, List<Request>> requests) {
        for (Graph.Pkg p : g.pkgs.values()) {
            if (p.kind != Graph.Kind.GIT) continue;
            Expanded exp = expand(p.id, new Selected(p.version, p.commit, p.tag));
            if (Boolean.TRUE.equals(exp.isPublic)) continue;
            DepKey key = DepKey.parse(p.id);
            for (Request r : requests.get(p.id)) {
                boolean sameRepo = r.from.contains(":") && !r.from.startsWith("workspace:")
                        && DepKey.parse(r.from).isGit() && DepKey.parse(r.from).repoKey().equals(key.repoKey());
                if (!sameRepo) {
                    throw new JpmException(p.id + " is an internal module and cannot be used from outside "
                            + key.repoKey() + " (required by " + r.from + "). Depend on one of its public modules.");
                }
            }
        }
    }

    private boolean locked(String id) {
        return previous != null && !unlockAll && !unlocked.contains(id) && previous.get(id) != null;
    }

    private Selected choose(String id, List<Request> reqs) {
        if (reqs == null || reqs.isEmpty()) throw new IllegalStateException("no requests for " + id);
        return switch (kinds.get(id)) {
            case GIT -> gitVersions.choose(id, reqs);
            case GRADLE -> chooseGradle(id, reqs);
            case EXTENSION -> chooseExtension(id, reqs);
            default -> chooseMaven(id, reqs);
        };
    }

    /** ext:<extension>/<name>: the extension says which version a spec is; locked versions are kept while the spec is unchanged. */
    private Selected chooseExtension(String id, List<Request> reqs) {
        String spec = null;
        for (Request r : reqs) {
            String s = r.spec == null ? "" : r.spec.strip();
            if (spec != null && !spec.equals(s)) throw conflict(id, reqs);
            spec = s;
        }
        if (locked(id)) return new Selected(previous.get(id).version, null, null);
        return new Selected(ExtensionDeps.resolve(DepKey.parse(id), spec).version(), null, null);
    }

    /** gradle:api names one Gradle version; every request must name the same one. */
    private Selected chooseGradle(String id, List<Request> reqs) {
        String version = null;
        for (Request r : reqs) {
            String v = r.spec == null ? "" : r.spec.strip();
            if (!GradleDist.validVersion(v)) {
                throw new JpmException(id + " (required by " + r.from + "): the version is an exact Gradle version such as 9.8.0, not \""
                        + v + "\"");
            }
            if (version != null && !version.equals(v)) throw conflict(id, reqs);
            version = v;
        }
        return new Selected(version, null, null);
    }
    private Selected chooseMaven(String id, List<Request> reqs) {
        List<VersionSpec> hard = new ArrayList<>();
        List<Version> soft = new ArrayList<>();
        for (Request r : reqs) {
            if (r.spec == null || r.spec.isBlank()) continue;
            if (r.hard) {
                try {
                    hard.add(VersionSpecs.parse(r.spec));
                } catch (IllegalArgumentException e) {
                    throw new JpmException(id + " (required by " + r.from + "): " + e.getMessage());
                }
            } else {
                soft.add(Version.of(r.spec));
            }
        }
        if (hard.isEmpty() && soft.isEmpty()) {
            throw new JpmException(id + " is required by " + reqs.get(0).from + " without a version");
        }
        Version pref = locked(id) ? Version.of(previous.get(id).version) : null;
        Version maxSoft = null;
        for (Version v : soft) if (satisfies(v, hard) && (maxSoft == null || v.compareTo(maxSoft) > 0)) maxSoft = v;
        if (pref != null && satisfies(pref, hard) && (maxSoft == null || pref.compareTo(maxSoft) >= 0)
                && (!hard.isEmpty() || soft.contains(pref))) {
            return new Selected(pref.toString(), null, null);
        }
        if (maxSoft != null) return new Selected(maxSoft.toString(), null, null);
        boolean allExact = hard.stream().allMatch(s -> s.exactVersion() != null);
        if (allExact) {
            Version v = hard.get(0).exactVersion();
            if (satisfies(v, hard)) return new Selected(v.toString(), null, null);
        } else {
            String[] ga = id.split(":");
            List<Version> all = repo.versions(ga[0], ga[1]);
            Optional<Version> best = hard.size() == 1 ? hard.get(0).best(all) : new AllOf(hard).best(all);
            if (best.isPresent()) return new Selected(best.get().toString(), null, null);
        }
        throw conflict(id, reqs);
    }

    private static boolean satisfies(Version v, List<VersionSpec> specs) {
        for (VersionSpec s : specs) if (!s.matches(v)) return false;
        return true;
    }

    /** The error for requirements that no version satisfies, naming who asked for what. */
    public static JpmException conflict(String id, List<Request> reqs) {
        StringBuilder sb = new StringBuilder("No version of " + id + " satisfies every requirement:");
        for (Request r : reqs) sb.append("\n  ").append(r.spec).append("  required by ").append(r.from);
        return new JpmException(sb.toString());
    }

    private record AllOf(List<VersionSpec> specs) implements VersionSpec {
        @Override
        public boolean matches(Version v) { return satisfies(v, specs); }
        @Override
        public String text() { return String.join(" ", specs.stream().map(VersionSpec::text).toList()); }
        @Override
        public boolean isExact() { return false; }
    }
    private Expanded expand(String id, Selected sel) {
        String memoKey = id + "@" + sel.version + "@" + sel.commit;
        Expanded e = expandMemo.get(memoKey);
        if (e != null) return e;
        e = switch (kinds.get(id)) {
            case GIT -> expandGit(id, sel);
            case GRADLE -> new Expanded(List.of(), true, null);
            case EXTENSION -> {
                List<EdgeReq> edges = new ArrayList<>();
                for (Map.Entry<String, String> d : ExtensionDeps.resolve(DepKey.parse(id), sel.version).dependencies().entrySet()) {
                    edges.add(new EdgeReq(d.getKey(), Graph.Kind.MAVEN, d.getValue(), false, Set.of()));
                }
                yield new Expanded(edges, true, null);
            }
            default -> expandMaven(id, sel.version);
        };
        expandMemo.put(memoKey, e);
        return e;
    }

    private Expanded expandMaven(String id, String version) {
        String[] ga = id.split(":");
        Pom pom = poms.load(ga[0], ga[1], version);
        List<EdgeReq> edges = new ArrayList<>();
        if (pom.relocation != null) {
            String[] r = pom.relocation.split(":");
            if (!(r[0] + ":" + r[1]).equals(id)) {
                edges.add(new EdgeReq(r[0] + ":" + r[1], Graph.Kind.MAVEN, r[2], false, Set.of()));
                return new Expanded(edges, false, null);
            }
        }
        dev.jpm.maven.GradleMetadata meta = null;
        if (pom.gradleMetadata) {
            java.nio.file.Path module = repo.fetch(ga[0], ga[1], version, null, "module");
            if (module != null) {
                String at = ga.length == 2 ? dev.jpm.maven.GradleMetadata.jvmVariantAt(module) : null;
                if (at != null) {
                    String[] t = at.split(":");
                    if (!(t[0] + ":" + t[1]).equals(id)) {
                        edges.add(new EdgeReq(t[0] + ":" + t[1], Graph.Kind.MAVEN, t[2], false, Set.of()));
                        return new Expanded(edges, false, null);
                    }
                }
                meta = dev.jpm.maven.GradleMetadata.read(module);
            }
        }
        for (Pom.Dep d : pom.dependencies) {
            String scope = d.scope() == null ? "compile" : d.scope();
            boolean providedApi = scope.equals("provided") && meta != null && meta.compileOnly(d.key());
            if (!scope.equals("compile") && !scope.equals("runtime") && !providedApi) continue;
            if (d.optional()) continue;
            if (d.type() != null && !d.type().equals("jar") && !d.type().equals("bundle")) continue;
            if (d.group() == null || d.artifact() == null || d.group().contains("${") || d.artifact().contains("${")) continue;
            if (d.version() == null || d.version().contains("${")) {
                Log.warn(id + ":" + version + " depends on " + d.key() + " without a usable version; skipping it");
                continue;
            }
            String target = d.key() + (d.classifier() != null && !d.classifier().isEmpty() ? ":" + d.classifier() : "");
            boolean runtimeOnly = scope.equals("runtime"), compileOnly = false;
            if (meta != null) {
                if (meta.compileOnly(d.key())) compileOnly = true;
                else if (meta.runtimeOnly(d.key())) runtimeOnly = true;
            }
            edges.add(new EdgeReq(target, Graph.Kind.MAVEN, d.version(), runtimeOnly && !compileOnly, d.exclusions(), compileOnly));
        }
        return new Expanded(edges, !"pom".equals(pom.packaging), null);
    }

    private Expanded expandGit(String id, Selected sel) {
        DepKey key = DepKey.parse(id);
        GitModule mod = GitModule.locate(key, sel.commit);
        repo.addRepositories(mod.root().repositories);
        repo.addRepositories(mod.manifest().repositories);
        List<EdgeReq> edges = new ArrayList<>();
        for (Manifest.Dependency d : mod.manifest().deps(Manifest.Scope.RUNTIME)) {
            if (d.key().isWorkspace()) {
                String sib = d.key().artifact();
                if (!mod.siblings().containsKey(sib)) {
                    throw new JpmException(id + ": depends on workspace:" + sib + ", which is not a module of " + key.repoKey());
                }
                GitModule sm = GitModule.locate(key.withModule(sib), sel.commit);
                boolean runtimeOnly = !sm.isPublic();
                String target = ResolveInputs.idOf(key.withModule(sib));
                edges.add(new EdgeReq(target, Graph.Kind.GIT, "@" + sel.commit, runtimeOnly, Set.of()));
            } else {
                edges.add(new EdgeReq(ResolveInputs.idOf(d.key()), ResolveInputs.kindOf(d.key()), d.spec(),
                        d.runtimeOnly(), d.exclusions()));
            }
        }
        for (Manifest.Dependency d : mod.manifest().deps(Manifest.Scope.DEV)) {
            if (!d.api() || d.key().isWorkspace()) continue;
            edges.add(new EdgeReq(ResolveInputs.idOf(d.key()), ResolveInputs.kindOf(d.key()), d.spec(),
                    false, d.exclusions(), true));
        }
        return new Expanded(edges, true, mod.isPublic());
    }
}
