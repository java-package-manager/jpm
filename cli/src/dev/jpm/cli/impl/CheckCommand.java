package dev.jpm.cli.impl;

import dev.jpm.check.CheckResult;
import dev.jpm.check.Reports;
import dev.jpm.cli.Main;
import dev.jpm.log.Log;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class CheckCommand extends Command {
    CheckCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "check";
    }

    @Override
    List<Usage> usages() {
        return List.of(new Usage(Group.PROJECT, "check [check...]", "Run every check, then the tests, and report all problems (for CI)",
                "Run jpm's checks (manifest, dependencies, paths, license, compile, unused-dependencies, duplicate-classes), the checks"
                        + " of the extensions the project declares, then the tests, and report every problem; `--no-test`, `--fail-on-warning`,"
                        + " `--exclude-tag <tag>`, `--sarif <file>`, `--junit <file>`, `--frozen`"));
    }

    @Override
    List<String> flags() {
        return List.of("--no-test", "--fail-on-warning", "--exclude-tag", "--sarif", "--junit", "--frozen");
    }

    @Override
    int run(List<String> args) {
        boolean noTest = flag(args, "--no-test");
        boolean failOnWarning = flag(args, "--fail-on-warning");
        boolean frozen = flag(args, "--frozen");
        String sarif = option(args, "--sarif");
        String junit = option(args, "--junit");
        List<String> excluded = new ArrayList<>();
        for (String t; (t = option(args, "--exclude-tag")) != null; ) excluded.add(t);
        noMoreFlags(args);
        Checker c = new Checker(cwd, new Checker.Options(!noTest, new LinkedHashSet<>(args), excluded, frozen));
        List<CheckResult> results = c.run();
        Path root = c.root() != null ? c.root() : cwd;
        if (sarif != null) Fs.write(cwd.resolve(sarif), Reports.sarif(results, root, Main.VERSION));
        if (junit != null) Fs.write(cwd.resolve(junit), Reports.junit(results, root));
        int errors = Checker.errors(results), warnings = Checker.warnings(results);
        long ran = results.stream().filter(r -> r.skipped() == null).count();
        long skipped = results.size() - ran;
        String summary = ran + (ran == 1 ? " check" : " checks") + (skipped == 0 ? "" : ", " + skipped + " skipped") + ": "
                + errors + (errors == 1 ? " error" : " errors") + ", " + warnings + (warnings == 1 ? " warning" : " warnings");
        if (errors > 0 || (failOnWarning && warnings > 0)) {
            throw new JpmException("jpm check found problems (" + summary + ")", 1);
        }
        Log.step("Checked", summary);
        return 0;
    }
}
