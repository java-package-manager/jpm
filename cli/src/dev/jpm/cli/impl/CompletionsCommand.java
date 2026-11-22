package dev.jpm.cli.impl;

import dev.jpm.log.Log;
import dev.jpm.util.JpmException;

import java.nio.file.Path;
import java.util.List;

final class CompletionsCommand extends Command {
    CompletionsCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "completions";
    }

    @Override
    List<Usage> usages() {
        return List.of(new Usage(Group.MAINTENANCE, "completions <shell>", "Print shell completions for bash, zsh, fish or powershell",
                "Print shell completions: `jpm completions bash > ~/.local/share/bash-completion/completions/jpm`, and the same for"
                        + " `zsh`, `fish` and `powershell`"));
    }

    @Override
    List<String> flags() {
        return Completions.SHELLS;
    }

    @Override
    int run(List<String> args) {
        if (args.size() != 1 || !Completions.SHELLS.contains(args.get(0))) {
            throw new JpmException("Usage: jpm completions <shell>", 2).withHint("the shells are " + String.join(", ", Completions.SHELLS));
        }
        Log.print(Completions.script(args.get(0), Commands.all(cwd)));
        return 0;
    }
}
