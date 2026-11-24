package dev.jpm.importer.impl;

import dev.jpm.importer.Imported;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.jpm.importer.impl.MavenImport.childOf;
import static dev.jpm.importer.impl.MavenImport.elements;

final class Spotless {
    private Spotless() {
    }

    record Step(String name, List<String> args, Map<String, String> options) {
        String arg(int i) {
            return i < args.size() ? args.get(i) : null;
        }

        String option(String... names) {
            for (String n : names) if (options.get(n) != null) return options.get(n);
            return null;
        }
    }

    record Section(String language, List<String> includes, List<Step> steps) {}

    private static final Map<String, List<String>> DEFAULT_INCLUDES = Map.of(
            "kotlin", List.of("**/*.kt"),
            "kotlinGradle", List.of("**/*.gradle.kts"),
            "scala", List.of("**/*.scala"),
            "groovy", List.of("**/*.groovy"),
            "groovyGradle", List.of("**/*.gradle"),
            "json", List.of("**/*.json"),
            "yaml", List.of("**/*.yaml", "**/*.yml"),
            "sql", List.of("**/*.sql"));

    private static final List<String> LANGUAGES = List.of("java", "kotlin", "kotlinGradle", "scala", "groovy", "groovyGradle",
            "json", "yaml", "sql");

    private static final List<String> WHITESPACE = List.of("trimTrailingWhitespace", "endWithNewline", "toggleOffOn");

    static Map<String, Object> fromGradle(String build, Imported imp, String where) {
        List<Section> sections = new ArrayList<>();
        for (String body : GradleScripts.blockBodies(GradleScripts.stripComments(build), "spotless")) {
            for (String lang : LANGUAGES) {
                for (String b : GradleScripts.blockBodies(body, lang)) sections.add(gradleSection(lang, b));
            }
            Matcher f = Pattern.compile("\\bformat\\s*\\(?\\s*[\"']([^\"']+)[\"']\\s*\\)?\\s*,?\\s*\\{").matcher(body);
            while (f.find()) {
                int end = GradleScripts.closing(body, f.end() - 1, '{', '}');
                sections.add(gradleSection("format " + f.group(1), body.substring(f.end(), Math.max(f.end(), end))));
            }
        }
        return convert(sections, imp, where);
    }

    static Map<String, Object> fromMaven(Element configuration, UnaryOperator<String> interp, Imported imp, String where) {
        List<Section> sections = new ArrayList<>();
        for (String lang : LANGUAGES) {
            Element e = childOf(configuration, lang);
            if (e != null) sections.add(mavenSection(lang, e, interp));
        }
        Element formats = childOf(configuration, "formats");
        if (formats != null) for (Element e : elements(formats, "format")) sections.add(mavenSection("format", e, interp));
        return convert(sections, imp, where);
    }

    static void place(Imported imp, Map<String, Map<String, Object>> byDir) {
        boolean placed = false;
        Map<String, Object> root = byDir.get("");
        for (Map.Entry<String, Map<String, Object>> e : byDir.entrySet()) {
            Map<String, Object> cfg = e.getValue();
            Map<String, Object> man = imp.manifests.get(e.getKey());
            if (cfg == null || man == null) continue;
            if (!e.getKey().isEmpty()) {
                if (cfg.equals(root)) continue;
                if (cfg.remove("files") != null) {
                    imp.warn(e.getKey(), "Spotless's steps for files other than Java are not carried over from a module; jpm-format reads \"files\" from the root only");
                }
                if (cfg.isEmpty()) continue;
            }
            @SuppressWarnings("unchecked") Map<String, Object> ext = (Map<String, Object>) man.computeIfAbsent("extensions", k -> new LinkedHashMap<>());
            ext.put("format", cfg);
            placed = true;
        }
        if (placed) {
            imp.warn(null, "the Spotless settings are in \"extensions\": { \"format\": ... } for jpm-format; add it with `jpm add format`,"
                    + " then `jpm format` is spotlessApply and `jpm format --check` spotlessCheck");
        }
    }

    private static Section gradleSection(String lang, String body) {
        List<String> includes = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        for (String stmt : statements(body)) {
            List<Step> chain = calls(stmt);
            if (chain.isEmpty()) continue;
            Step head = chain.get(0);
            if (head.name.equals("target")) {
                includes.addAll(head.args);
                continue;
            }
            Map<String, String> options = new LinkedHashMap<>();
            for (Step c : chain.subList(1, chain.size())) options.put(c.name, c.args.isEmpty() ? "true" : c.args.get(0));
            steps.add(new Step(head.name, head.args, options));
        }
        return new Section(lang, includes, steps);
    }

    private static Section mavenSection(String lang, Element e, UnaryOperator<String> interp) {
        List<String> includes = new ArrayList<>();
        Element inc = childOf(e, "includes");
        if (inc != null) for (Element i : elements(inc, "include")) includes.add(path(interp.apply(i.getTextContent().trim())));
        List<Step> steps = new ArrayList<>();
        for (Element s : children(e)) {
            String name = s.getTagName();
            if (name.equals("includes") || name.equals("excludes")) continue;
            Map<String, String> options = new LinkedHashMap<>();
            for (Element o : children(s)) options.put(o.getTagName(), path(interp.apply(o.getTextContent().trim())));
            List<String> args = new ArrayList<>();
            String v = options.remove("version");
            if (v != null) args.add(v);
            steps.add(new Step(name, args, options));
        }
        return new Section(lang, includes, steps);
    }

    private static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        for (org.w3c.dom.Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element c) out.add(c);
        return out;
    }

    private static String path(String s) {
        return s == null ? null : s.replaceFirst("^\\$\\{(project\\.basedir|basedir|maven\\.multiModuleProjectDirectory)}/", "");
    }

    private static Map<String, Object> convert(List<Section> sections, Imported imp, String where) {
        if (sections.isEmpty()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> files = new ArrayList<>();
        for (Section s : sections) {
            if (s.language.equals("java")) java(s, out, imp, where);
            else {
                Map<String, Object> entry = files(s, imp, where);
                if (entry != null) files.add(entry);
            }
        }
        if (!files.isEmpty()) out.put("files", files);
        return out.isEmpty() ? null : out;
    }

    private static void java(Section s, Map<String, Object> out, Imported imp, String where) {
        Map<String, Object> formatter = new LinkedHashMap<>();
        Map<String, Object> imports = new LinkedHashMap<>();
        boolean styled = false, removeUnused = false, sorted = false;
        for (Step st : s.steps) {
            switch (st.name) {
                case "palantirJavaFormat" -> {
                    styled = true;
                    formatter.put("style", versioned("palantir", st.arg(0)));
                    String style = st.option("style");
                    if (style != null && !style.equalsIgnoreCase("PALANTIR")) {
                        imp.warn(where, "Spotless's palantirJavaFormat style " + style + " is not carried over");
                    }
                }
                case "googleJavaFormat" -> {
                    styled = true;
                    formatter.put("style", versioned("google-java-format", st.arg(0)));
                    String style = st.option("style");
                    if (st.options.containsKey("aosp") || (style != null && style.equalsIgnoreCase("AOSP"))) {
                        imp.warn(where, "Spotless's googleJavaFormat AOSP style is not carried over; jpm-format uses google-java-format's default style");
                    }
                }
                case "eclipse" -> {
                    styled = true;
                    formatter.put("style", versioned("eclipse", st.arg(0)));
                    String file = st.option("configFile", "file");
                    if (file != null) formatter.put("eclipseConfig", file);
                }
                case "removeUnusedImports" -> removeUnused = true;
                case "importOrder" -> {
                    sorted = true;
                    List<String> order = st.args;
                    if (st.options.get("order") != null) order = List.of(st.options.get("order").split(",", -1));
                    if (st.options.get("file") != null) {
                        imp.warn(where, "Spotless's import order file " + st.options.get("file") + " is not carried over; imports are sorted in jpm-format's groups");
                    } else if (!order.isEmpty()) {
                        imports.put("groups", groups(order));
                    }
                }
                case "importOrderFile" -> {
                    sorted = true;
                    imp.warn(where, "Spotless's import order file " + st.arg(0) + " is not carried over; imports are sorted in jpm-format's groups");
                }
                case "licenseHeader" -> {
                    String content = st.option("content");
                    String file = st.option("file");
                    if (content == null && file == null) content = st.arg(0);
                    if (content != null) formatter.put("licenseHeader", content);
                    else formatter.put("licenseHeader", Map.of("file", file));
                }
                case "licenseHeaderFile" -> {
                    String file = st.arg(0);
                    if (file != null) formatter.put("licenseHeader", Map.of("file", file));
                }
                default -> {
                    if (!WHITESPACE.contains(st.name) && !st.name.equals("indent") && !st.name.startsWith("indentWith")
                            && !st.name.startsWith("leading")) {
                        imp.warn(where, "Spotless's java step " + st.name + " is not carried over");
                    }
                }
            }
        }
        if (!styled) formatter.put("enabled", false);
        if (!removeUnused) imports.put("removeUnused", false);
        if (!sorted) imports.put("sort", false);
        if (!formatter.isEmpty()) out.put("formatter", formatter);
        if (!imports.isEmpty()) out.put("imports", imports);
    }

    private static Map<String, Object> files(Section s, Imported imp, String where) {
        List<String> includes = s.includes.isEmpty() ? DEFAULT_INCLUDES.get(s.language) : s.includes;
        if (includes == null || includes.isEmpty()) {
            imp.warn(where, "Spotless's " + s.language + " has no target, so it is not carried over");
            return null;
        }
        List<Object> steps = new ArrayList<>();
        for (Step st : s.steps) {
            Object step = fileStep(s.language, st);
            if (step == null) imp.warn(where, "Spotless's " + s.language + " step " + st.name + " is not carried over");
            else steps.add(step);
        }
        if (steps.isEmpty()) return null;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("include", includes.stream().map(i -> i.startsWith("**/") || i.startsWith("/") ? i : i.contains("/") ? i : "**/" + i).toList());
        entry.put("steps", steps);
        return entry;
    }

    private static Object fileStep(String language, Step st) {
        return switch (st.name) {
            case "trimTrailingWhitespace", "endWithNewline" -> st.name;
            case "ktfmt", "ktlint" -> versioned(st.name, st.arg(0));
            case "scalafmt" -> {
                String config = st.option("configFile", "file");
                if (config == null) yield versioned("scalafmt", st.arg(0));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("step", "scalafmt");
                if (st.arg(0) != null) m.put("version", st.arg(0));
                m.put("config", config);
                yield m;
            }
            case "simple" -> {
                String indent = st.option("indentWithSpaces", "indentSpaces");
                yield indent == null ? "jsonSimple" : Map.of("step", "jsonSimple", "indent", number(indent));
            }
            case "jackson" -> language.equals("yaml") ? "jacksonYaml" : "jacksonJson";
            case "dbeaver" -> {
                String config = st.option("configFile", "file");
                yield config == null ? "dbeaverSql" : Map.of("step", "dbeaverSql", "config", config);
            }
            case "removeSemicolons" -> "removeSemicolons";
            case "indentWithSpaces", "leadingTabsToSpaces" -> indent("space", st.arg(0));
            case "indentWithTabs", "leadingSpacesToTabs" -> indent("tab", st.arg(0));
            case "indent" -> indent("true".equals(st.option("tabs")) ? "tab" : "space", st.option("spacesPerTab", "spaces"));
            default -> null;
        };
    }

    private static Map<String, Object> indent(String type, String width) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", "indent");
        m.put("type", type);
        if (width != null) m.put("width", number(width));
        return m;
    }

    private static Object number(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return s;
        }
    }

    private static String versioned(String name, String version) {
        return version == null || version.isBlank() || version.contains("$") ? name : name + "@" + version;
    }

    static List<Object> groups(List<String> order) {
        List<Object> out = new ArrayList<>();
        boolean statics = false;
        for (String raw : order) {
            List<String> globs = new ArrayList<>();
            for (String part : raw.trim().split("\\|", -1)) {
                String p = part.trim();
                boolean isStatic = p.startsWith("\\#") || p.startsWith("#");
                if (isStatic) {
                    statics = true;
                    p = p.substring(p.indexOf('#') + 1);
                }
                String glob = p.isEmpty() ? "*" : p.endsWith(".*") ? p : p + ".*";
                globs.add(isStatic ? "static " + glob : glob);
            }
            out.add(globs.size() == 1 ? globs.get(0) : globs);
        }
        if (!out.contains("*")) out.add("*");
        if (!statics) out.add("static *");
        return out;
    }

    static List<String> statements(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"' || c == '\'') {
                int end = stringEnd(body, i);
                cur.append(body, i, end);
                i = end - 1;
                continue;
            }
            if (c == '(' || c == '{' || c == '[') depth++;
            if (c == ')' || c == '}' || c == ']') depth--;
            if (depth <= 0 && (c == '\n' || c == ';')) {
                String next = nextNonBlank(body, i + 1);
                if (c == '\n' && next.startsWith(".")) continue;
                if (!cur.toString().isBlank()) out.add(cur.toString().trim());
                cur.setLength(0);
                depth = 0;
                continue;
            }
            cur.append(c);
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().trim());
        return out;
    }

    private static String nextNonBlank(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }

    private static int stringEnd(String s, int start) {
        if (s.startsWith("\"\"\"", start)) {
            int end = s.indexOf("\"\"\"", start + 3);
            return end < 0 ? s.length() : end + 3;
        }
        char q = s.charAt(start);
        int j = start + 1;
        while (j < s.length() && s.charAt(j) != q) j += s.charAt(j) == '\\' ? 2 : 1;
        return Math.min(s.length(), j + 1);
    }

    static List<Step> calls(String stmt) {
        List<Step> out = new ArrayList<>();
        int i = 0, n = stmt.length();
        while (i < n) {
            while (i < n && (Character.isWhitespace(stmt.charAt(i)) || stmt.charAt(i) == '.')) i++;
            int start = i;
            while (i < n && Character.isJavaIdentifierPart(stmt.charAt(i))) i++;
            if (i == start) break;
            String name = stmt.substring(start, i);
            while (i < n && stmt.charAt(i) == ' ') i++;
            List<String> args = new ArrayList<>();
            if (i < n && stmt.charAt(i) == '(') {
                int close = GradleScripts.closing(stmt, i, '(', ')');
                args = args(stmt.substring(i + 1, Math.min(close, n)));
                i = close + 1;
            } else if (out.isEmpty() && i < n && stmt.charAt(i) != '.' && stmt.charAt(i) != '{' && stmt.charAt(i) != '=') {
                int end = i;
                while (end < n && stmt.charAt(end) != '{') {
                    if (stmt.charAt(end) == '"' || stmt.charAt(end) == '\'') end = stringEnd(stmt, end);
                    else if (stmt.charAt(end) == '.' && end + 1 < n && Character.isJavaIdentifierStart(stmt.charAt(end + 1))
                            && end > i && (stmt.charAt(end - 1) == '\'' || stmt.charAt(end - 1) == '"' || stmt.charAt(end - 1) == ')')) break;
                    else end++;
                }
                args = args(stmt.substring(i, end));
                i = end;
            }
            out.add(new Step(name, args, Map.of()));
            if (i < n && stmt.charAt(i) == '{') break;
        }
        return out;
    }

    private static List<String> args(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ',';
            if (i < text.length() && (c == '"' || c == '\'')) {
                i = stringEnd(text, i) - 1;
                continue;
            }
            if (c == '(' || c == '[') depth++;
            if (c == ')' || c == ']') depth--;
            if (c == ',' && depth == 0) {
                String a = text.substring(start, i).trim();
                if (!a.isEmpty()) out.add(value(a));
                start = i + 1;
            }
        }
        return out;
    }

    private static String value(String a) {
        Matcher file = Pattern.compile("^(?:rootProject\\.|project\\.)?file\\s*\\(\\s*(.+)\\s*\\)$").matcher(a);
        if (file.matches()) a = file.group(1).trim();
        if (a.startsWith("\"\"\"") && a.endsWith("\"\"\"") && a.length() >= 6) return a.substring(3, a.length() - 3);
        if (a.length() >= 2 && (a.charAt(0) == '"' || a.charAt(0) == '\'') && a.charAt(a.length() - 1) == a.charAt(0)) {
            return a.substring(1, a.length() - 1).replace("\\n", "\n").replace("\\\\", "\\").replace("\\$", "$");
        }
        return a;
    }
}
