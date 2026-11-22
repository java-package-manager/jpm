package dev.jpm.cli.impl;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.api.json.Jsonc;
import dev.jpm.build.Builder;
import dev.jpm.build.Env;
import dev.jpm.build.TestRunner;
import dev.jpm.check.CheckResult;
import dev.jpm.check.Problem;
import dev.jpm.check.ProjectChecks;
import dev.jpm.check.Reports;
import dev.jpm.log.Log;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.GitModule;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Install;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

final class Checker {
    record Options(boolean tests, Set<String> only, List<String> excludedTags, boolean frozen) {}

    static final List<String> OWN = List.of("manifest", "dependencies", "paths", "license", "compile", "unused-dependencies",
            "duplicate-classes");

    private final Path cwd;
    private final Options o;
    private final List<CheckResult> results = new ArrayList<>();
    private Path root;

    Checker(Path cwd, Options o) {
        this.cwd = cwd;
        this.o = o;
    }

    List<CheckResult> run() {
        Project p = step("manifest", "project.jsonc loads", () -> Project.find(cwd));
        if (p == null) return skipRest("the project does not load");
        root = p.dir;
        MavenRepo repo = new MavenRepo(p.root.repositories);
        Graph g = step("dependencies", "dependencies resolve", () -> Install.run(p, repo, new Install.Options(o.frozen(), false, Set.of(), true)));
        list("paths", "files and folders the manifests name exist", () -> ProjectChecks.paths(p));
        list("license", "published modules have a license", () -> ProjectChecks.license(p));
        if (g == null) return skipRest("the dependencies do not resolve");
        Builder b = new Builder(p, g, repo, Env.Mode.DEVELOPMENT);
        List<Project.Module> compiled = new ArrayList<>();
        list("compile", "the sources compile", () -> {
            List<Problem> out = new ArrayList<>();
            for (Project.Module m : p.buildOrder()) {
                try {
                    Builder mb = b.forModule(m);
                    mb.compileMain(m);
                    mb.compileTests(m);
                    compiled.add(m);
                } catch (JpmException e) {
                    out.add(problem("compile", e));
                }
            }
            return out;
        });
        boolean any = !compiled.isEmpty();
        list("unused-dependencies", "every declared dependency is used", any ? () -> {
            List<Problem> out = new ArrayList<>();
            for (Project.Module m : compiled) out.addAll(ProjectChecks.unusedDependencies(b.forModule(m), m));
            return out;
        } : null);
        list("duplicate-classes", "no class is on a runtime classpath twice", any ? () -> {
            List<Problem> out = new ArrayList<>();
            for (Project.Module m : compiled) out.addAll(ProjectChecks.duplicateClasses(b.forModule(m), m));
            return out;
        } : null);
        extensionChecks(p, g);
        if (o.tests()) {
            list("tests", "the tests pass", compiled.size() == p.modules.size() ? () -> tests(p, b) : null);
        }
        return results;
    }

    private void extensionChecks(Project p, Graph g) {
        for (Manifest.ExtensionDecl d : p.root.declaredExtensions) {
            Graph eg = g.extensions.get(d.name());
            Graph.Pkg pkg = eg == null ? null : eg.get(d.key().toString());
            Manifest.ExtensionDescriptor desc = pkg == null || pkg.kind != Graph.Kind.GIT || pkg.commit == null ? null
                    : GitModule.locate(d.key(), pkg.commit).manifest().extension;
            if (desc == null) continue;
            for (Manifest.ExtensionCheck c : desc.checks()) {
                String id = d.name() + ":" + c.id();
                String title = c.description() != null ? c.description() : "jpm " + d.name() + " " + String.join(" ", c.run());
                list(id, title, () -> extensionCheck(p, d, c, id));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Problem> extensionCheck(Project p, Manifest.ExtensionDecl d, Manifest.ExtensionCheck c, String id) {
        Path file = p.dir.resolve(".cache").resolve("check").resolve(id.replace(':', '-') + ".json");
        Fs.mkdirs(file.getParent());
        try {
            Files.deleteIfExists(file);
        } catch (java.io.IOException e) {
            throw new JpmException("Could not remove " + file + ": " + e.getMessage());
        }
        int exit = ExtensionCommand.launch(p.dir, p, d, c.run(), Map.of("JPM_CHECK_PROBLEMS", file.toString()));
        List<Problem> out = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            Object parsed;
            try {
                parsed = Jsonc.parse(Fs.read(file));
            } catch (Jsonc.ParseException e) {
                parsed = null;
                out.add(Problem.error(id, "jpm-" + d.name() + " wrote problems that are not JSON: " + e.getMessage(), file, null));
            }
            if (parsed instanceof List<?> list) {
                for (Object x : list) {
                    if (!(x instanceof Map<?, ?> m)) continue;
                    Map<String, Object> pm = (Map<String, Object>) m;
                    Problem.Severity sev = "warning".equals(pm.get("severity")) ? Problem.Severity.WARNING : Problem.Severity.ERROR;
                    Path f = pm.get("file") instanceof String s ? p.dir.resolve(s) : null;
                    Integer line = pm.get("line") instanceof Number n ? n.intValue() : null;
                    Problem pr = new Problem(sev, String.valueOf(pm.get("message")), f, line, pm.get("hint") instanceof String h ? h : null, id);
                    out.add(pr);
                }
            }
        }
        if (exit != 0 && out.stream().noneMatch(x -> x.severity() == Problem.Severity.ERROR)) {
            out.add(Problem.error(id, "`jpm " + d.name() + " " + String.join(" ", c.run()) + "` failed (exit " + exit + ")", null, null));
        }
        return out;
    }

    private List<Problem> tests(Project p, Builder b) {
        List<Problem> out = new ArrayList<>();
        for (Project.Module m : p.modules) {
            Path reports = p.dir.resolve(".cache").resolve("check").resolve("tests").resolve(m.name());
            Builder mb = b.forModule(m);
            int code = TestRunner.run(mb, m, new TestRunner.Selection(List.of(), List.of(), o.excludedTags()), reports);
            if (code == 0 || code == TestRunner.NO_MATCH) continue;
            List<Problem> failed = ProjectChecks.testFailures(reports, m);
            out.addAll(failed.isEmpty() ? List.of(Problem.error("tests", m.name() + ": the tests failed (exit " + code + ")", null, null)) : failed);
        }
        return out;
    }

    private <T> T step(String id, String title, Supplier<T> s) {
        if (skipped(id)) return run(s);
        Log.step("Checking", title + Log.dim(" (" + id + ")"));
        try {
            T value = s.get();
            add(new CheckResult(id, title, List.of(), null));
            return value;
        } catch (JpmException e) {
            add(new CheckResult(id, title, List.of(problem(id, e)), null));
            return null;
        }
    }

    private static <T> T run(Supplier<T> s) {
        try {
            return s.get();
        } catch (JpmException e) {
            return null;
        }
    }

    private void list(String id, String title, Supplier<List<Problem>> s) {
        if (skipped(id)) return;
        if (s == null) {
            add(new CheckResult(id, title, List.of(), "an earlier check failed"));
            return;
        }
        Log.step("Checking", title + Log.dim(" (" + id + ")"));
        List<Problem> problems;
        try {
            problems = s.get();
        } catch (JpmException e) {
            problems = List.of(problem(id, e));
        }
        add(new CheckResult(id, title, List.copyOf(problems), null));
    }

    private boolean skipped(String id) {
        return !o.only().isEmpty() && !o.only().contains(id);
    }

    private List<CheckResult> skipRest(String why) {
        Set<String> done = new LinkedHashSet<>();
        for (CheckResult r : results) done.add(r.id());
        List<String> rest = new ArrayList<>(OWN);
        if (o.tests()) rest.add("tests");
        for (String id : rest) if (!done.contains(id) && !skipped(id)) results.add(new CheckResult(id, id, List.of(), why));
        return results;
    }

    private void add(CheckResult r) {
        results.add(r);
        for (Problem p : r.problems()) {
            String text = Reports.lines(List.of(p), root).strip();
            String body = text.replaceFirst("(error|warning): ", "");
            if (p.severity() == Problem.Severity.ERROR) Log.error(body);
            else Log.warn(body);
            if (p.hint() != null) Log.info("  hint: " + p.hint());
        }
    }

    static Problem problem(String check, JpmException e) {
        Path file = null;
        Integer line = null;
        String l = e.location();
        if (l != null) {
            int colon = l.lastIndexOf(':');
            if (colon > 0 && l.substring(colon + 1).matches("\\d+")) {
                file = Path.of(l.substring(0, colon));
                line = Integer.parseInt(l.substring(colon + 1));
            } else {
                file = Path.of(l);
            }
        }
        Problem p = new Problem(Problem.Severity.ERROR, e.getMessage(), file, line, null, check);
        return e.hint() == null ? p : p.withHint(e.hint());
    }

    static int errors(List<CheckResult> rs) {
        return (int) rs.stream().flatMap(r -> r.problems().stream()).filter(p -> p.severity() == Problem.Severity.ERROR).count();
    }

    static int warnings(List<CheckResult> rs) {
        return (int) rs.stream().flatMap(r -> r.problems().stream()).filter(p -> p.severity() == Problem.Severity.WARNING).count();
    }

    Path root() {
        return root;
    }
}
