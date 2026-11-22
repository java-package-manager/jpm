package dev.jpm.resolve;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.git.GitRepo;
import dev.jpm.maven.MavenRepo;
import dev.jpm.maven.Pom;
import dev.jpm.util.Hash;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;
import dev.jpm.version.Version;
import dev.jpm.version.VersionSpec;
import dev.jpm.version.VersionSpecs;

import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
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
import java.util.regex.Pattern;

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
    /** JUnit added to tests when no test framework is declared. */
    public static final String JUNIT_JUPITER = "org.junit.jupiter:junit-jupiter";
    public static final String JUNIT_VERSION = "5.11.4";

    private static final Pattern HEX = Pattern.compile("[0-9a-f]{7,40}");

    private record Request(String spec, boolean hard, boolean direct, String from) {}

    private record Selected(String version, String commit, String tag) {}

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
    private final Map<String, Map<String, String>> tagMemo = new HashMap<>();

    public Resolver(Project project, MavenRepo repo, Graph previous, Set<String> unlocked, boolean unlockAll) {
        this.project = project;
        this.roots = new ArrayList<>();
        // isolated modules are resolved on their own, see Install
        for (Project.Module m : project.sharedModules()) {
            for (Manifest.Dependency d : effectiveDeps(m)) {
                if (d.key().isWorkspace()) continue;
                validateSpec(d);
                roots.add(new Root(m.id(), d.key(), d.spec(), d.exclusions()));
            }
        }
        // compile-only dependencies of patches: resolved with the project, so their versions agree with it
        // and are locked, but on no module's classpath (only the patched classes are compiled against them)
        for (Map.Entry<String, Manifest> e : patchManifests(project).entrySet()) {
            for (Manifest.PatchOptions o : e.getValue().patchOptions.values()) {
                for (Manifest.Dependency d : o.dependencies()) {
                    validateSpec(d);
                    roots.add(new Root(e.getKey(), d.key(), d.spec(), d.exclusions()));
                }
            }
        }
        this.repo = repo;
        this.poms = new Pom.Loader(repo);
        this.previous = previous;
        this.unlocked = unlocked;
        this.unlockAll = unlockAll;
    }

    /** The manifests that may hold patches, by the id their patch dependencies are requested from. */
    private static Map<String, Manifest> patchManifests(Project project) {
        Map<String, Manifest> out = new LinkedHashMap<>();
        if (project.root.isWorkspaceRoot()) out.put("workspace root", project.root);
        for (Project.Module m : project.modules) out.put(m.id(), m.manifest);
        return out;
    }

    /** Resolves explicit roots without a project (tools, a dependency's own build dependencies). */
    public Resolver(List<Root> roots, MavenRepo repo, Graph previous) {
        this.project = null;
        this.roots = roots;
        this.repo = repo;
        this.poms = new Pom.Loader(repo);
        this.previous = previous;
        this.unlocked = Set.of();
        this.unlockAll = false;
    }

    // ------------------------------------------------------------------ inputs

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

    // ------------------------------------------------------------------ resolution

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
                String id = idOf(r.key());
                kinds.put(id, kindOf(r.key()));
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
                    // a bare version from a POM is a preference; ranges in any syntax are requirements
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
        if (project != null) addLocal(g, project);
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
            // "api" dev dependencies: siblings compile against them, nothing runs with them
            for (Manifest.Dependency d : m.manifest.deps(Manifest.Scope.DEV)) {
                if (!d.api()) continue;
                String id = idOf(d.key());
                p.deps.putIfAbsent(id, new Graph.Edge(id, false, d.exclusions(), true));
            }
            g.put(p);
        }
    }

    private static void validateSpec(Manifest.Dependency d) {
        try {
            if (d.key().isMaven()) VersionSpecs.parse(d.spec());
        } catch (IllegalArgumentException e) {
            throw new JpmException(d.key() + " (" + Manifest.FILE + " line " + d.line() + "): " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ choosing versions

    private boolean locked(String id) {
        return previous != null && !unlockAll && !unlocked.contains(id) && previous.get(id) != null;
    }

    private Selected choose(String id, List<Request> reqs) {
        if (reqs == null || reqs.isEmpty()) throw new IllegalStateException("no requests for " + id);
        return switch (kinds.get(id)) {
            case GIT -> chooseGit(id, reqs);
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

    /** The commit a branch requirement stands for: the locked one until updated, else the branch's newest. */
    private String branchCommit(String id, GitRepo git, String branch, Request r) {
        Graph.Pkg prev = previous == null ? null : previous.get(id);
        return locked(id) && prev.tag == null && prev.commit != null && r.direct ? prev.commit : git.branchHead(branch);
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
            // one requirement chooses by its own rules (latest.integration takes snapshots, !! a preferred version)
            Optional<Version> best = hard.size() == 1 ? hard.get(0).best(all) : new AllOf(hard).best(all);
            if (best.isPresent()) return new Selected(best.get().toString(), null, null);
        }
        throw conflict(id, reqs);
    }

    private static boolean satisfies(Version v, List<VersionSpec> specs) {
        for (VersionSpec s : specs) if (!s.matches(v)) return false;
        return true;
    }

    private static JpmException conflict(String id, List<Request> reqs) {
        StringBuilder sb = new StringBuilder("No version of " + id + " satisfies every requirement:");
        for (Request r : reqs) sb.append("\n  ").append(r.spec).append("  required by ").append(r.from);
        return new JpmException(sb.toString());
    }

    /** Intersection of several specs. */
    private record AllOf(List<VersionSpec> specs) implements VersionSpec {
        public boolean matches(Version v) { return satisfies(v, specs); }
        public String text() { return String.join(" ", specs.stream().map(VersionSpec::text).toList()); }
        public boolean isExact() { return false; }
    }

    private Selected chooseGit(String id, List<Request> reqs) {
        DepKey key = DepKey.parse(id);
        GitRepo git = GitRepo.of(key);
        List<Selected> pins = new ArrayList<>();
        List<Request> pinReqs = new ArrayList<>();
        List<VersionSpec> ranges = new ArrayList<>();
        // "@<commit>" ties a module to the commit of a sibling from the same repository. It only decides
        // when nothing else asks for the module; otherwise the sibling follows the other requests.
        boolean onlySiblingPins = reqs.stream().allMatch(r -> r.spec.trim().startsWith("@"));
        for (Request r : reqs) {
            String s = r.spec.trim();
            if (s.startsWith("@") && !onlySiblingPins) continue;
            if (s.startsWith("@")) {
                pins.add(commitSelection(key, s.substring(1), null));
                pinReqs.add(r);
            } else if (s.startsWith("#")) {
                pins.add(commitSelection(key, branchCommit(id, git, s.substring(1), r), null));
                pinReqs.add(r);
            } else if (HEX.matcher(s).matches() && !VersionSpecs.looksLikeSpec(s)) {
                String full = git.resolveCommit(s);
                if (full == null) throw new JpmException(id + ": commit " + s + " not found");
                pins.add(commitSelection(key, full, null));
                pinReqs.add(r);
            } else if (VersionSpecs.looksLikeSpec(s)) {
                try {
                    ranges.add(VersionSpecs.parse(s, true));
                } catch (IllegalArgumentException e) {
                    throw new JpmException(id + " (required by " + r.from + "): " + e.getMessage());
                }
            } else {
                // an exact tag name, else a branch name ("main" is "#main" when there is no tag "main")
                Map<String, String> tags = tags(key);
                String commit = tags.get(s);
                if (commit != null) {
                    pins.add(tagSelection(key, s, commit));
                } else if (git.hasBranch(s)) {
                    pins.add(commitSelection(key, branchCommit(id, git, s, r), null));
                } else {
                    throw new JpmException(id + ": no tag or branch \"" + s + "\" in " + key.repoKey()
                            + " (a version range, tag, branch or commit; #name is always a branch)");
                }
                pinReqs.add(r);
            }
        }
        if (!pins.isEmpty()) {
            // A pin in the project's own manifest wins; otherwise the highest pinned version does.
            Selected chosen = null;
            for (int i = 0; i < pins.size(); i++) {
                if (pinReqs.get(i).direct) { chosen = pins.get(i); break; }
            }
            if (chosen == null) {
                for (Selected s : pins) {
                    if (chosen == null || Version.of(s.version).compareTo(Version.of(chosen.version)) > 0) chosen = s;
                }
            }
            for (Selected s : pins) {
                if (!s.commit.equals(chosen.commit)) {
                    Log.warn(id + " is pinned to several commits; using " + chosen.commit.substring(0, 7) + " (" + chosen.version + ")");
                    break;
                }
            }
            for (VersionSpec r : ranges) {
                if (!r.matches(Version.of(stripBuild(chosen.version)))) {
                    throw conflict(id, reqs);
                }
            }
            return chosen;
        }
        Map<String, String> tags = tags(key);
        Map<Version, String> byVersion = new LinkedHashMap<>();
        for (String tag : tags.keySet()) {
            String v = tagVersion(key, tag, tags.keySet());
            if (v != null) byVersion.putIfAbsent(Version.of(v), tag);
        }
        if (locked(id)) {
            Graph.Pkg p = previous.get(id);
            if (p.tag != null && tags.containsKey(p.tag) && tags.get(p.tag).equals(p.commit)) {
                Version v = Version.of(p.version);
                boolean ok = true;
                for (VersionSpec r : ranges) ok &= r.matches(v);
                if (ok) return new Selected(p.version, p.commit, p.tag);
            }
        }
        Version best = null;
        for (Version v : byVersion.keySet()) {
            boolean ok = true;
            for (VersionSpec r : ranges) {
                if (!r.matches(v) || (v.isPreRelease() && !r.text().contains("-"))) { ok = false; break; }
            }
            if (ok && (best == null || v.compareTo(best) > 0)) best = v;
        }
        if (best == null) {
            StringBuilder sb = new StringBuilder("No tag of " + key.repoKey() + " matches every requirement for " + id + ":");
            for (Request r : reqs) sb.append("\n  ").append(r.spec).append("  required by ").append(r.from);
            List<String> avail = byVersion.keySet().stream().sorted().map(Version::toString).toList();
            sb.append("\nAvailable versions: ").append(avail.isEmpty() ? "(no version tags)" : String.join(", ", avail));
            throw new JpmException(sb.toString());
        }
        String tag = byVersion.get(best);
        return tagSelection(key, tag, tags.get(tag));
    }

    private static String stripBuild(String v) {
        int p = v.indexOf('+');
        return p < 0 ? v : v.substring(0, p);
    }

    private Map<String, String> tags(DepKey key) {
        return tagMemo.computeIfAbsent(key.repoKey().toString(), k -> GitRepo.of(key).tags());
    }

    /**
     * Version a tag stands for, for this module. Prefixed tags ({@code api/v1.2.0}) are used for a module
     * when the repository has any; otherwise plain tags ({@code v1.2.0}) version the whole workspace.
     */
    static String tagVersion(DepKey key, String tag, Collection<String> allTags) {
        String module = key.module();
        boolean prefixed = module != null && allTags.stream().anyMatch(t -> t.startsWith(module + "/"));
        String v;
        if (prefixed) {
            if (!tag.startsWith(module + "/")) return null;
            v = tag.substring(module.length() + 1);
        } else {
            if (tag.contains("/")) return null;
            v = tag;
        }
        if (v.startsWith("v")) v = v.substring(1);
        return v.matches("\\d+(\\.\\d+){0,2}(-[0-9A-Za-z.-]+)?") ? v : null;
    }

    private Selected tagSelection(DepKey key, String tag, String commit) {
        GitModule mod = GitModule.locate(key, commit);
        String declared = mod.version();
        String fromTag = tagVersion(key, tag, tags(key).keySet());
        if (fromTag != null && !Version.of(fromTag).equals(Version.of(declared))) {
            throw new JpmException(key + ": tag " + tag + " points to a commit whose " + Manifest.FILE
                    + " says version " + declared + ". Tags must match the manifest version.");
        }
        return new Selected(fromTag != null ? fromTag : declared, commit, tag);
    }

    private Selected commitSelection(DepKey key, String commit, String tag) {
        // A commit that carries a version tag for this module is that version.
        Map<String, String> tags = tags(key);
        for (Map.Entry<String, String> t : tags.entrySet()) {
            if (t.getValue().equals(commit) && tagVersion(key, t.getKey(), tags.keySet()) != null) {
                return tagSelection(key, t.getKey(), commit);
            }
        }
        GitModule mod = GitModule.locate(key, commit);
        return new Selected(mod.version() + "+" + commit.substring(0, 7), commit, tag);
    }

    // ------------------------------------------------------------------ expanding

    private Expanded expand(String id, Selected sel) {
        String memoKey = id + "@" + sel.version + "@" + sel.commit;
        Expanded e = expandMemo.get(memoKey);
        if (e != null) return e;
        e = switch (kinds.get(id)) {
            case GIT -> expandGit(id, sel);
            case GRADLE -> new Expanded(List.of(), true, null); // the distribution's jars, nothing to resolve
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
        // a classified jar (g:a:classifier) shares the POM of g:a
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
        // Gradle's module metadata knows what a POM cannot: compile-time-only (compileOnlyApi) dependencies
        dev.jpm.maven.GradleMetadata meta = null;
        if (pom.gradleMetadata) {
            java.nio.file.Path module = repo.fetch(ga[0], ga[1], version, null, "module");
            if (module != null) {
                // a Kotlin Multiplatform library: its JVM classes are another module (mockk -> mockk-jvm), which the
                // POM may not depend on; like a relocation
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
            // provided is not passed on, except where the .module says consumers compile against it (jpm-publish
            // writes "api" dev dependencies like that; Gradle writes compileOnlyApi as compile scope)
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
        List<EdgeReq> edges = new ArrayList<>();
        for (Manifest.Dependency d : mod.manifest().deps(Manifest.Scope.RUNTIME)) {
            if (d.key().isWorkspace()) {
                String sib = d.key().artifact();
                if (!mod.siblings().containsKey(sib)) {
                    throw new JpmException(id + ": depends on workspace:" + sib + ", which is not a module of " + key.repoKey());
                }
                GitModule sm = GitModule.locate(key.withModule(sib), sel.commit);
                // The implementation of a public API is used at runtime only, never compiled against.
                boolean runtimeOnly = !sm.isPublic();
                String target = idOf(key.withModule(sib));
                edges.add(new EdgeReq(target, Graph.Kind.GIT, "@" + sel.commit, runtimeOnly, Set.of()));
            } else {
                edges.add(new EdgeReq(idOf(d.key()), kindOf(d.key()), d.spec(),
                        d.runtimeOnly(), d.exclusions()));
            }
        }
        // "api" dev dependencies: consumers compile against them; the platform provides them at run time
        for (Manifest.Dependency d : mod.manifest().deps(Manifest.Scope.DEV)) {
            if (!d.api() || d.key().isWorkspace()) continue;
            edges.add(new EdgeReq(idOf(d.key()), kindOf(d.key()), d.spec(),
                    false, d.exclusions(), true));
        }
        return new Expanded(edges, true, mod.isPublic());
    }
}
