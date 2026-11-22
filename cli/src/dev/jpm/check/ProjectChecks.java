package dev.jpm.check;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.api.json.Jsonc;
import dev.jpm.build.Builder;
import dev.jpm.check.impl.ClassFiles;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** jpm's own checks of a project, each returning what it found. They never throw for what they check. */
public final class ProjectChecks {
    private ProjectChecks() {}

    /** Every field that names a file or folder names one that exists: sources, resources, patches, script and hook files. */
    public static List<Problem> paths(Project p) {
        List<Problem> out = new ArrayList<>();
        for (Project.Module m : p.modules) {
            Manifest mf = m.manifest;
            for (String s : mf.sources) {
                if (!Files.isDirectory(m.dir.resolve(s))) out.add(missing("paths", mf, "source folder " + s, "sources"));
            }
            for (String s : mf.resources) {
                if (!Files.isDirectory(m.dir.resolve(s))) out.add(missing("paths", mf, "resource folder " + s, "resources"));
            }
            for (Map.Entry<String, String> e : mf.patches.entrySet()) {
                if (!Files.isRegularFile(mf.dir().resolve(e.getValue()))) {
                    out.add(missing("paths", mf, "patch " + e.getValue() + " of " + e.getKey(), "patches", e.getKey()));
                }
            }
            files(out, m, mf.scripts, "scripts", "script");
            files(out, m, mf.hooks, "hooks", "hook");
        }
        return out;
    }

    private static void files(List<Problem> out, Project.Module m, Map<String, String> entries, String field, String what) {
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String v = e.getValue().strip();
            boolean file = !v.contains(" ") && (v.endsWith(".java") || v.endsWith(".sh"));
            if (file && !Files.isRegularFile(m.dir.resolve(v))) out.add(missing("paths", m.manifest, what + " file " + v + " (" + e.getKey() + ")", field, e.getKey()));
        }
    }

    private static Problem missing(String check, Manifest mf, String what, String... keys) {
        return Problem.error(check, "the " + what + " does not exist", mf.file, line(mf, keys));
    }

    /** Modules published with Maven coordinates (a {@code "group"}) say under which license. */
    public static List<Problem> license(Project p) {
        List<Problem> out = new ArrayList<>();
        for (Project.Module m : p.modules) {
            Manifest mf = m.manifest;
            if (mf.group == null || "private".equals(mf.visibility)) continue;
            if (mf.license == null) {
                out.add(Problem.warning("license", m.name() + " has a \"group\" but no \"license\"", mf.file, line(mf, "group"))
                        .withHint("add an SPDX license id, such as \"license\": \"MIT\" (in the workspace root for every module)"));
            } else if (!mf.license.matches("[A-Za-z0-9.+-]+( (AND|OR|WITH) [A-Za-z0-9.+-]+)*")) {
                out.add(Problem.warning("license", "\"" + mf.license + "\" is not an SPDX license id", mf.file, line(mf, "license"))
                        .withHint("use an id from https://spdx.org/licenses/, such as MIT or Apache-2.0"));
            }
        }
        return out;
    }

    /**
     * Declared dependencies that none of the module's compiled classes refer to: runtime and dev dependencies by its main
     * classes, test dependencies by its main and test classes. Jars without classes (aggregators such as
     * junit-jupiter), annotation processors and runtime-only dependencies are not reported; nor are constants, which
     * javac copies into the classes that use them.
     */
    public static List<Problem> unusedDependencies(Builder b, Project.Module m) {
        List<Problem> out = new ArrayList<>();
        try {
            Set<String> main = ClassFiles.referencedClasses(Builder.classesDir(m));
            Set<String> all = new HashSet<>(main);
            all.addAll(ClassFiles.referencedClasses(Builder.testClassesDir(m)));
            for (Manifest.Dependency d : m.manifest.dependencies) {
                if (d.runtimeOnly() || d.key().isGradle() || d.key().isExtension()) continue;
                String id = dev.jpm.resolve.ResolveInputs.idOf(d.key());
                List<Path> paths = d.key().isWorkspace() ? workspaceClasses(b, d) : b.paths(id, m);
                if (paths.isEmpty()) continue;
                Set<String> provided = new HashSet<>();
                boolean processor = false;
                for (Path jar : paths) {
                    provided.addAll(ClassFiles.classesIn(jar).keySet());
                    processor |= ClassFiles.hasEntry(jar, "META-INF/services/javax.annotation.processing.Processor");
                }
                if (provided.isEmpty() || processor) continue;
                Set<String> refs = d.scope() == Manifest.Scope.TEST ? all : main;
                if (provided.stream().noneMatch(refs::contains)) {
                    out.add(Problem.warning("unused-dependencies", m.name() + " does not use " + d.key() + " (" + d.scope().key + ")",
                            m.manifest.file, d.line()).withHint("remove it with `jpm remove " + d.key() + "`, or make it \"runtimeOnly\" if it is"
                            + " only loaded at run time"));
                }
            }
        } catch (IOException | JpmException e) {
            out.add(Problem.error("unused-dependencies", m.name() + ": could not read its classes: " + e.getMessage(), m.manifest.file, null));
        }
        return out;
    }

    private static List<Path> workspaceClasses(Builder b, Manifest.Dependency d) {
        Project.Module sib = b.project.module(d.key().artifact());
        return sib == null ? List.of() : List.of(Builder.classesDir(sib));
    }

    /** Classes that two entries of the module's runtime classpath both have, so which one loads depends on the order. */
    public static List<Problem> duplicateClasses(Builder b, Project.Module m) {
        List<Problem> out = new ArrayList<>();
        try {
            Map<String, Path> owner = new LinkedHashMap<>();
            Map<String, List<String>> clashes = new LinkedHashMap<>();
            for (Path entry : b.runtimeClasspath(m)) {
                for (String cls : ClassFiles.classesIn(entry).keySet()) {
                    Path first = owner.putIfAbsent(cls, entry);
                    if (first != null && !first.equals(entry)) {
                        clashes.computeIfAbsent(name(first) + " and " + name(entry), k -> new ArrayList<>()).add(cls.replace('/', '.'));
                    }
                }
            }
            for (Map.Entry<String, List<String>> e : clashes.entrySet()) {
                List<String> classes = e.getValue();
                out.add(Problem.warning("duplicate-classes", m.name() + ": " + e.getKey() + " both have " + classes.size()
                        + (classes.size() == 1 ? " class" : " classes") + " (" + classes.get(0) + (classes.size() > 1 ? ", ..." : "") + ")",
                        m.manifest.file, null).withHint("exclude one of them, or drop the dependency that brings the second copy"));
            }
        } catch (IOException | JpmException e) {
            out.add(Problem.error("duplicate-classes", m.name() + ": could not read its classpath: " + e.getMessage(), m.manifest.file, null));
        }
        return out;
    }

    private static String name(Path p) {
        return p.getFileName() == null ? p.toString() : p.getFileName().toString().equals("classes") ? p.toString() : p.getFileName().toString();
    }

    private static final Pattern TESTCASE = Pattern.compile("<testcase\\s+name=\"([^\"]*)\"\\s+classname=\"([^\"]*)\"[^>]*?(?:/>|>(.*?)</testcase>)",
            Pattern.DOTALL);
    private static final Pattern FAILURE = Pattern.compile("<(failure|error)(?:\\s+message=\"([^\"]*)\")?[^>]*>(.*?)</\\1>", Pattern.DOTALL);

    /** The failed tests in a JUnit XML report folder, each at its test class's source line when the stack trace names it. */
    public static List<Problem> testFailures(Path reports, Project.Module m) {
        List<Problem> out = new ArrayList<>();
        if (!Files.isDirectory(reports)) return out;
        try (var files = Files.list(reports)) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.getFileName().toString().endsWith(".xml"))::iterator) {
                Matcher tc = TESTCASE.matcher(dev.jpm.util.Fs.read(f));
                while (tc.find()) {
                    if (tc.group(3) == null) continue;
                    Matcher fail = FAILURE.matcher(tc.group(3));
                    if (!fail.find()) continue;
                    String cls = unescape(tc.group(2)), test = unescape(tc.group(1));
                    String msg = fail.group(2) == null ? fail.group(1) : unescape(fail.group(2));
                    Path file = null;
                    Integer line = null;
                    String simple = cls.substring(cls.lastIndexOf('.') + 1).replaceAll("\\$.*", "");
                    Matcher at = Pattern.compile("at " + Pattern.quote(cls) + "[.$][^(]*\\(" + Pattern.quote(simple) + "\\.(?:java|kt):(\\d+)\\)")
                            .matcher(fail.group(3));
                    for (Path dir : m.testDirs()) {
                        Path src = dir.resolve(cls.replaceAll("\\$.*", "").replace('.', '/') + ".java");
                        if (!Files.exists(src)) src = dir.resolve(cls.replaceAll("\\$.*", "").replace('.', '/') + ".kt");
                        if (Files.exists(src)) {
                            file = src;
                            if (at.find()) line = Integer.parseInt(at.group(1));
                            break;
                        }
                    }
                    out.add(Problem.error("tests", cls + " > " + test + ": " + msg, file, line));
                }
            }
        } catch (IOException e) {
            out.add(Problem.error("tests", m.name() + ": could not read the test reports: " + e.getMessage(), null, null));
        }
        return out;
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#10;", "\n").replace("&amp;", "&");
    }

    /** The 1-based line of a field in a manifest ({@code "patches", "<dep>"} for a member), or null when it has none. */
    static Integer line(Manifest mf, String... keys) {
        Jsonc.Node n = mf.root;
        for (String k : keys) {
            if (n == null || !n.isObject()) return null;
            Jsonc.Member member = n.members.get(k);
            if (member == null) return null;
            if (k.equals(keys[keys.length - 1])) return mf.line(member.keyStart);
            n = member.value;
        }
        return null;
    }
}
