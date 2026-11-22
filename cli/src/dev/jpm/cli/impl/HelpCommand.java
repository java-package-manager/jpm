package dev.jpm.cli.impl;

import dev.jpm.log.Log;

import java.nio.file.Path;
import java.util.List;

final class HelpCommand extends Command {
    HelpCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "help";
    }

    @Override
    List<Usage> usages() {
        return List.of(new Usage(Group.MAINTENANCE, "help", "Show this list of commands (also -h, --help)"));
    }

    @Override
    int run(List<String> args) {
        Log.print(Commands.help());
        return 0;
    }
}
