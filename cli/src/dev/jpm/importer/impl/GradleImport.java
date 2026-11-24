package dev.jpm.importer.impl;

import dev.jpm.api.json.Jsonc;
import dev.jpm.importer.Imported;
import dev.jpm.log.Log;
import dev.jpm.toolchain.JavaSpec;
import dev.jpm.toolchain.Jdk;
import dev.jpm.toolchain.Toolchain;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class GradleImport {
    private GradleImport() {
    }

    static final class GProject {
        String path = ":";
        String name;
        String dir = "";
        String group;
        String version;
        boolean java;
        boolean kotlin;
        Integer release;
        String mainClass;
        final Map<String, List<GDep>> deps = new LinkedHashMap<>();
        final Map<String, String> resolved = new TreeMap<>();
        final List<String> repositories = new ArrayList<>();
        List<String> mainJava = List.of();
        List<String> testJava = List.of();
    }

    record GDep(String project, String group, String name, String version, String category, String raw,
                String classifier, List<String> excludes) {
        GDep(String project, String group, String name, String version, String category, String raw) {
            this(project, group, name, version, category, raw, null, List.of());
        }
    }

    public static Imported run(Path dir, dev.jpm.maven.MavenRepo repo) {
        Path launcher = launcher(dir);
        if (launcher == null) {
            Imported imp = GradleScripts.runStatic(dir, repo);
            imp.warn(null, "Gradle was not found (no gradlew and no gradle on PATH), so the build files were read as text;"
                    + " check the result");
            return imp;
        }
        return runModel(dir, launcher, repo);
    }

    static Path launcher(Path dir) {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        Path w = dir.resolve(win ? "gradlew.bat" : "gradlew");
        if (Files.isRegularFile(w)) return w;
        return Exec.which("gradle");
    }

    @SuppressWarnings("unchecked")
    public static Imported runModel(Path dir, Path launcher, dev.jpm.maven.MavenRepo repo) {
        Path tmp;
        try {
            tmp = Files.createTempDirectory("jpm-gradle");
        } catch (IOException e) {
            throw new JpmException("Could not create a temporary directory: " + e.getMessage());
        }
        try {
            Path script = tmp.resolve("jpm-model.gradle");
            Path out = tmp.resolve("model");
            Fs.mkdirs(out);
            Fs.write(script, GradleModelScript.TEXT);
            Jdk jdk = Toolchain.require(JavaSpec.any());
            List<String> cmd = new ArrayList<>();
            if (launcher.getFileName().toString().equals("gradlew") && !Files.isExecutable(launcher)) cmd.add("sh");
            cmd.add(launcher.toString());
            cmd.addAll(List.of("-q", "--no-daemon", "--console=plain", "--init-script", script.toString(),
                    "-Djpm.model.output=" + out, "-Dorg.gradle.configuration-cache=false",
                    "-Dorg.gradle.unsafe.isolated-projects=false", "jpmModel"));
            Log.step("Reading", "the Gradle build with " + launcher.getFileName() + Log.dim(" (runs the build scripts)"));
            Map<String, String> env = new LinkedHashMap<>();
            env.put("JAVA_HOME", jdk.home().toString());
            Exec.Result r = Exec.capture(dir, cmd, env);
            List<Path> reports = Fs.files(out, ".json");
            if (!r.ok() || reports.isEmpty()) {
                throw new JpmException("Gradle could not evaluate the build:\n" + (r.err() + r.out()).strip()
                        + "\nFix the build, or import without running Gradle: `jpm import --static`.");
            }
            List<Map<String, Object>> models = new ArrayList<>();
            for (Path f : reports) models.add((Map<String, Object>) Jsonc.parse(Fs.read(f)));
            models.sort(java.util.Comparator.comparing((Map<String, Object> m) -> !":".equals(m.get("path")))
                    .thenComparing(m -> String.valueOf(m.get("path"))));
            List<GProject> projects = new ArrayList<>();
            Imported imp = new Imported("Gradle");
            for (Map<String, Object> pm : models) {
                projects.add(fromModel(pm));
                if (pm.get("resolveError") != null) {
                    imp.warn((String) pm.get("dir"), "Gradle could not resolve " + pm.get("resolveError")
                            + "; versions it did not resolve are read from the platforms' POMs instead");
                }
            }
            GradleScripts.applyPlatforms(projects, repo, imp);
            GradleConvert.convert(projects, imp);
            GradleScripts.spotless(dir, projects, imp);
            return imp;
        } finally {
            Fs.deleteTree(tmp);
        }
    }

    @SuppressWarnings("unchecked")
    static GProject fromModel(Map<String, Object> m) {
        GProject p = new GProject();
        p.path = (String) m.get("path");
        p.name = (String) m.get("name");
        p.dir = (String) m.getOrDefault("dir", "");
        p.group = blankToNull((String) m.get("group"));
        p.version = blankToNull((String) m.get("version"));
        if ("unspecified".equals(p.version)) p.version = null;
        p.java = Boolean.TRUE.equals(m.get("java"));
        p.kotlin = Boolean.TRUE.equals(m.get("kotlin"));
        Object rel = m.get("release");
        if (rel == null) rel = m.get("toolchain");
        if (rel == null) rel = m.get("target");
        p.release = rel == null ? null : Imported.major(rel.toString());
        p.mainClass = blankToNull((String) m.get("mainClass"));
        Map<String, Object> deps = (Map<String, Object>) m.getOrDefault("deps", Map.of());
        for (Map.Entry<String, Object> e : deps.entrySet()) {
            List<GDep> list = new ArrayList<>();
            for (Object o : (List<Object>) e.getValue()) {
                Map<String, Object> d = (Map<String, Object>) o;
                List<String> ex = new ArrayList<>();
                for (Object x : (List<Object>) d.getOrDefault("excludes", List.of())) {
                    Map<String, Object> xm = (Map<String, Object>) x;
                    String g = (String) xm.get("group"), mod = (String) xm.get("module");
                    if (g != null) ex.add(g + ":" + (mod == null ? "*" : mod));
                }
                list.add(new GDep((String) d.get("project"), (String) d.get("group"), (String) d.get("name"),
                        (String) d.get("version"), (String) d.get("category"), null, (String) d.get("classifier"), ex));
            }
            p.deps.put(e.getKey(), list);
        }
        Map<String, Object> resolved = (Map<String, Object>) m.getOrDefault("resolved", Map.of());
        for (Map.Entry<String, Object> e : resolved.entrySet()) p.resolved.put(e.getKey(), String.valueOf(e.getValue()));
        for (Object r : (List<Object>) m.getOrDefault("repositories", List.of())) p.repositories.add(r.toString());
        Map<String, Object> ss = (Map<String, Object>) m.getOrDefault("sourceSets", Map.of());
        if (ss.get("main") instanceof Map<?, ?> main) p.mainJava = (List<String>) main.get("java");
        if (ss.get("test") instanceof Map<?, ?> test) p.testJava = (List<String>) test.get("java");
        return p;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
