package dev.jpm.importer;

import dev.jpm.json.Jsonc;
import dev.jpm.toolchain.JavaSpec;
import dev.jpm.toolchain.Jdk;
import dev.jpm.toolchain.Toolchain;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;
import dev.jpm.version.VersionSpecs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a Gradle build into jpm manifests.
 *
 * <p>Two ways to read the build. {@link #runModel} runs Gradle with an init script that reports the
 * evaluated model, which is exact but executes the build's scripts, so it is used only for an explicit
 * {@code jpm import} of your own project. {@link #runStatic} reads {@code settings.gradle(.kts)},
 * {@code build.gradle(.kts)}, {@code gradle.properties} and {@code gradle/libs.versions.toml} as text
 * and never executes anything; it is used for Git dependencies and when Gradle is not available.
 */
public final class GradleImport {
    private GradleImport() {}

    /** One Gradle project, as either reader sees it. */
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
        /** configuration name to dependencies */
        final Map<String, List<GDep>> deps = new LinkedHashMap<>();
        /** group:name to resolved version (model reader only) */
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
            Imported imp = runStatic(dir, repo);
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
            applyPlatforms(projects, repo, imp);
            convert(projects, imp);
            return imp;
        } finally {
            Fs.deleteTree(tmp);
        }
    }

    @SuppressWarnings("unchecked")
    private static GProject fromModel(Map<String, Object> m) {
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

    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");

    /** Removes {@code name { ... }} blocks (balanced braces) from build script text. */
    static String withoutBlocks(String text, String... names) {
        String out = text;
        for (String name : names) {
            Matcher m = Pattern.compile("\\b" + name + "\\s*\\{").matcher(out);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            while (m.find(last)) {
                int i = m.end(), depth = 1;
                while (i < out.length() && depth > 0) {
                    char c = out.charAt(i++);
                    if (c == '{') depth++;
                    else if (c == '}') depth--;
                }
                sb.append(out, last, m.start());
                last = i;
            }
            sb.append(out.substring(Math.min(last, out.length())));
            out = sb.toString();
        }
        return out;
    }

    public static Imported runStatic(Path dir) {
        return runStatic(dir, null);
    }

    /** Reads the build as text. With a repository, versions managed by platforms (BOMs) are looked up in their POMs. */
    public static Imported runStatic(Path dir, dev.jpm.maven.MavenRepo repo) {
        Imported imp = new Imported("Gradle");
        Map<String, String> props = gradleProperties(dir.resolve("gradle.properties"));
        String settings = firstExisting(dir, "settings.gradle.kts", "settings.gradle");
        Catalog catalog = Catalog.read(catalogFile(dir, settings));
        List<GProject> projects = new ArrayList<>();
        GProject root = new GProject();
        root.name = dir.getFileName().toString();
        if (settings != null) {
            Matcher rn = Pattern.compile("rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']").matcher(settings);
            if (rn.find()) root.name = rn.group(1);
        }
        projects.add(root);
        if (settings != null) {
            Matcher inc = Pattern.compile("(?m)^\\s*include(?:\\s*\\(|\\s+)(.*)$").matcher(stripComments(settings));
            while (inc.find()) {
                Matcher q = QUOTED.matcher(inc.group(1));
                while (q.find()) {
                    String path = q.group(1).startsWith(":") ? q.group(1) : ":" + q.group(1);
                    GProject p = new GProject();
                    p.path = path;
                    p.name = path.substring(path.lastIndexOf(':') + 1);
                    p.dir = path.substring(1).replace(':', '/');
                    projects.add(p);
                }
            }
            if (settings.contains("projectDir")) imp.warn(null, "custom projectDir settings are not supported; modules are assumed to sit at their path");
        }
        String rootBuild = firstExisting(dir, "build.gradle.kts", "build.gradle");
        String defGroup = props.get("group"), defVersion = props.get("version");
        if (rootBuild != null) {
            String rb = stripComments(rootBuild);
            String g = assignment(topLevel(rb), "group"), v = assignment(topLevel(rb), "version");
            for (String body : blockBodies(rb, "allprojects", "subprojects")) {
                if (g == null) g = assignment(topLevel(body), "group");
                if (v == null) v = assignment(topLevel(body), "version");
            }
            if (g != null) defGroup = g;
            if (v != null) defVersion = v;
        }
        for (GProject p : projects) {
            Path pd = p.dir.isEmpty() ? dir : dir.resolve(p.dir);
            String build = p.dir.isEmpty() ? rootBuild : firstExisting(pd, "build.gradle.kts", "build.gradle");
            p.group = defGroup;
            p.version = defVersion;
            if (build == null) continue;
            if (!p.dir.isEmpty()) {
                String g = assignment(topLevel(stripComments(build)), "group");
                String v = assignment(topLevel(stripComments(build)), "version");
                if (g != null) p.group = g;
                if (v != null) p.version = v;
            }
            String noComments = stripComments(build);
            String own = p.dir.isEmpty() ? withoutBlocks(noComments, "subprojects", "allprojects") : noComments;
            p.java = Pattern.compile("\\b(java|java-library|application)\\b|id\\s*\\(?\\s*[\"'](java|java-library|application)[\"']|apply\\s+plugin:\\s*[\"']java")
                    .matcher(own).find() || own.contains("libs.plugins.");
            p.kotlin = noComments.contains("kotlin(\"jvm\")") || noComments.contains("org.jetbrains.kotlin.jvm");
            p.release = javaVersion(noComments);
            p.mainClass = mainClass(noComments);
            readDependencies(noComments, p, catalog, accessors(projects), imp);
            readRepositories(noComments, p);
        }
        if (rootBuild != null) {
            String rb = stripComments(rootBuild);
            boolean shared = rb.contains("subprojects") || rb.contains("allprojects");
            if (shared) {
                Integer rel = javaVersion(rb);
                for (GProject p : projects) {
                    if (!p.dir.isEmpty()) {
                        if (p.release == null) p.release = rel;
                        if (rb.matches("(?s).*(subprojects|allprojects)\\s*\\{.*(java|java-library).*")) p.java = true;
                    }
                }
            }
        }
        for (GProject p : projects) {
            Path pd = p.dir.isEmpty() ? dir : dir.resolve(p.dir);
            if (!p.java && Files.isDirectory(pd.resolve("src/main/java"))) p.java = true;
        }
        applyPlatforms(projects, repo, imp);
        imp.warn(null, "the Gradle build was read without running it; check the result");
        convert(projects, imp);
        return imp;
    }

    /**
     * Fills in versions that platforms (BOMs) manage: each platform's POM is read from the repository,
     * which executes nothing.
     */
    private static void applyPlatforms(List<GProject> projects, dev.jpm.maven.MavenRepo repo, Imported imp) {
        if (repo == null) return;
        dev.jpm.maven.Pom.Loader loader = new dev.jpm.maven.Pom.Loader(repo);
        for (GProject p : projects) {
            Map<String, String> managed = new LinkedHashMap<>();
            for (List<GDep> list : p.deps.values()) {
                for (GDep d : list) {
                    if (!"platform".equals(d.category())) continue;
                    String[] coords = platformCoords(d);
                    if (coords == null) continue;
                    try {
                        for (dev.jpm.maven.Pom.Dep m : loader.load(coords[0], coords[1], coords[2]).management.values()) {
                            if (m.version() != null) managed.putIfAbsent(m.key(), m.version());
                        }
                    } catch (JpmException e) {
                        imp.warn(p.dir, "could not read platform " + String.join(":", coords) + ": " + e.getMessage());
                    }
                }
            }
            for (Map.Entry<String, String> e : managed.entrySet()) p.resolved.putIfAbsent(e.getKey(), e.getValue());
        }
    }

    /** {group, name, version} of a platform dependency read as text. */
    private static String[] platformCoords(GDep d) {
        if (d.group() != null && d.version() != null) return new String[]{d.group(), d.name(), d.version()};
        return null;
    }

    private static String firstExisting(Path dir, String... names) {
        for (String n : names) {
            Path f = dir.resolve(n);
            if (Files.isRegularFile(f)) return Fs.read(f);
        }
        return null;
    }

    private static Map<String, String> gradleProperties(Path f) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) return out;
        for (String line : Fs.read(f).split("\r?\n")) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) eq = line.indexOf(':');
            if (eq > 0) out.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return out;
    }

    private static String assignment(String build, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*(?:project\\.)?" + key + "\\s*=\\s*[\"']([^\"']+)[\"']").matcher(build);
        return m.find() ? m.group(1) : null;
    }

    static Integer javaVersion(String build) {
        String[] patterns = {
                "JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)",
                "options\\.release(?:\\.set)?\\s*[=(]\\s*(\\d+)",
                "release(?:\\.set)?\\s*[=(]\\s*(\\d+)",
                "(?:source|target)Compatibility\\s*=\\s*(JavaVersion\\.VERSION_[\\d_]+|[\"']?[\\d.]+[\"']?)",
                "jvmToolchain\\(\\s*(\\d+)\\s*\\)"};
        for (String pat : patterns) {
            Matcher m = Pattern.compile(pat).matcher(build);
            if (m.find()) {
                Integer r = Imported.major(m.group(1).replace("\"", "").replace("'", ""));
                if (r != null) return r;
            }
        }
        return null;
    }

    static String mainClass(String build) {
        Matcher m = Pattern.compile("mainClass(?:Name)?(?:\\.set)?\\s*[=(]\\s*[\"']([\\w.$]+)[\"']").matcher(build);
        return m.find() ? m.group(1) : null;
    }

    /** Build script text without comments; strings (with the {@code //} of their URLs) are kept. */
    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (text.startsWith("\"\"\"", i)) {
                int end = text.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                out.append(text, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && text.charAt(j) != c && text.charAt(j) != '\n') j += text.charAt(j) == '\\' ? 2 : 1;
                j = Math.min(n, j + 1);
                out.append(text, i, j);
                i = j;
            } else if (text.startsWith("//", i)) {
                while (i < n && text.charAt(i) != '\n') i++;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The text with every {@code { ... }} block taken out: what is set at the top level. */
    static String topLevel(String text) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth = Math.max(0, depth - 1);
            else if (depth == 0) out.append(c);
        }
        return out.toString();
    }

    /** The bodies of {@code name { ... }} blocks. */
    static List<String> blockBodies(String text, String... names) {
        List<String> out = new ArrayList<>();
        for (String name : names) {
            Matcher m = Pattern.compile("\\b" + name + "\\s*\\{").matcher(text);
            while (m.find()) {
                int end = closing(text, m.end() - 1, '{', '}');
                out.add(text.substring(m.end(), Math.max(m.end(), end)));
            }
        }
        return out;
    }

    /** The index of the bracket closing the one at {@code open}, or the end of the text. */
    private static int closing(String text, int open, char o, char c) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"' || ch == '\'') {
                int j = i + 1;
                while (j < text.length() && text.charAt(j) != ch && text.charAt(j) != '\n') j += text.charAt(j) == '\\' ? 2 : 1;
                i = j;
                continue;
            }
            if (ch == o) depth++;
            else if (ch == c && --depth == 0) return i;
        }
        return text.length();
    }

    /** The version catalog: the file settings name in versionCatalogs { ... from(files("...")) }, else gradle/libs.versions.toml. */
    static Path catalogFile(Path dir, String settings) {
        if (settings != null) {
            Matcher m = Pattern.compile("(?s)versionCatalogs\\s*\\{.*?(?:create|register)\\s*\\(\\s*[\"']libs[\"']\\s*\\)\\s*\\{[^}]*?from\\s*\\(\\s*files\\s*\\(\\s*[\"']([^\"']+)[\"']")
                    .matcher(stripComments(settings));
            if (m.find()) return dir.resolve(m.group(1)).normalize();
        }
        return dir.resolve("gradle").resolve("libs.versions.toml");
    }

    /** Typesafe project accessors ({@code projects.fidorialApi}, {@code projects.libs.coreUtil}) to project paths. */
    static Map<String, String> accessors(List<GProject> projects) {
        Map<String, String> out = new LinkedHashMap<>();
        for (GProject p : projects) {
            if (p.path.equals(":")) continue;
            StringBuilder a = new StringBuilder();
            for (String seg : p.path.substring(1).split(":")) {
                if (a.length() > 0) a.append('.');
                a.append(camel(seg));
            }
            out.put(a.toString(), p.path);
        }
        return out;
    }

    private static String camel(String name) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : name.toCharArray()) {
            if (c == '-' || c == '_' || c == '.' || c == ' ') {
                up = sb.length() > 0;
                continue;
            }
            sb.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return sb.toString();
    }

    /**
     * Repeats the bodies of {@code for (x in listOf("a", "b")) { ... }} (Groovy: {@code for (x in ['a', 'b'])}) once
     * per value, with {@code x} replaced by the quoted value: dependencies declared in loops.
     */
    static String expandLoops(String block) {
        Pattern loop = Pattern.compile("\\bfor\\s*\\(\\s*(?:val\\s+|def\\s+)?(\\w+)\\s+in\\s+(?:listOf|setOf|arrayOf)?\\s*[(\\[]([^)\\]]*)[)\\]]\\s*\\)\\s*\\{");
        for (int guard = 0; guard < 50; guard++) {
            Matcher m = loop.matcher(block);
            if (!m.find()) return block;
            int end = closing(block, m.end() - 1, '{', '}');
            String body = block.substring(m.end(), Math.min(end, block.length()));
            StringBuilder rep = new StringBuilder();
            Matcher v = QUOTED.matcher(m.group(2));
            while (v.find()) rep.append(body.replaceAll("\\b" + Pattern.quote(m.group(1)) + "\\b", Matcher.quoteReplacement("\"" + v.group(1) + "\""))).append('\n');
            block = block.substring(0, m.start()) + rep + block.substring(Math.min(block.length(), end + 1));
        }
        return block;
    }

    private static final Pattern CONF_CALL = Pattern.compile(
            "(?<![\"'\\w.$])(api|implementation|compileOnlyApi|compileOnly|runtimeOnly|annotationProcessor|testImplementation|"
                    + "testCompileOnly|testRuntimeOnly|testAnnotationProcessor)\\b(?:\\s*(\\()|[ \\t]+(?=[\"'\\w])(?![\\w.]*\\s*[=(]))");

    private static void readDependencies(String build, GProject p, Catalog catalog, Map<String, String> accessors, Imported imp) {
        Matcher blocks = Pattern.compile("dependencies\\s*\\{").matcher(build);
        while (blocks.find()) {
            int start = blocks.end(), end = closing(build, blocks.end() - 1, '{', '}');
            String before = build.substring(Math.max(0, blocks.start() - 200), blocks.start());
            if (before.matches("(?s).*buildscript\\s*\\{[^}]*$")) continue;
            String block = expandLoops(build.substring(start, Math.max(start, end)));
            Matcher m = CONF_CALL.matcher(block);
            int from = 0;
            while (m.find(from)) {
                String conf = m.group(1), arg;
                int next;
                if (m.group(2) != null) {
                    int close = closing(block, m.end() - 1, '(', ')');
                    arg = block.substring(m.end(), Math.min(close, block.length())).strip();
                    next = Math.min(block.length(), close + 1);
                } else {
                    int eol = block.indexOf('\n', m.end());
                    eol = eol < 0 ? block.length() : eol;
                    arg = block.substring(m.end(), eol).strip();
                    next = eol;
                }
                List<String> excludes = new ArrayList<>();
                Matcher cfg = Pattern.compile("\\G\\s*\\{").matcher(block);
                if (cfg.find(next)) {
                    int close = closing(block, cfg.end() - 1, '{', '}');
                    String body = block.substring(cfg.end(), Math.min(close, block.length()));
                    Matcher ex = Pattern.compile("exclude\\s*\\(?\\s*(?:group\\s*[:=]\\s*[\"']([^\"']+)[\"'])?\\s*,?\\s*(?:module\\s*[:=]\\s*[\"']([^\"']+)[\"'])?").matcher(body);
                    while (ex.find()) {
                        if (ex.group(1) == null && ex.group(2) == null) continue;
                        excludes.add((ex.group(1) != null ? ex.group(1) : "*") + ":" + (ex.group(2) != null ? ex.group(2) : "*"));
                    }
                    next = Math.min(block.length(), close + 1);
                }
                from = next;
                readDependency(conf, arg, excludes, p, catalog, accessors, imp);
            }
        }
    }

    private static void readDependency(String conf, String arg, List<String> excludes, GProject p, Catalog catalog,
                                       Map<String, String> accessors, Imported imp) {
        List<GDep> list = p.deps.computeIfAbsent(conf, k -> new ArrayList<>());
        String classifier = null;
        Matcher variant = Pattern.compile("^variantOf\\s*\\(\\s*(libs\\.[\\w.]+)\\s*\\)\\s*\\{[^}]*?classifier\\s*\\(?\\s*(?:[\"']([^\"']+)[\"']|(\\w+))").matcher(arg);
        if (variant.find()) {
            if (variant.group(2) == null) {
                imp.warn(p.dir, "could not read the classifier of `" + conf + "(" + arg + ")`");
                return;
            }
            classifier = variant.group(2);
            arg = variant.group(1);
        }
        if (arg.startsWith("platform(") || arg.startsWith("enforcedPlatform(")) {
            String inner = arg.substring(arg.indexOf('(') + 1).strip();
            String[] c = null;
            Matcher lib = Pattern.compile("^(libs\\.[\\w.]+)").matcher(inner);
            Matcher q = QUOTED.matcher(inner);
            if (lib.find()) c = catalog.library(lib.group(1).substring(5));
            else if (q.find()) c = q.group(1).split(":").length >= 3 ? q.group(1).split(":") : null;
            if (c == null) list.add(new GDep(null, null, null, null, "platform", arg));
            else list.add(new GDep(null, c[0], c[1], c[2], "platform", arg));
            return;
        }
        Matcher proj = Pattern.compile("^project\\s*\\(\\s*(?:path\\s*[:=]\\s*)?[\"']([^\"']+)[\"']").matcher(arg);
        if (proj.find()) {
            list.add(new GDep(proj.group(1), null, null, null, null, arg, null, excludes));
            return;
        }
        Matcher acc = Pattern.compile("^projects\\.([\\w.]+)$").matcher(arg);
        if (acc.find()) {
            String path = accessors.get(acc.group(1));
            if (path == null) imp.warn(p.dir, "no project for the accessor " + arg);
            else list.add(new GDep(path, null, null, null, null, arg, null, excludes));
            return;
        }
        Matcher lib = Pattern.compile("^(libs\\.[\\w.]+)$").matcher(arg);
        if (lib.find()) {
            String accessor = lib.group(1).substring(5);
            if (accessor.startsWith("bundles.")) {
                List<String[]> bundle = catalog.bundle(accessor.substring(8));
                if (bundle == null) imp.warn(p.dir, "version catalog bundle " + accessor + " not found");
                else for (String[] c : bundle) list.add(new GDep(null, c[0], c[1], c[2], null, arg, null, excludes));
            } else {
                String[] c = catalog.library(accessor);
                if (c == null) imp.warn(p.dir, "version catalog entry libs." + accessor + " not found");
                else list.add(new GDep(null, c[0], c[1], c[2], null, arg, classifier, excludes));
            }
            return;
        }
        Matcher q = QUOTED.matcher(arg);
        if (q.find() && q.start() <= 1) {
            String[] parts = q.group(1).split(":");
            if (parts.length >= 2) {
                list.add(new GDep(null, parts[0], parts[1], parts.length >= 3 ? parts[2] : null, null, arg,
                        parts.length >= 4 ? parts[3] : null, excludes));
                return;
            }
        }
        Matcher map = Pattern.compile("group\\s*[:=]\\s*[\"']([^\"']+)[\"']\\s*,\\s*name\\s*[:=]\\s*[\"']([^\"']+)[\"']"
                + "(?:\\s*,\\s*version\\s*[:=]\\s*[\"']([^\"']+)[\"'])?").matcher(arg);
        if (map.find()) {
            list.add(new GDep(null, map.group(1), map.group(2), map.group(3), null, arg, null, excludes));
            return;
        }
        imp.warn(p.dir, "could not read dependency `" + conf + "(" + arg + ")`");
    }

    private static void readRepositories(String build, GProject p) {
        Matcher m = Pattern.compile("maven\\s*(?:\\{[^}]*?url\\s*[=(]?\\s*(?:uri\\s*\\()?|\\(\\s*(?:url\\s*=\\s*)?(?:uri\\s*\\()?)[\"']([^\"']+)[\"']").matcher(build);
        while (m.find()) p.repositories.add(m.group(1));
        if (build.contains("google()")) p.repositories.add("https://dl.google.com/dl/android/maven2/");
    }

    /** A minimal reader of gradle/libs.versions.toml. */
    static final class Catalog {
        final Map<String, String> versions = new LinkedHashMap<>();
        /** normalized alias to {group, name, version} */
        final Map<String, String[]> libraries = new LinkedHashMap<>();
        final Map<String, List<String>> bundles = new LinkedHashMap<>();

        static String norm(String alias) {
            return alias.replaceAll("[-_.]", ".").toLowerCase();
        }

        static Catalog read(Path f) {
            Catalog c = new Catalog();
            if (!Files.isRegularFile(f)) return c;
            return parse(Fs.read(f));
        }

        static Catalog parse(String text) {
            Catalog c = new Catalog();
            String section = "";
            List<String> lines = new ArrayList<>();
            StringBuilder pending = null;
            int open = 0;
            for (String raw : text.split("\r?\n")) {
                String l = raw.replaceAll("\\s+#.*$", "").strip();
                if (pending != null) {
                    pending.append(' ').append(l);
                } else {
                    pending = new StringBuilder(l);
                }
                for (char ch : l.toCharArray()) {
                    if (ch == '[' || ch == '{') open++;
                    else if (ch == ']' || ch == '}') open--;
                }
                if (open <= 0 || l.startsWith("[") && l.endsWith("]") && !l.contains("=")) {
                    lines.add(pending.toString());
                    pending = null;
                    open = 0;
                }
            }
            if (pending != null) lines.add(pending.toString());
            for (String line : lines) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    section = line.replaceAll("[\\[\\]\\s]", "");
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).strip().replace("\"", "");
                String value = line.substring(eq + 1).strip();
                switch (section) {
                    case "versions" -> {
                        if (value.startsWith("\"")) c.versions.put(key, unquote(value));
                        else {
                            String strict = field(value, "strictly");
                            String req = field(value, "require");
                            String pref = field(value, "prefer");
                            c.versions.put(key, strict != null ? strict : req != null ? req : pref);
                        }
                    }
                    case "libraries" -> {
                        String group, name, version = null;
                        if (value.startsWith("\"")) {
                            String[] parts = unquote(value).split(":");
                            if (parts.length < 2) continue;
                            group = parts[0];
                            name = parts[1];
                            if (parts.length > 2) version = parts[2];
                        } else {
                            String module = field(value, "module");
                            if (module != null && module.contains(":")) {
                                group = module.substring(0, module.indexOf(':'));
                                name = module.substring(module.indexOf(':') + 1);
                            } else {
                                group = field(value, "group");
                                name = field(value, "name");
                            }
                            String ref = field(value, "version.ref");
                            if (ref == null) {
                                Matcher vr = Pattern.compile("version\\s*=\\s*\\{[^}]*ref\\s*=\\s*\"([^\"]+)\"").matcher(value);
                                if (vr.find()) ref = vr.group(1);
                            }
                            version = ref != null ? c.versions.get(ref) : field(value, "version");
                        }
                        if (group != null && name != null) c.libraries.put(norm(key), new String[]{group, name, version});
                    }
                    case "bundles" -> {
                        List<String> members = new ArrayList<>();
                        Matcher q = Pattern.compile("\"([^\"]+)\"").matcher(value);
                        while (q.find()) members.add(norm(q.group(1)));
                        c.bundles.put(norm(key), members);
                    }
                    default -> {
                    }
                }
            }
            return c;
        }

        private static String unquote(String v) {
            v = v.strip();
            return v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2 ? v.substring(1, v.length() - 1) : v;
        }

        private static String field(String table, String name) {
            Matcher m = Pattern.compile("(?:^|[{,\\s])" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"").matcher(table);
            return m.find() ? m.group(1) : null;
        }

        String[] library(String accessor) {
            return libraries.get(norm(accessor));
        }

        List<String[]> bundle(String accessor) {
            List<String> members = bundles.get(norm(accessor));
            if (members == null) return null;
            List<String[]> out = new ArrayList<>();
            for (String m : members) {
                String[] lib = libraries.get(m);
                if (lib != null) out.add(lib);
            }
            return out;
        }
    }

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
                        if (kv == null && "org.jetbrains.kotlin".equals(d.group) && "kotlin-stdlib".equals(d.name)) kv = d.version;
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
                    if ("platform".equals(d.category) || "enforced-platform".equals(d.category)) {
                        imp.warn(where, "platform " + (d.group != null ? d.group + ":" + d.name : d.raw)
                                + " is not carried over; the versions it manages are written out instead");
                        continue;
                    }
                    if ("org.junit.platform".equals(d.group) && "junit-platform-launcher".equals(d.name)) continue;
                    if (d.project != null) {
                        String name = byPath.get(d.project.startsWith(":") ? d.project : ":" + d.project);
                        if (name == null) {
                            imp.warn(where, "dependency on unknown project " + d.project + " skipped");
                        } else {
                            target.put("workspace:" + name, d.excludes == null || d.excludes.isEmpty() ? "*"
                                    : MavenImport.depValue("*", d.excludes, false, false));
                        }
                        continue;
                    }
                    String key = d.group + ":" + d.name;
                    String version = d.version;
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
                            imp.warn(where, "no version for " + key + (d.version != null ? " (" + d.version + ")" : "") + "; skipped");
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

    private static List<String> repositories(List<GProject> projects) {
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
