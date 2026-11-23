package dev.jpm.audit;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.audit.impl.Osv;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.ResolveInputs;
import dev.jpm.version.Version;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code jpm audit}: the locked packages with known vulnerabilities, from the OSV database (https://osv.dev): Maven
 * packages by name and version, Git packages by commit.
 */
public final class Audit {
    private Audit() {}

    /** Where OSV is: {@code JPM_OSV_URL}, else https://api.osv.dev. */
    public static String osvUrl() {
        String v = System.getenv("JPM_OSV_URL");
        return v == null || v.isBlank() ? "https://api.osv.dev" : v;
    }

    /**
     * One vulnerability of one package.
     *
     * @param pkg      the package id, such as {@code org.apache.logging.log4j:log4j-core}
     * @param version  its locked version
     * @param id       the OSV id, such as {@code GHSA-jfh8-c2jp-5v3q}
     * @param aliases  other ids, such as {@code CVE-2021-44228}
     * @param severity {@code CRITICAL}, {@code HIGH}, {@code MODERATE}, {@code LOW}, or null when OSV gives none
     * @param summary  a one-line summary
     * @param fixed    the lowest version that fixes it for this version, or null when OSV names none
     * @param path     how the project gets the package: the module, then each package down to it
     */
    public record Finding(String pkg, String version, String id, List<String> aliases, String severity, String summary, String fixed,
                          List<String> path) {}

    /** Every finding for the project's graph (its modules' packages, not tools), without the ignored ids. */
    @SuppressWarnings("unchecked")
    public static List<Finding> run(Project project, Graph graph, String osvUrl, Set<String> ignore) {
        List<Graph.Pkg> pkgs = new ArrayList<>();
        List<Osv.Query> queries = new ArrayList<>();
        for (Graph.Pkg p : graph.pkgs.values()) {
            if (p.kind == Graph.Kind.MAVEN && p.version != null) {
                String[] gac = p.id.split(":");
                queries.add(new Osv.Query("Maven", gac[0] + ":" + gac[1], p.version, null));
                pkgs.add(p);
            } else if (p.kind == Graph.Kind.GIT && p.commit != null) {
                queries.add(new Osv.Query(null, null, null, p.commit));
                pkgs.add(p);
            }
        }
        if (queries.isEmpty()) return List.of();
        Osv osv = new Osv(osvUrl);
        List<List<String>> ids = osv.ids(queries);
        Map<String, List<String>> paths = paths(project, graph);
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < pkgs.size() && i < ids.size(); i++) {
            Graph.Pkg p = pkgs.get(i);
            for (String id : ids.get(i)) {
                Map<String, Object> v = osv.vulnerability(id);
                List<String> aliases = new ArrayList<>();
                if (v.get("aliases") instanceof List<?> l) for (Object a : l) aliases.add(String.valueOf(a));
                if (ignore.contains(id) || aliases.stream().anyMatch(ignore::contains)) continue;
                String severity = v.get("database_specific") instanceof Map<?, ?> d && d.get("severity") instanceof String s ? s : null;
                String summary = v.get("summary") instanceof String s ? s : v.get("details") instanceof String d ? d.lines().findFirst().orElse("") : "";
                out.add(new Finding(p.id, p.version, id, List.copyOf(aliases), severity, summary, fixed(v, p), paths.getOrDefault(p.id, List.of(p.id))));
            }
        }
        out.sort((a, b) -> Integer.compare(rank(b.severity()), rank(a.severity())));
        return out;
    }

    private static int rank(String severity) {
        if (severity == null) return 0;
        return switch (severity.toUpperCase(java.util.Locale.ROOT)) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MODERATE", "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    @SuppressWarnings("unchecked")
    private static String fixed(Map<String, Object> v, Graph.Pkg p) {
        if (p.kind != Graph.Kind.MAVEN || !(v.get("affected") instanceof List<?> affected)) return null;
        String[] gac = p.id.split(":");
        String name = gac[0] + ":" + gac[1];
        Version current = Version.of(p.version);
        String best = null;
        for (Object o : affected) {
            if (!(o instanceof Map<?, ?> a) || !(a.get("package") instanceof Map<?, ?> pk) || !name.equals(pk.get("name"))) continue;
            if (!(a.get("ranges") instanceof List<?> ranges)) continue;
            for (Object r : ranges) {
                if (!(r instanceof Map<?, ?> rm) || !(rm.get("events") instanceof List<?> events)) continue;
                String introduced = null;
                for (Object e : events) {
                    if (!(e instanceof Map<?, ?> em)) continue;
                    if (em.get("introduced") instanceof String in) introduced = in;
                    if (em.get("fixed") instanceof String fx) {
                        boolean inRange = (introduced == null || introduced.equals("0") || current.compareTo(Version.of(introduced)) >= 0)
                                && current.compareTo(Version.of(fx)) < 0;
                        if (inRange && (best == null || Version.of(fx).compareTo(Version.of(best)) < 0)) best = fx;
                    }
                }
            }
        }
        return best;
    }

    private static Map<String, List<String>> paths(Project project, Graph graph) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Project.Module m : project.modules) {
            Deque<List<String>> queue = new ArrayDeque<>();
            for (Manifest.Scope s : Manifest.Scope.values()) {
                for (Graph.Edge e : ResolveInputs.rootEdges(m, s)) queue.add(List.of(m.name(), e.target()));
            }
            while (!queue.isEmpty()) {
                List<String> path = queue.poll();
                String id = path.get(path.size() - 1);
                if (out.containsKey(id)) continue;
                out.put(id, path);
                Graph.Pkg p = graph.get(id);
                if (p == null) continue;
                for (String d : p.deps.keySet()) {
                    if (out.containsKey(d)) continue;
                    List<String> next = new ArrayList<>(path);
                    next.add(d);
                    queue.add(next);
                }
            }
        }
        return out;
    }
}
