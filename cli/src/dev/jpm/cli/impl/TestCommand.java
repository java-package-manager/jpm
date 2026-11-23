package dev.jpm.cli.impl;

import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.build.Env;
import dev.jpm.build.Scripts;
import dev.jpm.build.TestRunner;
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
                                + " in a workspace, " + WorkspaceFilter.USAGE));
    }

    @Override
    List<String> flags() {
        return List.of("--frozen", "--module", "--tag", "--exclude-tag", "--filter");
    }

    @Override
    int run(List<String> args) {
        boolean frozen = flag(args, "--frozen");
        String module = option(args, "--module");
        List<String> tags = new ArrayList<>();
        for (String t; (t = option(args, "--tag")) != null; ) tags.add(t);
        List<String> excludedTags = new ArrayList<>();
        for (String t; (t = option(args, "--exclude-tag")) != null; ) excludedTags.add(t);
        List<String> filters = WorkspaceFilter.options(args);
        noMoreFlags(args);
        TestRunner.Selection selection = new TestRunner.Selection(args, tags, excludedTags);
        Ctx c = open(frozen);
        Builder b = c.builder(Env.Mode.DEVELOPMENT);
        List<Project.Module> mods = targets(c.project(), module != null ? List.of(module) : List.of(), filters);
        int worst = 0;
        boolean matched = false;
        for (Project.Module m : mods) {
            Builder mb = b.forModule(m);
            Scripts.hooks(mb, m, m.manifest, "pre", "test", null, Env.Mode.DEVELOPMENT);
            int code = TestRunner.run(mb, m, selection);
            if (code == TestRunner.NO_MATCH) continue;
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
