package dev.jpm.maven;

import dev.jpm.util.JpmException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The parts of an effective POM that matter for dependency resolution: parent inheritance, property
 * interpolation, dependency management (including imported BOMs) and relocation.
 */
public final class Pom {
    public record Dep(String group, String artifact, String version, String scope, boolean optional,
                      String type, String classifier, Set<String> exclusions) {
        /**
         * {@code group:artifact}.
         */
        public String key() {
            return group + ":" + artifact;
        }
    }

    public String group;
    public String artifact;
    public String version;
    public String packaging = "jar";
    public final Map<String, String> properties = new LinkedHashMap<>();
    public final Map<String, Dep> management = new LinkedHashMap<>();
    public final List<Dep> dependencies = new ArrayList<>();
    /**
     * Relocation target as group:artifact:version, or null.
     */
    public String relocation;
    /**
     * The raw POM elements: this POM first, then its parents.
     */
    public final List<Element> chain = new ArrayList<>();
    /**
     * True if Gradle published this POM next to Gradle Module Metadata (a .module file).
     */
    public boolean gradleMetadata;
    /**
     * Local file this POM was read from (null for POMs from a repository).
     */
    public Path file;
    /**
     * Raw explicit properties of this POM and its parents (no built-ins), for importers.
     */
    public final Map<String, String> declaredProperties = new LinkedHashMap<>();

    /**
     * Loads POMs and builds effective models, memoized.
     */
    public static final class Loader {
        private final MavenRepo repo;
        private final Profiles.Context profiles;
        private final Map<String, Pom> memo = new HashMap<>();

        /**
         * A loader that reads POMs from these repositories, activating profiles for this machine.
         */
        public Loader(MavenRepo repo) {
            this(repo, Profiles.Context.current());
        }

        /**
         * A loader that reads POMs from these repositories, activating profiles against {@code profiles}.
         */
        public Loader(MavenRepo repo, Profiles.Context profiles) {
            this.repo = repo;
            this.profiles = profiles;
        }

        /**
         * The effective POM of a version: parents, properties and imported BOMs applied, cached per run.
         */
        public Pom load(String group, String artifact, String version) {
            String key = group + ":" + artifact + ":" + version;
            Pom cached = memo.get(key);
            if (cached != null) return cached;
            Pom p = build(group, artifact, version, new LinkedHashSet<>());
            memo.put(key, p);
            return p;
        }

        /**
         * Effective model of a POM on disk; parents are found through relativePath first, then the repository.
         */
        public Pom loadFile(Path pomFile) {
            Path f = pomFile.toAbsolutePath().normalize();
            String key = "file:" + f;
            Pom cached = memo.get(key);
            if (cached != null) return cached;
            Pom p = buildFrom(parseXml(f), f, null, null, null, new LinkedHashSet<>());
            memo.put(key, p);
            return p;
        }

        private Pom build(String group, String artifact, String version, Set<String> stack) {
            Path file = repo.require(group, artifact, version, null, "pom");
            Pom p = buildFrom(parseXml(file), null, group, artifact, version, stack);
            p.gradleMetadata = dev.jpm.util.Fs.read(file).contains("published-with-gradle-metadata");
            return p;
        }

        private Pom parentOf(Element parentEl, Path localFile, Set<String> stack) {
            String pg = text(parentEl, "groupId"), pa = text(parentEl, "artifactId"), pv = text(parentEl, "version");
            if (localFile != null) {
                Element rp = child(parentEl, "relativePath");
                String rel = rp == null ? "../pom.xml" : rp.getTextContent().trim();
                if (!rel.isEmpty()) {
                    Path cand = localFile.getParent().resolve(rel).normalize();
                    if (java.nio.file.Files.isDirectory(cand)) cand = cand.resolve("pom.xml");
                    if (java.nio.file.Files.isRegularFile(cand)) {
                        Element pe = parseXml(cand);
                        Element pp = child(pe, "parent");
                        String g = or(text(pe, "groupId"), pp == null ? null : text(pp, "groupId"));
                        String v = or(text(pe, "version"), pp == null ? null : text(pp, "version"));
                        if (pa.equals(text(pe, "artifactId")) && pg.equals(g) && pv.equals(v)) {
                            String key = "file:" + cand;
                            Pom cached = memo.get(key);
                            if (cached == null) {
                                cached = buildFrom(pe, cand, null, null, null, stack);
                                memo.put(key, cached);
                            }
                            return cached;
                        }
                    }
                }
            }
            Pom parent = memo.get(pg + ":" + pa + ":" + pv);
            if (parent == null) {
                parent = build(pg, pa, pv, stack);
                memo.put(pg + ":" + pa + ":" + pv, parent);
            }
            return parent;
        }

        private Pom buildFrom(Element project, Path localFile, String group, String artifact, String version, Set<String> stack) {
            String key = localFile != null ? "file:" + localFile : group + ":" + artifact + ":" + version;
            if (!stack.add(key)) throw new JpmException("Cycle in POM parents: " + stack);
            Pom pom = new Pom();
            pom.file = localFile;
            pom.chain.add(project);
            Pom parent = null;
            Element parentEl = child(project, "parent");
            if (parentEl != null) {
                parent = parentOf(parentEl, localFile, stack);
                pom.chain.addAll(parent.chain);
            }
            pom.group = or(text(project, "groupId"), parent != null ? parent.group : group);
            pom.artifact = or(text(project, "artifactId"), artifact);
            pom.version = or(text(project, "version"), parent != null ? parent.version : version);
            pom.packaging = or(text(project, "packaging"), "jar");

            if (parent != null) pom.properties.putAll(parent.properties);
            if (parent != null) pom.declaredProperties.putAll(parent.declaredProperties);
            Element props = child(project, "properties");
            if (props != null) {
                for (Element e : children(props)) {
                    pom.properties.put(e.getTagName(), e.getTextContent().trim());
                    pom.declaredProperties.put(e.getTagName(), e.getTextContent().trim());
                }
            }
            List<Element> profiles = Profiles.active(project, localFile, this.profiles);
            for (Element prof : profiles) {
                Element pp = child(prof, "properties");
                if (pp == null) continue;
                for (Element e : children(pp)) {
                    pom.properties.put(e.getTagName(), e.getTextContent().trim());
                    pom.declaredProperties.put(e.getTagName(), e.getTextContent().trim());
                }
            }
            pom.properties.put("project.groupId", pom.group);
            pom.properties.put("project.artifactId", pom.artifact);
            pom.properties.put("project.version", pom.version);
            pom.properties.put("pom.groupId", pom.group);
            pom.properties.put("pom.version", pom.version);
            pom.properties.put("version", pom.version);
            pom.properties.put("groupId", pom.group);
            if (parent != null) {
                pom.properties.put("project.parent.version", parent.version);
                pom.properties.put("project.parent.groupId", parent.group);
                pom.properties.put("parent.version", parent.version);
            }
            for (String prop : List.of("java.version", "java.specification.version")) {
                pom.properties.putIfAbsent(prop, System.getProperty(prop, "21"));
            }

            if (parent != null) pom.management.putAll(parent.management);
            List<Element> sections = new ArrayList<>(List.of(project));
            sections.addAll(profiles);
            List<Dep> imports = new ArrayList<>();
            for (Element section : sections) {
                Element dm = child(section, "dependencyManagement");
                if (dm == null) continue;
                Element deps = child(dm, "dependencies");
                if (deps != null) {
                    for (Element d : children(deps, "dependency")) {
                        Dep dep = dep(d, pom.properties);
                        if ("import".equals(dep.scope) && "pom".equals(dep.type)) imports.add(dep);
                        else pom.management.put(mgmtKey(dep), dep);
                    }
                }
            }
            for (Dep bom : imports) {
                if (bom.version == null) continue;
                Pom b = load(bom.group, bom.artifact, bom.version);
                for (Map.Entry<String, Dep> e : b.management.entrySet()) pom.management.putIfAbsent(e.getKey(), e.getValue());
            }

            Map<String, Dep> deps = new LinkedHashMap<>();
            if (parent != null) for (Dep d : parent.dependencies) deps.put(mgmtKey(d), d);
            for (Element section : sections) {
                Element depsEl = child(section, "dependencies");
                if (depsEl == null) continue;
                for (Element d : children(depsEl, "dependency")) {
                    Dep dep = dep(d, pom.properties);
                    deps.put(mgmtKey(dep), dep);
                }
            }
            for (Dep d : deps.values()) {
                Dep m = pom.management.get(mgmtKey(d));
                String v = d.version != null ? d.version : m != null ? m.version : null;
                String scope = d.scope != null ? d.scope : m != null && m.scope != null ? m.scope : "compile";
                Set<String> excl = new LinkedHashSet<>(d.exclusions);
                if (m != null) excl.addAll(m.exclusions);
                pom.dependencies.add(new Dep(d.group, d.artifact, v, scope, d.optional, d.type, d.classifier, excl));
            }

            Element dist = child(project, "distributionManagement");
            Element reloc = dist == null ? null : child(dist, "relocation");
            if (reloc != null) {
                pom.relocation = or(interp(text(reloc, "groupId"), pom.properties), pom.group) + ":"
                        + or(interp(text(reloc, "artifactId"), pom.properties), pom.artifact) + ":"
                        + or(interp(text(reloc, "version"), pom.properties), pom.version);
            }
            stack.remove(key);
            return pom;
        }
    }

    private static String mgmtKey(Dep d) {
        return d.group + ":" + d.artifact + ":" + (d.type == null ? "jar" : d.type) + (d.classifier == null ? "" : ":" + d.classifier);
    }

    private static Dep dep(Element d, Map<String, String> props) {
        Set<String> excl = new LinkedHashSet<>();
        Element ex = child(d, "exclusions");
        if (ex != null) {
            for (Element e : children(ex, "exclusion")) {
                excl.add(interp(text(e, "groupId"), props) + ":" + or(interp(text(e, "artifactId"), props), "*"));
            }
        }
        String type = or(interp(text(d, "type"), props), "jar");
        return new Dep(interp(text(d, "groupId"), props), interp(text(d, "artifactId"), props),
                interp(text(d, "version"), props), interp(text(d, "scope"), props),
                "true".equals(interp(text(d, "optional"), props)), type, interp(text(d, "classifier"), props), excl);
    }

    private static final Pattern PROP = Pattern.compile("\\$\\{([^}]+)}");

    static String interp(String s, Map<String, String> props) {
        if (s == null) return null;
        for (int depth = 0; depth < 10 && s.contains("${"); depth++) {
            Matcher m = PROP.matcher(s);
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String v = props.get(m.group(1));
                if (v != null) changed = true;
                m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group(0)));
            }
            m.appendTail(sb);
            s = sb.toString();
            if (!changed) break;
        }
        return s;
    }

    private static String or(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    static Element parseXml(Path file) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setExpandEntityReferences(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            Document doc = b.parse(file.toFile());
            return doc.getDocumentElement();
        } catch (Exception e) {
            throw new JpmException("Could not parse " + file + ": " + e.getMessage());
        }
    }

    static Element child(Element parent, String name) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element e && e.getTagName().equals(name)) return e;
        }
        return null;
    }

    static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) if (nl.item(i) instanceof Element e) out.add(e);
        return out;
    }

    static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Element e : children(parent)) if (e.getTagName().equals(name)) out.add(e);
        return out;
    }

    static String text(Element parent, String name) {
        Element e = child(parent, name);
        return e == null ? null : e.getTextContent().trim();
    }
}
