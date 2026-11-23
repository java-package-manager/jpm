package dev.jpm.cli.impl;

import dev.jpm.Project;
import dev.jpm.log.Log;
import dev.jpm.util.Fs;

import java.nio.file.Path;
import java.util.List;

final class CleanCommand extends Command {

    CleanCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "clean";
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.BUILD, "clean", "Delete build output"));
    }

    @Override
    int run(List<String> args) {
        noMoreFlags(args);
        Project p = Project.find(cwd);
        Fs.deleteTree(p.dir.resolve(".cache").resolve("build"));
        for (Project.Module m : p.modules) Fs.deleteTree(m.dir.resolve("dist"));
        Log.step("Cleaned", "build output");
        return 0;
    }
}
