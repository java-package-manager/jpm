package dev.jpm.importer.impl;

import dev.jpm.importer.Imported;
import dev.jpm.importer.impl.GradleImport.GDep;
import dev.jpm.importer.impl.GradleImport.GProject;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GradleScripts {
    private GradleScripts() {
    }

    static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");

    public static String withoutBlocks(String text, String... names) {
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
        GradleConvert.convert(projects, imp);
        return imp;
    }

    static void applyPlatforms(List<GProject> projects, dev.jpm.maven.MavenRepo repo, Imported imp) {
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

    static String[] platformCoords(GDep d) {
        if (d.group() != null && d.version() != null) return new String[]{d.group(), d.name(), d.version()};
        return null;
    }

    static String firstExisting(Path dir, String... names) {
        for (String n : names) {
            Path f = dir.resolve(n);
            if (Files.isRegularFile(f)) return Fs.read(f);
        }
        return null;
    }

    static Map<String, String> gradleProperties(Path f) {
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

    static String assignment(String build, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*(?:project\\.)?" + key + "\\s*=\\s*[\"']([^\"']+)[\"']").matcher(build);
        return m.find() ? m.group(1) : null;
    }

    public static Integer javaVersion(String build) {
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

    public static String mainClass(String build) {
        Matcher m = Pattern.compile("mainClass(?:Name)?(?:\\.set)?\\s*[=(]\\s*[\"']([\\w.$]+)[\"']").matcher(build);
        return m.find() ? m.group(1) : null;
    }

    public static String stripComments(String text) {
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

    static int closing(String text, int open, char o, char c) {
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

    static Path catalogFile(Path dir, String settings) {
        if (settings != null) {
            Matcher m = Pattern.compile("(?s)versionCatalogs\\s*\\{.*?(?:create|register)\\s*\\(\\s*[\"']libs[\"']\\s*\\)\\s*\\{[^}]*?from\\s*\\(\\s*files\\s*\\(\\s*[\"']([^\"']+)[\"']")
                    .matcher(stripComments(settings));
            if (m.find()) return dir.resolve(m.group(1)).normalize();
        }
        return dir.resolve("gradle").resolve("libs.versions.toml");
    }

    static Map<String, String> accessors(List<GProject> projects) {
        Map<String, String> out = new LinkedHashMap<>();
        for (GProject p : projects) {
            if (p.path.equals(":")) continue;
            StringBuilder a = new StringBuilder();
            for (String seg : p.path.substring(1).split(":")) {
                if (!a.isEmpty()) a.append('.');
                a.append(camel(seg));
            }
            out.put(a.toString(), p.path);
        }
        return out;
    }

    static String camel(String name) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : name.toCharArray()) {
            if (c == '-' || c == '_' || c == '.' || c == ' ') {
                up = !sb.isEmpty();
                continue;
            }
            sb.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return sb.toString();
    }

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

    static final Pattern CONF_CALL = Pattern.compile(
            "(?<![\"'\\w.$])(api|implementation|compileOnlyApi|compileOnly|runtimeOnly|annotationProcessor|testImplementation|"
                    + "testCompileOnly|testRuntimeOnly|testAnnotationProcessor)\\b(?:\\s*(\\()|[ \\t]+(?=[\"'\\w])(?![\\w.]*\\s*[=(]))");

    static void readDependencies(String build, GProject p, Catalog catalog, Map<String, String> accessors, Imported imp) {
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

    static void readDependency(String conf, String arg, List<String> excludes, GProject p, Catalog catalog,
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

    static void readRepositories(String build, GProject p) {
        Matcher m = Pattern.compile("maven\\s*(?:\\{[^}]*?url\\s*[=(]?\\s*(?:uri\\s*\\()?|\\(\\s*(?:url\\s*=\\s*)?(?:uri\\s*\\()?)[\"']([^\"']+)[\"']").matcher(build);
        while (m.find()) p.repositories.add(m.group(1));
        if (build.contains("google()")) p.repositories.add("https://dl.google.com/dl/android/maven2/");
    }

    static final class Catalog {
        final Map<String, String> versions = new LinkedHashMap<>();
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
}
