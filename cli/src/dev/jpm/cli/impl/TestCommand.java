package dev.jpm.cli.impl;

import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.build.Env;
import dev.jpm.build.Scripts;
import dev.jpm.build.TestRunner;
import dev.jpm.log.Log;
import dev.jpm.util.JpmException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class TestCommand extends Command {

    TestCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "test";
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.BUILD, "test [filter...]", "Run tests on the JUnit Platform (--tag, --exclude-tag select by JUnit tag)",
                        "Run tests on the JUnit Platform: a class, `Class#method` or a pattern selects tests, `--tag` and `--exclude-tag` select by JUnit tag;"
                                + " `--reports <dir>` writes JUnit XML reports for CI (one folder per module in a workspace); in a workspace, "
                                + WorkspaceFilter.USAGE));
    }

    @Override
    List<String> flags() {
        return List.of("--frozen", "--module", "--tag", "--exclude-tag", "--filter", "--reports", "--watch", "--trace");
    }

    @Override
    int run(List<String> args) {
        boolean frozen = flag(args, "--frozen");
        boolean watch = flag(args, "--watch");
        boolean trace = flag(args, "--trace");
        String module = option(args, "--module");
        List<String> tags = new ArrayList<>();
        for (String t; (t = option(args, "--tag")) != null; ) tags.add(t);
        List<String> excludedTags = new ArrayList<>();
        for (String t; (t = option(args, "--exclude-tag")) != null; ) excludedTags.add(t);
        List<String> filters = WorkspaceFilter.options(args);
        String reports = option(args, "--reports");
        noMoreFlags(args);
        if (watch && frozen) throw new JpmException("--watch and --frozen do not go together: --frozen is for CI", 2);
        if (watch && trace) throw new JpmException("--watch and --trace do not go together: trace one run", 2);
        TestRunner.Selection selection = new TestRunner.Selection(args, tags, excludedTags, trace);
        if (!watch) return once(frozen, module, filters, reports, selection);
        String snapshot = null;
        while (true) {
            String now = Watch.snapshot(Project.find(cwd), true);
            if (!now.equals(snapshot)) {
                if (snapshot != null) Log.step("Rerunning", "files changed");
                snapshot = now;
                try {
                    int code = once(false, module, filters, reports, selection);
                    Log.info(Log.dim((code == 0 ? "tests passed" : "tests failed") + "; watching for changes (Ctrl+C to stop)"));
                } catch (JpmException e) {
                    dev.jpm.cli.Main.report(e);
                    Log.info(Log.dim("waiting for changes (Ctrl+C to stop)"));
                }
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                return 130;
            }
        }
    }

    private int once(boolean frozen, String module, List<String> filters, String reports, TestRunner.Selection selection) {
        List<String> args = selection.filters();
        Ctx c = open(frozen);
        Builder b = c.builder(Env.Mode.DEVELOPMENT);
        List<Project.Module> mods = targets(c.project(), module != null ? List.of(module) : List.of(), filters);
        int worst = 0;
        boolean matched = false;
        for (Project.Module m : mods) {
            Builder mb = b.forModule(m);
            Scripts.hooks(mb, m, m.manifest, "pre", "test", null, Env.Mode.DEVELOPMENT);
            Path dir = reports == null ? null : mods.size() == 1 ? cwd.resolve(reports) : cwd.resolve(reports).resolve(m.name());
            int code = TestRunner.run(mb, m, selection, dir);
            if (code == TestRunner.NO_MATCH) continue;
            if (selection.trace()) {
                Log.step("Traced", m.name() + ": what the tests use merged into " + m.dir.relativize(dev.jpm.build.Builder.nativeConfigDir(m))
                        + "; `jpm build --native` uses it");
            }
            matched = true;
            if (code != 0) worst = code;
            else Scripts.hooks(mb, m, m.manifest, "post", "test", null, Env.Mode.DEVELOPMENT);
        }
        if (!matched && !args.isEmpty()) {
            throw new JpmException("No test class " + String.join(", ", args) + " in " + (mods.size() == 1 ? mods.get(0).name()
                    : "any module")).withHint("name a class of the module's tests, such as com.example.FooTest, or a pattern such as 'com.example.*'");
        }
        return worst;
    }
}
