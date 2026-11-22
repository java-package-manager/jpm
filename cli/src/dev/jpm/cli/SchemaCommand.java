package dev.jpm.cli;

import java.nio.file.Path;
import java.util.List;

final class SchemaCommand extends Command {

    SchemaCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "schema";
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.PROJECT, "schema [--path]", "Print the JSON Schema of project.jsonc (--path writes it to ~/.jpm and prints where, for editors)",
                        "Print the JSON Schema of `project.jsonc` built into this jpm; `--path` writes it to `~/.jpm/schemas/<version>/project.schema.json` and prints the path instead (used by the IntelliJ plugin), see [Editor support](#editor-support)"));
    }

    @Override
    List<String> flags() {
        return List.of("--path");
    }

    @Override
    int run(List<String> args) {
        return Schema.run(args);
    }
}
