package dev.jpm.check;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.api.json.Jsonc;
import dev.jpm.build.Builder;
import dev.jpm.check.impl.ClassFiles;
import dev.jpm.license.Licenses;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.ReleaseAge;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * jpm's own checks of a project, each returning what it found. They never throw for what they check.
 */
public final class ProjectChecks {
    private ProjectChecks() {
    }

    /**
     * Every field that names a file or folder names one that exists: sources, resources, patches, script and hook files;
     * and every {@code filter} glob matches a resource.
     */
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
            filters(out, m);
            files(out, m, mf.scripts, "scripts", "script");
            files(out, m, mf.hooks, "hooks", "hook");
        }
        return out;
    }

    private static void filters(List<Problem> out, Project.Module m) {
        Manifest mf = m.manifest;
        if (mf.filter.isEmpty()) return;
        List<String> resources = new ArrayList<>();
        for (Path dir : m.assetsDirs()) {
            if (!Files.isDirectory(dir)) continue;
            try (var files = Files.walk(dir)) {
                files.filter(Files::isRegularFile).forEach(f -> resources.add(dir.relativize(f).toString().replace('\\', '/')));
            } catch (IOException e) {
                out.add(Problem.error("paths", m.name() + ": could not read " + dir + ": " + e.getMessage(), mf.file, null));
                return;
            }
        }
        for (String glob : mf.filter) {
            PathMatcher matcher;
            try {
                matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            } catch (PatternSyntaxException e) {
                out.add(Problem.error("paths", "the filter " + glob + " is not a glob: " + e.getDescription(), mf.file, line(mf, "filter")));
                continue;
            }
            if (resources.stream().noneMatch(r -> matcher.matches(Path.of(r)))) {
                out.add(Problem.warning("paths", "the filter " + glob + " matches no resource", mf.file, line(mf, "filter"))
                        .withHint("globs are relative to the resource folder, such as \"*.properties\" or \"config/**.yml\""));
            }
        }
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

    /**
     * Licenses: published modules (with a {@code "group"}) say which, every id is on the SPDX license list, and the
     * project's license file and {@code "license"} agree (or the one that is missing is suggested from the other).
     */
    public static List<Problem> license(Project p) {
        List<Problem> out = new ArrayList<>();
        for (Project.Module m : p.modules) {
            Manifest mf = m.manifest;
            String own = mf.license;
            if (own == null) {
                if (mf.group != null && !"private".equals(mf.visibility) && Licenses.file(p.dir) == null) {
                    out.add(Problem.warning("license", m.name() + " has a \"group\" but no \"license\"", mf.file, line(mf, "group"))
                            .withHint("set one with `jpm license <SPDX id>`, such as `jpm license MIT`, which also writes the license file"));
                }
                continue;
            }
            if (line(mf, "license") == null) continue;
            for (String id : Licenses.unknown(own)) {
                String suggestion = Licenses.suggest(id);
                out.add(Problem.warning("license", "\"" + id + "\" is not an SPDX license id", mf.file, line(mf, "license"))
                        .withHint(suggestion != null ? "did you mean \"" + suggestion + "\"?" : "use an id from https://spdx.org/licenses/, such as MIT or Apache-2.0"));
            }
        }
        if (p.root.isWorkspaceRoot() && p.root.license != null) {
            for (String id : Licenses.unknown(p.root.license)) {
                String suggestion = Licenses.suggest(id);
                out.add(Problem.warning("license", "\"" + id + "\" is not an SPDX license id", p.root.file, line(p.root, "license"))
                        .withHint(suggestion != null ? "did you mean \"" + suggestion + "\"?" : "use an id from https://spdx.org/licenses/, such as MIT or Apache-2.0"));
            }
        }
        String license = p.root.license != null ? p.root.license : p.modules.size() == 1 ? p.modules.get(0).manifest.license : null;
        Manifest owner = p.root.license != null || p.modules.size() != 1 ? p.root : p.modules.get(0).manifest;
        Path file = Licenses.file(p.dir);
        if (file != null) {
            List<String> detected = Licenses.detect(Fs.read(file));
            if (license == null) {
                out.add(Problem.warning("license", p.dir.relativize(file) + " is " + (detected.isEmpty() ? "there" : detected.get(0))
                        + ", but " + p.dir.relativize(owner.file) + " has no \"license\"", owner.file, null)
                        .withHint(detected.isEmpty() ? "add \"license\" with its SPDX id (https://spdx.org/licenses/)"
                                : "add \"license\": \"" + detected.get(0) + "\" (or run `jpm license " + detected.get(0) + "`, which keeps the file)"));
            } else if (!detected.isEmpty() && !Licenses.names(license, detected)) {
                out.add(Problem.warning("license", "\"license\" is " + license + ", but " + p.dir.relativize(file) + " is " + detected.get(0),
                        owner.file, line(owner, "license")).withHint("make them agree: the license file is what users read"));
            }
        } else if (license != null && Licenses.unknown(license).isEmpty() && Licenses.ids(license).size() == 1) {
            String id = Licenses.find(Licenses.ids(license).get(0)).id();
            out.add(Problem.warning("license", "the project is " + id + " but has no license file", owner.file, line(owner, "license"))
                    .withHint("`jpm license " + id + "` writes LICENSE from SPDX's text"));
        }
        return out;
    }

    /**
     * Every locked Maven version and Git tag, the extensions' included, is at least the minimum release age old, or
     * is the version {@code releaseAgeExceptions} names for it; exceptions that no longer do anything are warnings
     * (ADR 0018).
     */
    public static List<Problem> releaseAge(Project p, Graph g, MavenRepo repo) {
        List<Problem> out = new ArrayList<>();
        ReleaseAge age = ReleaseAge.of(p.root, repo);
        Manifest root = p.root;
        if (!age.active()) {
            for (String id : root.releaseAgeExceptions.keySet()) {
                out.add(Problem.warning("release-age", "the exception for " + id + " does nothing: there is no \"minimumReleaseAge\"", root.file,
                        line(root, "releaseAgeExceptions", id)).withHint("remove it, or set \"minimumReleaseAge\", such as \"7 days\""));
            }
            return out;
        }
        Map<String, String> locked = new LinkedHashMap<>();
        List<Graph> graphs = new ArrayList<>(List.of(g));
        graphs.addAll(g.isolated.values());
        graphs.addAll(g.extensions.values());
        for (Graph each : graphs) {
            for (Graph.Pkg pkg : each.pkgs.values()) {
                if (locked.putIfAbsent(pkg.id, pkg.version) != null) continue;
                String version = pkg.version;
                Instant released;
                if (pkg.kind == Graph.Kind.MAVEN) {
                    if (ReleaseAge.isSnapshot(version)) continue;
                    Optional<Instant> when;
                    try {
                        when = age.mavenReleased(pkg.id, version);
                    } catch (JpmException e) {
                        out.add(Problem.error("release-age", e.getMessage(), root.file, null));
                        continue;
                    }
                    if (when.isEmpty()) {
                        out.add(Problem.warning("release-age", "no repository says when " + pkg.id + " " + version + " was released", root.file, null)
                                .withHint("its age cannot be checked; the repository sends no Last-Modified time for the POM"));
                        continue;
                    }
                    released = when.get();
                } else if (pkg.kind == Graph.Kind.GIT && pkg.tag != null) {
                    released = age.tagDate(DepKey.parse(pkg.id), pkg.tag);
                    if (released == null) continue;
                } else {
                    continue;
                }
                if (age.oldEnough(released) || age.excepted(pkg.id, version)) {
                    if (age.excepted(pkg.id, version) && age.oldEnough(released)) {
                        out.add(Problem.warning("release-age", "the exception for " + pkg.id + " " + version + " is not needed: it was released "
                                + age.ago(released) + " ago", root.file, line(root, "releaseAgeExceptions", pkg.id)).withHint("remove it from"
                                        + " \"releaseAgeExceptions\""));
                    }
                    continue;
                }
                Problem.Severity sev = Problem.Severity.ERROR;
                Path file = root.file;
                Integer at = null;
                for (Project.Module m : p.modules) {
                    for (Manifest.Dependency d : m.manifest.dependencies) {
                        if (d.key().toString().equals(pkg.id) && at == null) {
                            file = m.manifest.file;
                            at = d.line();
                        }
                    }
                }
                out.add(new Problem(sev, pkg.id + " " + version + " was released " + age.ago(released) + " ago, less than the minimum release age ("
                        + ReleaseAge.describe(age.minimum) + ")", file, at, "take it now with \"releaseAgeExceptions\": {\"" + pkg.id + "\": \"" + version
                                + "\"} in " + p.dir.relativize(root.file) + ", or wait until it is " + ReleaseAge.describe(age.minimum) + " old", "release-age"));
            }
        }
        for (Map.Entry<String, String> e : root.releaseAgeExceptions.entrySet()) {
            String now = locked.get(e.getKey());
            if (e.getValue().equals(now)) continue;
            out.add(Problem.warning("release-age", "the exception for " + e.getKey() + " " + e.getValue() + " is not needed: "
                    + (now == null ? "it is not a dependency" : "the locked version is " + now), root.file, line(root, "releaseAgeExceptions", e.getKey()))
                    .withHint("remove it from \"releaseAgeExceptions\""));
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

    /**
     * Classes that two entries of the module's runtime classpath both have, so which one loads depends on the order.
     */
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

    /**
     * The failed tests in a JUnit XML report folder, each at its test class's source line when the stack trace names it.
     */
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

    /**
     * The 1-based line of a field in a manifest ({@code "patches", "<dep>"} for a member), or null when it has none.
     */
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
