package dev.jpm.resolve;

import dev.jpm.api.json.Jsonc;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code project.lock}: text-based JSONC, sorted, one line per package so diffs stay clean.
 * Every package pins an exact version (and commit for Git) plus a checksum; transitive packages are
 * pinned here too.
 */
public final class Lockfile {
    public static final int VERSION = 1;

    public final String inputs;
    public final Graph graph;
    /**
     * Whether the file had merge conflict markers. Its graph then merges both sides, the higher version of each
     * package winning, and its inputs are null, so it is always resolved again.
     */
    public final boolean conflicted;

    /**
     * A lockfile for a graph resolved from these inputs.
     */
    public Lockfile(String inputs, Graph graph) {
        this(inputs, graph, false);
    }

    private Lockfile(String inputs, Graph graph, boolean conflicted) {
        this.inputs = inputs;
        this.graph = graph;
        this.conflicted = conflicted;
    }

    /**
     * Reads {@code project.lock}; null when there is none.
     */
    public static Lockfile read(Path file) {
        if (!Files.exists(file)) return null;
        String text = Fs.read(file);
        if (hasConflictMarkers(text)) return merged(file, text);
        return parse(file, text);
    }

    private static Lockfile parse(Path file, String text) {
        Object parsed;
        try {
            parsed = Jsonc.parse(text);
        } catch (Jsonc.ParseException e) {
            throw new JpmException(file + " is corrupt (" + e.getMessage() + "). Delete it and run `jpm install`.");
        }
        if (!(parsed instanceof Map<?, ?> root)) throw new JpmException(file + " is corrupt");
        Object v = root.get("lockfileVersion");
        if (!(v instanceof BigDecimal bd) || bd.intValue() != VERSION) {
            throw new JpmException(file + " has an unsupported lockfileVersion. Delete it and run `jpm install`.");
        }
        Graph g = readPackages(root.get("packages"));
        if (root.get("isolated") instanceof Map<?, ?> iso) {
            for (Map.Entry<?, ?> e : iso.entrySet()) {
                Object pk = e.getValue() instanceof Map<?, ?> im ? im.get("packages") : null;
                g.isolated.put((String) e.getKey(), readPackages(pk));
            }
        }
        if (root.get("extensions") instanceof Map<?, ?> ext) {
            for (Map.Entry<?, ?> e : ext.entrySet()) {
                Object pk = e.getValue() instanceof Map<?, ?> em ? em.get("packages") : null;
                g.extensions.put((String) e.getKey(), readPackages(pk));
            }
        }
        return new Lockfile((String) root.get("inputs"), g);
    }

    /**
     * Whether a lockfile's text has Git merge conflict markers.
     */
    public static boolean hasConflictMarkers(String text) {
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("<<<<<<<") || line.startsWith(">>>>>>>")) return true;
        }
        return false;
    }

    private static Lockfile merged(Path file, String text) {
        Lockfile ours = null, theirs = null;
        try {
            ours = parse(file, side(text, true));
        } catch (JpmException ignored) {
        }
        try {
            theirs = parse(file, side(text, false));
        } catch (JpmException ignored) {
        }
        if (ours == null && theirs == null) {
            throw new JpmException(file + " has merge conflicts that jpm cannot read. Take either side"
                    + " (`git checkout --theirs " + file.getFileName() + "`) and run `jpm install`.");
        }
        if (ours == null || theirs == null) return new Lockfile(null, (ours != null ? ours : theirs).graph, true);
        Graph g = merge(ours.graph, theirs.graph);
        for (String name : union(ours.graph.isolated.keySet(), theirs.graph.isolated.keySet())) {
            g.isolated.put(name, merge(ours.graph.isolated.getOrDefault(name, new Graph()), theirs.graph.isolated.getOrDefault(name, new Graph())));
        }
        for (String name : union(ours.graph.extensions.keySet(), theirs.graph.extensions.keySet())) {
            g.extensions.put(name, merge(ours.graph.extensions.getOrDefault(name, new Graph()), theirs.graph.extensions.getOrDefault(name, new Graph())));
        }
        return new Lockfile(null, g, true);
    }

    private static java.util.Set<String> union(java.util.Set<String> a, java.util.Set<String> b) {
        java.util.Set<String> out = new java.util.TreeSet<>(a);
        out.addAll(b);
        return out;
    }

    /**
     * One side of a conflicted file: the lines outside the conflicts and, inside each, ours (between {@code <<<<<<<}
     * and {@code |||||||} or {@code =======}) or theirs (between {@code =======} and {@code >>>>>>>}). A comma is added
     * where a side's last entry is followed by another entry, since each side ends its entries as if it were alone.
     */
    static String side(String text, boolean ours) {
        List<String> out = new java.util.ArrayList<>();
        int state = 0;
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("<<<<<<<")) {
                state = 1;
                continue;
            }
            if (state != 0 && line.startsWith("|||||||")) {
                state = 2;
                continue;
            }
            if (state != 0 && line.startsWith("=======")) {
                state = 3;
                continue;
            }
            if (state != 0 && line.startsWith(">>>>>>>")) {
                state = 0;
                continue;
            }
            if (state == 0 || (ours && state == 1) || (!ours && state == 3)) out.add(line);
        }
        for (int i = 0; i < out.size(); i++) {
            String line = out.get(i).stripTrailing();
            if (line.isEmpty() || line.endsWith(",") || line.endsWith("{") || line.endsWith("[")) continue;
            int next = i + 1;
            while (next < out.size() && out.get(next).isBlank()) next++;
            if (next < out.size() && out.get(next).stripLeading().startsWith("\"")) out.set(i, line + ",");
        }
        return String.join("\n", out);
    }

    private static Graph merge(Graph a, Graph b) {
        Graph g = new Graph();
        for (Graph.Pkg p : a.pkgs.values()) g.put(p);
        for (Graph.Pkg p : b.pkgs.values()) {
            Graph.Pkg other = g.get(p.id);
            if (other == null || newer(p, other)) g.put(p);
        }
        return g;
    }

    private static boolean newer(Graph.Pkg p, Graph.Pkg than) {
        if (p.version == null || than.version == null) return than.version == null;
        try {
            return dev.jpm.version.Version.of(p.version).compareTo(dev.jpm.version.Version.of(than.version)) > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Graph readPackages(Object pkgs) {
        Graph g = new Graph();
        if (pkgs instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String id = (String) e.getKey();
                Map<?, ?> entry = (Map<?, ?>) e.getValue();
                Graph.Kind kind = entry.containsKey("commit") ? Graph.Kind.GIT
                        : id.equals(GradleDist.ID) ? Graph.Kind.GRADLE : id.startsWith("ext:") ? Graph.Kind.EXTENSION : Graph.Kind.MAVEN;
                Graph.Pkg p = new Graph.Pkg(id, kind);
                p.version = (String) entry.get("version");
                p.commit = (String) entry.get("commit");
                p.tag = (String) entry.get("tag");
                p.sha256 = (String) entry.get("sha256");
                p.pomSha256 = (String) entry.get("pom");
                if (Boolean.FALSE.equals(entry.get("jar"))) p.hasJar = false;
                Object deps = entry.get("dependencies");
                if (deps instanceof Map<?, ?> dm) {
                    for (Map.Entry<?, ?> d : dm.entrySet()) {
                        p.deps.put((String) d.getKey(), Graph.Edge.decode((String) d.getKey(), (String) d.getValue()));
                    }
                }
                g.put(p);
            }
        }
        return g;
    }

    /**
     * The lockfile's text, one line per package, sorted, so it diffs well.
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by jpm. Do not edit by hand.\n");
        sb.append("{\n");
        sb.append("  \"lockfileVersion\": ").append(VERSION).append(",\n");
        sb.append("  \"inputs\": ").append(Jsonc.quote(inputs)).append(",\n");
        sb.append("  \"packages\": ");
        packages(sb, graph, "  ");
        section(sb, "isolated", graph.isolated);
        section(sb, "extensions", graph.extensions);
        sb.append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void section(StringBuilder sb, String name, Map<String, Graph> graphs) {
        if (graphs.isEmpty()) return;
        sb.append(",\n  ").append(Jsonc.quote(name)).append(": {");
        int n = 0;
        for (Map.Entry<String, Graph> e : graphs.entrySet()) {
            sb.append(n++ == 0 ? "\n" : ",\n");
            sb.append("    ").append(Jsonc.quote(e.getKey())).append(": { \"packages\": ");
            packages(sb, e.getValue(), "    ");
            sb.append(" }");
        }
        sb.append("\n  }");
    }

    private static void packages(StringBuilder sb, Graph graph, String indent) {
        sb.append("{");
        List<Graph.Pkg> pkgs = graph.pkgs.values().stream().filter(p -> p.kind != Graph.Kind.LOCAL).toList();
        for (int i = 0; i < pkgs.size(); i++) {
            Graph.Pkg p = pkgs.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("version", p.version);
            if (p.tag != null) entry.put("tag", p.tag);
            if (p.commit != null) entry.put("commit", p.commit);
            if (p.sha256 != null) entry.put("sha256", p.sha256);
            if (p.pomSha256 != null) entry.put("pom", p.pomSha256);
            if (!p.hasJar) entry.put("jar", Boolean.FALSE);
            if (!p.deps.isEmpty()) {
                Map<String, Object> deps = new LinkedHashMap<>();
                for (Graph.Edge e : p.deps.values()) deps.put(e.target(), e.encode());
                entry.put("dependencies", deps);
            }
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append(indent).append("  ").append(Jsonc.quote(p.id)).append(": ").append(inline(entry));
        }
        sb.append(pkgs.isEmpty() ? "}" : "\n" + indent + "}");
    }

    private static String inline(Object v) {
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{ ");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (i++ > 0) sb.append(", ");
                sb.append(Jsonc.quote((String) e.getKey())).append(": ").append(inline(e.getValue()));
            }
            return sb.append(" }").toString();
        }
        if (v instanceof String s) return Jsonc.quote(s);
        return String.valueOf(v);
    }

    /**
     * Writes the lockfile.
     */
    public void write(Path file) {
        Fs.writeIfChanged(file, render());
    }
}
