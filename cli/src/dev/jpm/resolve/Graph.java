package dev.jpm.resolve;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** A resolved dependency graph: one version per package, with typed edges. */
public final class Graph {

    /** Where a package comes from; LOCAL is a module of this project. */
    public enum Kind { MAVEN, GIT, LOCAL, GRADLE, EXTENSION }

    /** An edge to another package. {@code runtimeOnly} edges are left off compile classpaths. */
    /**
     * A dependency. {@code runtimeOnly}: on the runtime classpath only. {@code compileOnly}: on the compile
     * classpath only, provided at run time by the platform (a library's compileOnlyApi dependencies).
     */
    public record Edge(String target, boolean runtimeOnly, Set<String> exclusions, boolean compileOnly) {
        /** An edge that is not compile-only. */
        public Edge(String target, boolean runtimeOnly, Set<String> exclusions) {
            this(target, runtimeOnly, exclusions, false);
        }

        /** The edge as {@code project.lock} writes it: its scope, then {@code !exclusion}s. */
        public String encode() {
            StringBuilder sb = new StringBuilder(compileOnly ? "provided" : runtimeOnly ? "runtime" : "compile");
            for (String e : new TreeSet<>(exclusions)) sb.append(" !").append(e);
            return sb.toString();
        }

        /** Reads an edge as {@link #encode()} writes it. */
        public static Edge decode(String target, String s) {
            String[] parts = s.trim().split("\\s+");
            Set<String> ex = new LinkedHashSet<>();
            for (int i = 1; i < parts.length; i++) if (parts[i].startsWith("!")) ex.add(parts[i].substring(1));
            return new Edge(target, parts[0].equals("runtime"), ex, parts[0].equals("provided"));
        }
    }

    /** A package in the graph: its id, kind, chosen version, edges and where its files are. */
    public static final class Pkg {
        public final String id;
        public final Kind kind;
        public String version;
        /** Git only. */
        public String commit;
        public String tag;
        /** Maven only: checksum of the jar. Null for POM-only packages. */
        public String sha256;
        /** For Maven packages: the SHA-256 of the POM resolution read, which the lockfile pins too. */
        public String pomSha256;
        /** False for packages that have no jar (Maven pom packaging). */
        public boolean hasJar = true;
        public final Map<String, Edge> deps = new TreeMap<>();
        /** Git: source directory of the module (transient). Local: module directory. */
        public Path dir;
        /** Path of the jar or classes directory once fetched or built (transient). */
        public Path artifact;

        /** A package with nothing chosen yet. */
        public Pkg(String id, Kind kind) {
            this.id = id;
            this.kind = kind;
        }

        /** Maven group (first part of the id). */
        public String group() {
            return id.split(":")[0];
        }

        /** Maven artifact (second part of the id). */
        public String name() {
            return id.split(":")[1];
        }

        /** Maven classifier (third part of the id), or null. */
        public String classifier() {
            String[] p = id.split(":");
            return kind == Kind.MAVEN && p.length > 2 ? p[2] : null;
        }

        @Override
        public String toString() {
            return id + "@" + version;
        }
    }

    public final Map<String, Pkg> pkgs = new TreeMap<>();

    /** The graphs of isolated workspace modules, by module name; each is resolved on its own. */
    public final Map<String, Graph> isolated = new TreeMap<>();

    /** The graphs of the extensions declared in the root manifest, by extension name; each is resolved on its own. */
    public final Map<String, Graph> extensions = new TreeMap<>();

    /** The package with this id, or null. */
    public Pkg get(String id) {
        return pkgs.get(id);
    }

    /** Adds or replaces a package. */
    public void put(Pkg p) {
        pkgs.put(p.id, p);
    }

    /** Whether a package is left out by exclusions ({@code group:artifact}, {@code group:*} or {@code group}); classified jars are covered by their {@code group:artifact}. */
    public static boolean excluded(String id, Set<String> exclusions) {
        if (exclusions.isEmpty()) return false;
        String[] parts = id.split(":");
        if (parts.length < 2) return false;
        String g = parts[0], a = parts[1];
        for (String e : exclusions) {
            int ec = e.indexOf(':');
            String eg = ec < 0 ? e : e.substring(0, ec), ea = ec < 0 ? "*" : e.substring(ec + 1);
            if ((eg.equals("*") || eg.equals(g)) && (ea.equals("*") || ea.equals(a))) return true;
        }
        return false;
    }

    /**
     * Which edges a closure follows. COMPILE: what compiling against the roots needs (no run-time-only
     * edges). RUNTIME: what running them needs (no compile-only edges, which the platform provides). ALL:
     * both, for dev dependencies and tools whose whole tree goes on a compile classpath.
     */
    public enum Walk {
        COMPILE, RUNTIME, ALL;

        boolean follows(Edge e) {
            return switch (this) {
                case COMPILE -> !e.runtimeOnly;
                case RUNTIME -> !e.compileOnly;
                case ALL -> true;
            };
        }
    }

    /**
     * Transitive closure from the given root edges, honoring exclusions along each path.
     *
     * @return package ids in breadth-first order
     */
    public List<String> closure(Collection<Edge> roots, Walk walk) {
        return closure(roots, walk, Set.of());
    }

    /** Like {@link #closure(Collection, Walk)}, but neither includes nor walks through the ids in {@code stop}. */
    public List<String> closure(Collection<Edge> roots, Walk walk, Set<String> stop) {
        Map<String, List<Set<String>>> seen = new HashMap<>();
        LinkedHashSet<String> order = new LinkedHashSet<>();
        Deque<Object[]> queue = new ArrayDeque<>();
        for (Edge e : roots) {
            if (!walk.follows(e)) continue;
            queue.add(new Object[]{e.target, new LinkedHashSet<>(e.exclusions)});
        }
        while (!queue.isEmpty()) {
            Object[] item = queue.poll();
            String id = (String) item[0];
            @SuppressWarnings("unchecked")
            Set<String> ex = (Set<String>) item[1];
            if (stop.contains(id)) continue;
            List<Set<String>> prior = seen.computeIfAbsent(id, k -> new ArrayList<>());
            boolean covered = false;
            for (Set<String> s : prior) if (ex.containsAll(s)) { covered = true; break; }
            if (covered) continue;
            prior.add(ex);
            order.add(id);
            Pkg p = pkgs.get(id);
            if (p == null) continue;
            for (Edge e : p.deps.values()) {
                if (!walk.follows(e)) continue;
                if (excluded(e.target, ex)) continue;
                Set<String> next = new LinkedHashSet<>(ex);
                next.addAll(e.exclusions);
                queue.add(new Object[]{e.target, next});
            }
        }
        return new ArrayList<>(order);
    }

    /** All paths (as id lists) from the roots to the target, for `jpm why`. */
    public List<List<String>> paths(Map<String, Collection<Edge>> rootsByLabel, String target) {
        List<List<String>> out = new ArrayList<>();
        for (Map.Entry<String, Collection<Edge>> r : rootsByLabel.entrySet()) {
            for (Edge e : r.getValue()) {
                List<String> path = new ArrayList<>();
                path.add(r.getKey());
                walk(e.target, target, path, new LinkedHashSet<>(), out);
            }
        }
        return out;
    }

    private void walk(String id, String target, List<String> path, Set<String> onPath, List<List<String>> out) {
        if (out.size() > 50 || !onPath.add(id)) return;
        path.add(id);
        if (id.equals(target)) {
            out.add(new ArrayList<>(path));
        } else {
            Pkg p = pkgs.get(id);
            if (p != null) for (Edge e : p.deps.values()) walk(e.target, target, path, onPath, out);
        }
        path.remove(path.size() - 1);
        onPath.remove(id);
    }

    /** Packages in dependency order (dependencies before dependents) restricted to the given ids. */
    public List<Pkg> topo(Collection<String> ids) {
        Set<String> want = new LinkedHashSet<>(ids);
        List<Pkg> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        for (String id : want) visit(id, want, done, new LinkedHashSet<>(), out);
        return out;
    }

    private void visit(String id, Set<String> want, Set<String> done, Set<String> stack, List<Pkg> out) {
        if (done.contains(id) || !stack.add(id)) return;
        Pkg p = pkgs.get(id);
        if (p != null) for (Edge e : p.deps.values()) if (want.contains(e.target)) visit(e.target, want, done, stack, out);
        stack.remove(id);
        done.add(id);
        if (p != null) out.add(p);
    }
}
