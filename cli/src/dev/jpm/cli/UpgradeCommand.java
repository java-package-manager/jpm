package dev.jpm.cli;

import java.nio.file.Path;
import java.util.List;

final class UpgradeCommand extends Command {
    UpgradeCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "upgrade";
    }

    @Override
    List<String> aliases() {
        return List.of("self-update");
    }

    @Override
    List<Usage> usages() {
        return List.of(new Usage(Group.MAINTENANCE, "upgrade [version]",
                "Upgrade jpm to the latest release (--check only reports, --canary installs the newest build of the main branch)",
                "Upgrade jpm itself to the latest release (`--check` only reports, `--canary` installs the newest build of the main branch)"));
    }

    @Override
    List<String> flags() {
        return List.of("--check", "--canary");
    }

    @Override
    int run(List<String> args) {
        return Upgrade.run(args);
    }
}
