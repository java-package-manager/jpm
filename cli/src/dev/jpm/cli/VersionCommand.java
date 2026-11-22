package dev.jpm.cli;

import java.nio.file.Path;
import java.util.List;

final class VersionCommand extends Command {
    VersionCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "version";
    }

    @Override
    List<Usage> usages() {
        return List.of(new Usage(Group.MAINTENANCE, "version", "Show jpm's version (also --version)",
                "Show jpm's version (also `--version`), and whether a newer one is out"));
    }

    @Override
    int run(List<String> args) {
        noMoreFlags(args);
        return Upgrade.version();
    }
}
