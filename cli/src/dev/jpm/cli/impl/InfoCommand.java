package dev.jpm.cli.impl;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.log.Log;
import dev.jpm.resolve.Graph;
import dev.jpm.util.JpmException;

import java.nio.file.Path;
import java.util.List;

final class InfoCommand extends Command {

    InfoCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "info";
    }

    @Override
    List<Usage> usages() {
        return List.of(new Usage(Group.PROJECT, "info", "Describe the project: modules, versions and dependencies",
                "Describe the project: each module's version and folder, and its dependencies with what they resolve to. Programs"
                        + " use the jpm protocol instead (`jpm api`, [docs/protocol.md](docs/protocol.md))"));
    }

    @Override
    int run(List<String> args) {
        for (String a : List.copyOf(args)) {
            if (a.equals("--json") || a.equals("--ide") || a.equals("--classpath")) {
                throw new JpmException("jpm info " + a + " was removed in jpm 0.12", 2)
                        .withHint("programs read the project over the jpm protocol (`jpm api`) with the client dev.jpm:jpm-api; see docs/protocol.md");
            }
        }
        noMoreFlags(args);
        Ctx c = open(false);
        for (Project.Module m : c.project().buildOrder()) {
            Log.out(Log.bold(m.name() + " " + m.version()) + "  " + m.dir);
            for (Manifest.Dependency d : m.manifest.dependencies) {
                Graph.Pkg p = c.graph().get(dev.jpm.resolve.ResolveInputs.idOf(d.key()));
                String resolved = p != null && p.kind != Graph.Kind.LOCAL ? p.version : null;
                Log.out("  " + d.scope().key + "  " + d.key() + " " + d.spec() + (resolved != null ? Log.dim(" -> " + resolved) : ""));
            }
        }
        return 0;
    }
}
