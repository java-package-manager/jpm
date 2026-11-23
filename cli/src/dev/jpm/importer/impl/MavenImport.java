package dev.jpm.importer.impl;

import dev.jpm.importer.Imported;

import dev.jpm.maven.MavenRepo;
import dev.jpm.maven.Pom;
import dev.jpm.util.JpmException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class MavenImport {
    private MavenImport() {}

    private static final Set<String> KNOWN_PLUGINS = Set.of("maven-compiler-plugin", "maven-surefire-plugin",
            "maven-failsafe-plugin", "maven-jar-plugin", "maven-source-plugin", "maven-javadoc-plugin",
            "maven-install-plugin", "maven-deploy-plugin", "maven-resources-plugin", "maven-clean-plugin",
            "maven-site-plugin", "maven-enforcer-plugin", "maven-gpg-plugin", "maven-release-plugin",
            "exec-maven-plugin", "maven-shade-plugin", "maven-assembly-plugin", "spring-boot-maven-plugin",
            "jacoco-maven-plugin", "maven-checkstyle-plugin", "spotless-maven-plugin", "maven-dependency-plugin",
            "versions-maven-plugin", "flatten-maven-plugin", "maven-project-info-reports-plugin",
            "central-publishing-maven-plugin", "nexus-staging-maven-plugin", "maven-bundle-plugin",
            "maven-antrun-plugin", "animal-sniffer-maven-plugin", "maven-pmd-plugin", "spotbugs-maven-plugin",
            "native-maven-plugin", "license-maven-plugin", "apache-rat-plugin", "maven-scm-publish-plugin",
            "japicmp-maven-plugin", "maven-remote-resources-plugin", "moditect-maven-plugin", "maven-toolchains-plugin",
            "build-helper-maven-plugin", "git-commit-id-maven-plugin", "git-commit-id-plugin", "maven-help-plugin");

    private record Module(String dir, Pom pom) {}

    public static Imported run(Path root, MavenRepo repo) {
        Path rootPom = root.resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) throw new JpmException("No pom.xml in " + root);
        Pom.Loader loader = new Pom.Loader(repo);
        Imported out = new Imported("Maven");

        Pom rootModel = loader.loadFile(rootPom);
        List<Module> modules = new ArrayList<>();
        collect(root, rootPom, "", loader, modules, new LinkedHashSet<>(), out);
        boolean workspace = modules.size() != 1 || !modules.get(0).dir.isEmpty();

        Map<String, String> coordsToModule = new LinkedHashMap<>();
        for (Module m : modules) coordsToModule.put(m.pom.group + ":" + m.pom.artifact, m.pom.artifact);

        if (workspace) {
            Map<String, Object> ws = new LinkedHashMap<>();
            ws.put("$schema", dev.jpm.Manifest.SCHEMA_URL);
            ws.put("name", rootModel.artifact);
            if (rootModel.version != null) ws.put("version", rootModel.version);
            String license = license(rootModel, out, "");
            if (license != null) ws.put("license", license);
            ws.put("workspace", modules.stream().map(Module::dir).toList());
            List<String> repos = repositories(rootModel);
            if (!repos.isEmpty()) ws.put("repositories", repos);
            out.manifests.put("", ws);
        }
        for (Module m : modules) {
            Map<String, Object> man = module(m, rootModel, coordsToModule, workspace, out);
            out.manifests.put(m.dir, man);
        }
        return out;
    }

    private static void collect(Path root, Path pomFile, String dir, Pom.Loader loader, List<Module> out,
                                Set<Path> seen, Imported imp) {
        if (!seen.add(pomFile.toAbsolutePath().normalize())) return;
        Pom pom = loader.loadFile(pomFile);
        List<String> subs = new ArrayList<>();
        Element modulesEl = childOf(pom.chain.get(0), "modules");
        if (modulesEl != null) for (Element e : elements(modulesEl, "module")) subs.add(e.getTextContent().trim());
        Element profiles = childOf(pom.chain.get(0), "profiles");
        if (profiles != null) {
            for (Element pr : elements(profiles, "profile")) {
                Element act = childOf(pr, "activation");
                boolean byDefault = act != null && "true".equals(textOf(act, "activeByDefault"));
                Element pm = childOf(pr, "modules");
                if (pm == null) continue;
                if (byDefault) for (Element e : elements(pm, "module")) subs.add(e.getTextContent().trim());
                else imp.warn(dir, "modules of profile " + textOf(pr, "id") + " are not imported");
            }
        }
        if (!"pom".equals(pom.packaging)) {
            out.add(new Module(dir, pom));
        } else if (subs.isEmpty() && dir.isEmpty()) {
            out.add(new Module(dir, pom));
        }
        for (String s : subs) {
            Path sub = pomFile.getParent().resolve(s).normalize();
            Path subPom = Files.isDirectory(sub) ? sub.resolve("pom.xml") : sub;
            if (!Files.isRegularFile(subPom)) {
                imp.warn(dir, "module " + s + " has no pom.xml; skipped");
                continue;
            }
            String rel = root.relativize(subPom.getParent()).toString().replace('\\', '/');
            collect(root, subPom, rel, loader, out, seen, imp);
        }
    }

    private static Map<String, Object> module(Module m, Pom rootModel, Map<String, String> siblings, boolean workspace, Imported imp) {
        Pom pom = m.pom;
        String where = m.dir;
        Map<String, Object> man = new LinkedHashMap<>();
        man.put("$schema", dev.jpm.Manifest.SCHEMA_URL);
        man.put("name", pom.artifact);
        if (pom.group != null) man.put("group", pom.group);
        if (!workspace || !java.util.Objects.equals(pom.version, rootModel.version)) {
            if (pom.version != null) man.put("version", pom.version);
        }
        if (workspace && !java.util.Objects.equals(pom.version, rootModel.version)) {
            imp.warn(where, "version " + pom.version + " differs from the workspace version " + rootModel.version + "; jpm versions workspaces in lockstep by default");
        }
        String license = license(pom, imp, where);
        if (license != null && !(workspace && license.equals(license(rootModel, null, "")))) man.put("license", license);
        man.put("layout", "maven");

        Integer release = release(pom);
        if (release != null) {
            man.put("java", Imported.jdkFor(release));
            man.put("target", Integer.toString(release));
        }
        String main = mainClass(pom);
        if (main != null) man.put("main", main);
        if ("war".equals(pom.packaging) || "ear".equals(pom.packaging)) {
            imp.warn(where, pom.packaging + " packaging is built as a plain jar");
        }

        Map<String, Object> runtime = new TreeMap<>(), dev = new TreeMap<>(), test = new TreeMap<>();
        for (Pom.Dep d : pom.dependencies) {
            String key = d.group() + ":" + d.artifact();
            String scope = d.scope() == null ? "compile" : d.scope();
            if (d.type() != null && !d.type().equals("jar") && !d.type().equals("bundle")) {
                imp.warn(where, "dependency " + key + " of type " + d.type() + " skipped");
                continue;
            }
            boolean classified = d.classifier() != null && !d.classifier().isEmpty();
            Map<String, Object> target = switch (scope) {
                case "compile", "runtime" -> runtime;
                case "provided" -> dev;
                case "test" -> test;
                default -> null;
            };
            if (target == null) {
                imp.warn(where, scope + "-scoped dependency " + key + " skipped");
                continue;
            }
            String sibling = siblings.get(key);
            if (sibling != null && !classified) {
                target.put("workspace:" + sibling, "*");
                continue;
            }
            if (d.version() == null || d.version().contains("${")) {
                imp.warn(where, "no version for " + key + "; skipped");
                continue;
            }
            target.put(classified ? key + ":" + d.classifier() : key,
                    depValue(d.version(), d.exclusions(), scope.equals("runtime")));
        }
        for (String[] ap : processorPaths(pom)) {
            if (ap[2] == null) {
                String managed = managedVersion(pom, ap[0] + ":" + ap[1]);
                if (managed == null) {
                    imp.warn(where, "annotation processor " + ap[0] + ":" + ap[1] + " has no version; skipped");
                    continue;
                }
                ap[2] = managed;
            }
            dev.putIfAbsent(ap[0] + ":" + ap[1], ap[2]);
        }
        if (!runtime.isEmpty()) man.put("runtime", runtime);
        if (!dev.isEmpty()) man.put("dev", dev);
        if (!test.isEmpty()) man.put("test", test);
        if (!workspace) {
            List<String> repos = repositories(pom);
            if (!repos.isEmpty()) man.put("repositories", repos);
        }

        Element build = childOf(pom.chain.get(0), "build");
        if (build != null) {
            for (String custom : List.of("sourceDirectory", "testSourceDirectory")) {
                String v = textOf(build, custom);
                if (v != null && !v.endsWith("src/main/java") && !v.endsWith("src/test/java")) {
                    imp.warn(where, "custom " + custom + " " + v + " is not supported; jpm uses src/main/java and src/test/java");
                }
            }
        }
        Set<String> plugins = new LinkedHashSet<>();
        for (Element p : plugins(pom, false)) plugins.add(textOf(p, "artifactId"));
        for (String p : plugins) {
            if (p != null && !KNOWN_PLUGINS.contains(p)) imp.warn(where, "plugin " + p + " is not carried over");
        }
        return man;
    }

    static Object depValue(String version, java.util.Collection<String> exclusions, boolean runtimeOnly) {
        return depValue(version, exclusions, runtimeOnly, false);
    }

    static Object depValue(String version, java.util.Collection<String> exclusions, boolean runtimeOnly, boolean api) {
        java.util.List<String> ex = new ArrayList<>(exclusions);
        if (ex.isEmpty() && !runtimeOnly && !api) return version;
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("version", version);
        if (!ex.isEmpty()) o.put("exclude", new ArrayList<>(new java.util.TreeSet<>(ex)));
        if (runtimeOnly) o.put("runtimeOnly", Boolean.TRUE);
        if (api) o.put("api", Boolean.TRUE);
        return o;
    }

    private static String managedVersion(Pom pom, String key) {
        for (Pom.Dep d : pom.management.values()) if (d.key().equals(key)) return d.version();
        return null;
    }

    static Integer release(Pom pom) {
        Map<String, String> p = pom.declaredProperties;
        for (String k : List.of("maven.compiler.release")) if (p.containsKey(k)) return Imported.major(interp(pom, p.get(k)));
        for (Element plugin : plugins(pom, true)) {
            if (!"maven-compiler-plugin".equals(textOf(plugin, "artifactId"))) continue;
            Element conf = childOf(plugin, "configuration");
            if (conf == null) continue;
            for (String k : List.of("release", "target", "source")) {
                String v = textOf(conf, k);
                if (v != null) {
                    Integer r = Imported.major(interp(pom, v));
                    if (r != null) return r;
                }
            }
        }
        for (String k : List.of("maven.compiler.target", "maven.compiler.source", "java.version")) {
            if (p.containsKey(k)) {
                Integer r = Imported.major(interp(pom, p.get(k)));
                if (r != null) return r;
            }
        }
        return null;
    }

    static String mainClass(Pom pom) {
        for (Element plugin : plugins(pom, true)) {
            String id = textOf(plugin, "artifactId");
            Element conf = childOf(plugin, "configuration");
            if (id == null) continue;
            List<Element> confs = new ArrayList<>();
            if (conf != null) confs.add(conf);
            Element execs = childOf(plugin, "executions");
            if (execs != null) {
                for (Element ex : elements(execs, "execution")) {
                    Element c = childOf(ex, "configuration");
                    if (c != null) confs.add(c);
                }
            }
            for (Element c : confs) {
                String v = switch (id) {
                    case "maven-jar-plugin", "maven-assembly-plugin" -> deep(c, "archive", "manifest", "mainClass");
                    case "exec-maven-plugin", "spring-boot-maven-plugin" -> textOf(c, "mainClass");
                    case "maven-shade-plugin" -> deepAny(c, "mainClass");
                    default -> null;
                };
                if (v != null && !v.isBlank()) {
                    String r = interp(pom, v.trim());
                    if (!r.contains("${")) return r;
                }
            }
        }
        for (String k : List.of("exec.mainClass", "mainClass", "main.class", "start-class")) {
            String v = pom.declaredProperties.get(k);
            if (v != null && !v.isBlank()) return interp(pom, v.trim());
        }
        return null;
    }

    private static List<String[]> processorPaths(Pom pom) {
        List<String[]> out = new ArrayList<>();
        for (Element plugin : plugins(pom, true)) {
            if (!"maven-compiler-plugin".equals(textOf(plugin, "artifactId"))) continue;
            Element conf = childOf(plugin, "configuration");
            Element paths = conf == null ? null : childOf(conf, "annotationProcessorPaths");
            if (paths == null) continue;
            for (Element p : elements(paths, "path")) {
                String g = interp(pom, textOf(p, "groupId")), a = interp(pom, textOf(p, "artifactId"));
                String v = textOf(p, "version");
                if (g != null && a != null) out.add(new String[]{g, a, v == null ? null : interp(pom, v)});
            }
            if (!out.isEmpty()) break;
        }
        return out;
    }

    private static String license(Pom pom, Imported imp, String where) {
        for (Element e : pom.chain) {
            Element ls = childOf(e, "licenses");
            if (ls == null) continue;
            List<String> ids = new ArrayList<>();
            for (Element l : elements(ls, "license")) {
                String name = interp(pom, textOf(l, "name")), url = interp(pom, textOf(l, "url"));
                String id = dev.jpm.license.Licenses.forNameOrUrl(name, url);
                if (id == null) {
                    if (imp != null) imp.warn(where, "the license \"" + (name != null ? name : url) + "\" is not on the SPDX license list; set \"license\" by hand");
                    continue;
                }
                if (!ids.contains(id)) ids.add(id);
            }
            return ids.isEmpty() ? null : String.join(" OR ", ids);
        }
        return null;
    }

    private static List<String> repositories(Pom pom) {
        List<String> out = new ArrayList<>();
        Element reps = childOf(pom.chain.get(0), "repositories");
        if (reps == null) return out;
        for (Element r : elements(reps, "repository")) {
            String url = interp(pom, textOf(r, "url"));
            if (url == null || url.contains("repo.maven.apache.org") || url.contains("repo1.maven.org")) continue;
            out.add(url);
        }
        return out;
    }

    private static List<Element> plugins(Pom pom, boolean managed) {
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < pom.chain.size(); i++) {
            Element build = childOf(pom.chain.get(i), "build");
            if (build == null) continue;
            Element ps = childOf(build, "plugins");
            if (ps != null && (i == 0 || managed)) out.addAll(elements(ps, "plugin"));
            if (managed) {
                Element pm = childOf(build, "pluginManagement");
                Element mps = pm == null ? null : childOf(pm, "plugins");
                if (mps != null) out.addAll(elements(mps, "plugin"));
            }
        }
        return out;
    }

    private static String interp(Pom pom, String s) {
        if (s == null) return null;
        for (int i = 0; i < 10 && s.contains("${"); i++) {
            String before = s;
            for (Map.Entry<String, String> e : pom.properties.entrySet()) s = s.replace("${" + e.getKey() + "}", e.getValue());
            if (s.equals(before)) break;
        }
        return s;
    }

    private static String deep(Element e, String... path) {
        Element cur = e;
        for (int i = 0; i < path.length - 1; i++) {
            cur = childOf(cur, path[i]);
            if (cur == null) return null;
        }
        return textOf(cur, path[path.length - 1]);
    }

    private static String deepAny(Element e, String name) {
        NodeList nl = e.getElementsByTagName(name);
        return nl.getLength() == 0 ? null : nl.item(0).getTextContent().trim();
    }

    static Element childOf(Element parent, String name) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element e && e.getTagName().equals(name)) return e;
        }
        return null;
    }

    static List<Element> elements(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) if (nl.item(i) instanceof Element e && e.getTagName().equals(name)) out.add(e);
        return out;
    }

    static String textOf(Element parent, String name) {
        Element e = childOf(parent, name);
        return e == null ? null : e.getTextContent().trim();
    }
}
