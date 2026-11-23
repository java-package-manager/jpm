package dev.jpm.sbom;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.api.json.Jsonc;
import dev.jpm.license.Licenses;
import dev.jpm.maven.Pom;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.ResolveInputs;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@code jpm sbom}: a CycloneDX 1.6 software bill of materials for the locked graph. Every package the project's
 * modules depend on is a component with its package URL, its SHA-256 from {@code project.lock}, its license
 * from its POM, and whether it ships at run time; extensions and tools are left out, as {@code jpm audit} leaves
 * them out. The output has no timestamp, and its serial number is derived from its content, so the same lock
 * always gives the same file.
 */
public final class Sbom {
    private Sbom() {
    }

    /**
     * The CycloneDX version written.
     */
    public static final String SPEC_VERSION = "1.6";

    /**
     * The bill of materials for a project and its resolved graph.
     *
     * @param project    the project
     * @param graph      its resolved graph, with the graphs of isolated modules
     * @param poms       reads the cached POMs of Maven packages, for their licenses; null leaves licenses out
     * @param jpmVersion the version of jpm, named as the tool that wrote it
     * @return the document, as nested maps and lists for {@link Jsonc#write(Object)}
     */
    public static Map<String, Object> of(Project project, Graph graph, Pom.Loader poms, String jpmVersion) {
        List<Graph> graphs = new ArrayList<>();
        graphs.add(graph);
        graphs.addAll(graph.isolated.values());
        Map<String, Graph.Pkg> pkgs = new TreeMap<>();
        for (Graph g : graphs) {
            for (Graph.Pkg p : g.pkgs.values()) if (p.kind != Graph.Kind.LOCAL) pkgs.putIfAbsent(p.id, p);
        }
        Set<String> runtime = new LinkedHashSet<>(), any = new LinkedHashSet<>();
        for (Project.Module m : project.modules) {
            Graph g = graph.isolated.getOrDefault(m.name(), graph);
            runtime.addAll(g.closure(ResolveInputs.rootEdges(m, Manifest.Scope.RUNTIME), Graph.Walk.RUNTIME));
            for (Manifest.Scope s : Manifest.Scope.values()) any.addAll(g.closure(ResolveInputs.rootEdges(m, s), Graph.Walk.ALL));
        }

        boolean workspace = project.root.isWorkspaceRoot();
        Map<String, Object> bom = new LinkedHashMap<>();
        bom.put("bomFormat", "CycloneDX");
        bom.put("specVersion", SPEC_VERSION);
        bom.put("version", 1);
        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "application");
        tool.put("group", "dev.jpm");
        tool.put("name", "jpm");
        tool.put("version", jpmVersion);
        metadata.put("tools", obj("components", List.of(tool)));
        Map<String, Object> root = workspace ? workspaceComponent(project) : moduleComponent(project.modules.get(0));
        metadata.put("component", root);
        bom.put("metadata", metadata);

        List<Object> components = new ArrayList<>();
        Map<String, String> refs = new LinkedHashMap<>();
        for (Project.Module m : project.modules) {
            Map<String, Object> c = workspace ? moduleComponent(m) : root;
            refs.put(m.id(), (String) c.get("bom-ref"));
            if (workspace) components.add(c);
        }
        for (Graph.Pkg p : pkgs.values()) {
            if (!any.contains(p.id)) continue;
            Map<String, Object> c = component(p, poms);
            c.put("scope", runtime.contains(p.id) ? "required" : "excluded");
            refs.put(p.id, (String) c.get("bom-ref"));
            components.add(c);
        }
        bom.put("components", components);

        List<Object> dependencies = new ArrayList<>();
        if (workspace) {
            List<String> on = new ArrayList<>();
            for (Project.Module m : project.modules) on.add(refs.get(m.id()));
            dependencies.add(dependency((String) root.get("bom-ref"), on));
        }
        for (Project.Module m : project.modules) {
            Set<String> on = new LinkedHashSet<>();
            for (Manifest.Scope s : Manifest.Scope.values()) {
                for (Graph.Edge e : ResolveInputs.rootEdges(m, s)) if (refs.containsKey(e.target())) on.add(refs.get(e.target()));
            }
            dependencies.add(dependency(refs.get(m.id()), new ArrayList<>(on)));
        }
        for (Graph.Pkg p : pkgs.values()) {
            if (!refs.containsKey(p.id)) continue;
            List<String> on = new ArrayList<>();
            for (String d : p.deps.keySet()) if (refs.containsKey(d)) on.add(refs.get(d));
            dependencies.add(dependency(refs.get(p.id), on));
        }
        bom.put("dependencies", dependencies);

        String serial = "urn:uuid:" + UUID.nameUUIDFromBytes(Jsonc.write(bom).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : bom.entrySet()) {
            out.put(e.getKey(), e.getValue());
            if (e.getKey().equals("specVersion")) out.put("serialNumber", serial);
        }
        return out;
    }

    private static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return m;
    }

    private static Map<String, Object> dependency(String ref, List<String> on) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("ref", ref);
        d.put("dependsOn", on);
        return d;
    }

    private static Map<String, Object> workspaceComponent(Project project) {
        Manifest r = project.root;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", "application");
        c.put("bom-ref", "workspace");
        if (r.group != null) c.put("group", r.group);
        c.put("name", r.name != null ? r.name : project.dir.getFileName().toString());
        c.put("version", r.version != null ? r.version : "0.0.0");
        describe(c, r);
        return c;
    }

    private static Map<String, Object> moduleComponent(Project.Module m) {
        Manifest man = m.manifest;
        String group = man.group != null ? man.group : m.project.root.group;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", man.mains.isEmpty() ? "library" : "application");
        c.put("bom-ref", group != null ? purl(group, m.name(), m.version(), null) : m.id());
        if (group != null) c.put("group", group);
        c.put("name", m.name());
        c.put("version", m.version());
        if (group != null) c.put("purl", purl(group, m.name(), m.version(), null));
        describe(c, man.license != null ? man : m.project.root);
        if (man.description != null) c.put("description", man.description);
        return c;
    }

    private static void describe(Map<String, Object> c, Manifest man) {
        if (man.license != null) c.put("licenses", List.of(obj("expression", man.license)));
        if (man.url != null) c.put("externalReferences", List.of(obj("type", "website", "url", man.url)));
    }

    private static Map<String, Object> component(Graph.Pkg p, Pom.Loader poms) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", "library");
        switch (p.kind) {
            case MAVEN -> {
                String purl = purl(p.group(), p.name(), p.version, p.classifier());
                c.put("bom-ref", purl);
                c.put("group", p.group());
                c.put("name", p.name());
                c.put("version", p.version);
                c.put("purl", purl);
                if (p.sha256 != null) c.put("hashes", List.of(obj("alg", "SHA-256", "content", p.sha256)));
                List<Object> licenses = poms == null ? List.of() : licenses(poms, p);
                if (!licenses.isEmpty()) c.put("licenses", licenses);
            }
            case GIT -> {
                dev.jpm.DepKey k = dev.jpm.DepKey.parse(p.id);
                String purl = gitPurl(k, p);
                c.put("bom-ref", purl);
                c.put("group", k.group());
                c.put("name", k.module() != null ? k.artifact() + "/" + k.module() : k.artifact());
                c.put("version", p.version != null ? p.version : p.commit);
                c.put("purl", purl);
                String host = switch (k.host()) {
                    case "gitlab" -> "gitlab.com";
                    case "codeberg" -> "codeberg.org";
                    default -> "github.com";
                };
                c.put("externalReferences", List.of(obj("type", "vcs", "url", "https://" + host + "/" + k.group() + "/" + k.artifact()
                        + ".git" + (p.commit != null ? "#" + p.commit : ""))));
            }
            default -> {
                c.put("bom-ref", p.id + "@" + p.version);
                c.put("name", p.id);
                c.put("version", p.version);
            }
        }
        return c;
    }

    /**
     * The package URL of a Maven artifact: {@code pkg:maven/group/artifact@version}, with its classifier.
     */
    public static String purl(String group, String artifact, String version, String classifier) {
        return "pkg:maven/" + enc(group) + "/" + enc(artifact) + "@" + enc(version)
                + (classifier == null ? "" : "?classifier=" + enc(classifier));
    }

    private static String gitPurl(dev.jpm.DepKey k, Graph.Pkg p) {
        String version = p.commit != null ? p.commit : p.version;
        String sub = k.module() == null ? "" : "#" + enc(k.module());
        if (k.host().equals("github") || k.host().equals("gitlab")) {
            return "pkg:" + k.host() + "/" + enc(k.group()) + "/" + enc(k.artifact()) + "@" + enc(version) + sub;
        }
        return "pkg:generic/" + enc(k.artifact()) + "@" + enc(version) + "?vcs_url="
                + enc("git+https://codeberg.org/" + k.group() + "/" + k.artifact() + ".git") + sub;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static List<Object> licenses(Pom.Loader poms, Graph.Pkg p) {
        Pom pom;
        try {
            pom = poms.load(p.group(), p.name(), p.version);
        } catch (RuntimeException e) {
            return List.of();
        }
        for (Element e : pom.chain) {
            Element ls = child(e, "licenses");
            if (ls == null) continue;
            List<Object> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Node n = ls.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element l) || !l.getTagName().equals("license")) continue;
                Element ne = child(l, "name"), ue = child(l, "url");
                String name = ne == null ? null : ne.getTextContent().strip(), url = ue == null ? null : ue.getTextContent().strip();
                String id = Licenses.forNameOrUrl(name, url);
                Map<String, Object> lic = new LinkedHashMap<>();
                if (id != null) lic.put("id", id);
                else if (name != null && !name.isEmpty()) lic.put("name", name);
                else continue;
                if (url != null && !url.isEmpty() && id == null) lic.put("url", url);
                if (seen.add(lic.toString())) out.add(obj("license", lic));
            }
            return out;
        }
        return List.of();
    }

    private static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) return e;
        }
        return null;
    }
}
