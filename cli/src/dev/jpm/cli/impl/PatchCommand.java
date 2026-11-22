package dev.jpm.cli.impl;

import dev.jpm.Project;
import dev.jpm.build.Patches;
import dev.jpm.util.JpmException;

import java.nio.file.Path;
import java.util.List;

final class PatchCommand extends Command {

    PatchCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "patch";
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.DEPENDENCIES, "patch <dep>", "Unpack a dependency's source for editing"),
                new Usage(Group.DEPENDENCIES, "patch --commit <dep>", "Save your edits to patches/ and record them in the manifest",
                        "Save your edits to `patches/` and record them in the manifest"));
    }

    @Override
    List<String> flags() {
        return List.of("--commit");
    }

    @Override
    int run(List<String> args) {
        boolean commit = flag(args, "--commit");
        noMoreFlags(args);
        if (args.size() != 1) throw new JpmException("Usage: jpm patch <dependency>  |  jpm patch --commit <dependency>");
        if (commit) {
            Patches.commit(Project.find(cwd), args.get(0));
            return 0;
        }
        Ctx c = open(false);
        Patches.start(c.project(), c.graph(), c.repo(), args.get(0));
        return 0;
    }
}
