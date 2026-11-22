package dev.jpm.cli.impl;

import dev.jpm.log.Log;

import java.nio.file.Path;
import java.util.List;

final class ApiCommand extends Command {
    ApiCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "api";
    }

    @Override
    List<Usage> usages() {
        return List.of(new Usage(Group.MAINTENANCE, "api", "Serve the jpm protocol on stdin and stdout, for IDEs and tools",
                "Serve the jpm protocol (JSON-RPC on stdin and stdout, see [docs/protocol.md](docs/protocol.md)) for IDEs, extensions"
                        + " and tools; the client in `github:java-package-manager/jpm/api` starts it"));
    }

    @Override
    int run(List<String> args) {
        noMoreFlags(args);
        if (!args.isEmpty()) throw new dev.jpm.util.JpmException("Usage: jpm api", 2);
        return new ApiServer(System.in, Log.claimStdout(), cwd).run();
    }
}
