package dev.jpm.importer.impl;

import dev.jpm.importer.Imported;

import dev.jpm.importer.impl.GradleImport.GDep;
import dev.jpm.importer.impl.GradleImport.GProject;
import dev.jpm.util.JpmException;
import dev.jpm.version.VersionSpecs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class GradleConvert {
    private GradleConvert() {}

    static void convert(List<GProject> projects, Imported imp) {
        GProject root = projects.stream().filter(p -> p.path.equals(":")).findFirst().orElse(projects.get(0));
        List<GProject> modules = new ArrayList<>();
        for (GProject p : projects) {
            if (!(p.java || p.kotlin)) continue;
            if (p == root && projects.size() > 1 && projects.stream().anyMatch(q -> q != root && (q.java || q.kotlin))) {
                imp.warn("root", "the root project applies the java plugin; a jpm workspace root holds no sources, so its own sources are not imported");
                continue;
            }
            modules.add(p);
        }
        if (modules.isEmpty()) throw new JpmException("The Gradle build has no Java projects to import");
        Map<String, String> byPath = new LinkedHashMap<>();
        for (GProject p : projects) byPath.put(p.path, p.name);
        boolean workspace = !(modules.size() == 1 && modules.get(0) == root);

        if (workspace) {
            Map<String, Object> ws = new LinkedHashMap<>();
            ws.put("$schema", dev.jpm.Manifest.SCHEMA_URL);
            ws.put("name", root.name);
            if (root.version != null) ws.put("version", root.version);
            ws.put("workspace", modules.stream().map(p -> p.dir).toList());
            List<String> repos = repositories(projects);
            if (!repos.isEmpty()) ws.put("repositories", repos);
            imp.manifests.put("", ws);
        }
        for (GProject p : modules) {
            String where = p.dir;
            Map<String, Object> man = new LinkedHashMap<>();
            man.put("$schema", dev.jpm.Manifest.SCHEMA_URL);
            man.put("name", p.name);
            if (p.group != null) man.put("group", p.group);
            if (p.version != null && (!workspace || !p.version.equals(root.version))) man.put("version", p.version);
            man.put("layout", "maven");
            if (p.release != null) {
                man.put("java", Imported.jdkFor(p.release));
                man.put("target", Integer.toString(p.release));
            }
            if (p.mainClass != null) man.put("main", p.mainClass);
            if (p.kotlin) {
                String kv = p.resolved.get("org.jetbrains.kotlin:kotlin-stdlib");
                for (List<GDep> l : p.deps.values()) {
                    for (GDep d : l) {
                        if (kv == null && "org.jetbrains.kotlin".equals(d.group()) && "kotlin-stdlib".equals(d.name())) kv = d.version();
                    }
                }
                if (kv != null && kv.matches("\\d+\\.\\d+\\.\\d+([-.][0-9A-Za-z.-]+)?")) {
                    man.put("kotlin", kv);
                } else {
                    imp.warn(where, "Kotlin sources: set \"kotlin\" to the Kotlin version and add org.jetbrains.kotlin:kotlin-stdlib to \"runtime\"");
                }
            }
            String expectedMain = (p.dir.isEmpty() ? "" : p.dir + "/") + "src/main/java";
            for (String d : p.mainJava) {
                if (!d.equals(expectedMain) && !d.endsWith("src/main/kotlin")) imp.warn(where, "source directory " + d + " is not built; jpm uses " + expectedMain);
            }
            Map<String, Object> runtime = new TreeMap<>(), dev = new TreeMap<>(), test = new TreeMap<>();
            for (Map.Entry<String, List<GDep>> e : p.deps.entrySet()) {
                String conf = e.getKey();
                Map<String, Object> target = switch (conf) {
                    case "api", "implementation", "runtimeOnly" -> runtime;
                    case "compileOnly", "compileOnlyApi", "annotationProcessor" -> dev;
                    default -> test;
                };
                for (GDep d : e.getValue()) {
                    if ("platform".equals(d.category()) || "enforced-platform".equals(d.category())) {
                        imp.warn(where, "platform " + (d.group() != null ? d.group() + ":" + d.name() : d.raw())
                                + " is not carried over; the versions it manages are written out instead");
                        continue;
                    }
                    if ("org.junit.platform".equals(d.group()) && "junit-platform-launcher".equals(d.name())) continue;
                    if (d.project() != null) {
                        String name = byPath.get(d.project().startsWith(":") ? d.project() : ":" + d.project());
                        if (name == null) {
                            imp.warn(where, "dependency on unknown project " + d.project() + " skipped");
                        } else {
                            target.put("workspace:" + name, d.excludes() == null || d.excludes().isEmpty() ? "*"
                                    : MavenImport.depValue("*", d.excludes(), false, false));
                        }
                        continue;
                    }
                    String key = d.group() + ":" + d.name();
                    String version = d.version();
                    boolean readable = version != null && !version.isBlank();
                    if (readable) {
                        try {
                            VersionSpecs.parse(version);
                        } catch (IllegalArgumentException unreadable) {
                            readable = false;
                        }
                    }
                    if (!readable) {
                        String res = p.resolved.get(key);
                        if (res != null) {
                            version = res;
                        } else {
                            imp.warn(where, "no version for " + key + (d.version() != null ? " (" + d.version() + ")" : "") + "; skipped");
                            continue;
                        }
                    }
                    String k = d.classifier() != null && !d.classifier().isEmpty() ? key + ":" + d.classifier() : key;
                    target.put(k, MavenImport.depValue(version, d.excludes(), conf.equals("runtimeOnly"), conf.equals("compileOnlyApi")));
                }
            }
            dev.keySet().removeAll(runtime.keySet());
            test.keySet().removeAll(runtime.keySet());
            if (!runtime.isEmpty()) man.put("runtime", runtime);
            if (!dev.isEmpty()) man.put("dev", dev);
            if (!test.isEmpty()) man.put("test", test);
            if (!workspace) {
                List<String> repos = repositories(projects);
                if (!repos.isEmpty()) man.put("repositories", repos);
            }
            imp.manifests.put(p.dir, man);
        }
    }

    static List<String> repositories(List<GProject> projects) {
        List<String> out = new ArrayList<>();
        for (GProject p : projects) {
            for (String r : p.repositories) {
                if (r.contains("repo.maven.apache.org") || r.contains("repo1.maven.org") || r.contains("plugins.gradle.org")) continue;
                String u = r.endsWith("/") ? r : r + "/";
                if (!out.contains(u)) out.add(u);
            }
        }
        return out;
    }
}
