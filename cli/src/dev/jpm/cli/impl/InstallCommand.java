package dev.jpm.cli.impl;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.log.Log;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Install;
import dev.jpm.util.Fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

final class InstallCommand extends Command {

    InstallCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "install";
    }

    @Override
    List<String> aliases() {
        return List.of("i");
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.PROJECT, "install", "Resolve dependencies and write project.lock (--frozen in CI)",
                        "Resolve dependencies and declared extensions and write `project.lock`; `--frozen` fails if the lock is stale (CI)"));
    }

    @Override
    List<String> flags() {
        return List.of("--frozen", "--frozen-lockfile");
    }

    @Override
    int run(List<String> args) {
        boolean frozen = flag(args, "--frozen", "--frozen-lockfile");
        noMoreFlags(args);
        Project p = Project.find(cwd);
        String before = Files.exists(p.lockFile()) ? Fs.read(p.lockFile()) : null;
        Graph g = Install.run(p, new MavenRepo(p.root.repositories), new Install.Options(frozen, false, Set.of(), false));
        if (before != null && before.equals(Fs.read(p.lockFile()))) {
            long n = g.pkgs.values().stream().filter(x -> x.kind != Graph.Kind.LOCAL).count();
            int e = g.extensions.size();
            Log.step("Up to date", n + " package" + (n == 1 ? "" : "s") + (e == 0 ? "" : " and " + e + " extension" + (e == 1 ? "" : "s"))
                    + " in " + Manifest.LOCK);
        }
        return 0;
    }
}
