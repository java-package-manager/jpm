package dev.jpm.cli.impl;

import dev.jpm.cli.Main;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Commands {
    private Commands() {}

    private static final int WIDTH = 108;
    private static final int COLUMN = 30;

    static List<Command> all(Path cwd) {
        return List.of(
                new InitCommand(cwd), new AddCommand(cwd), new RemoveCommand(cwd), new InstallCommand(cwd), new UpdateCommand(cwd),
                new OutdatedCommand(cwd), new AuditCommand(cwd), new SbomCommand(cwd), new WhyCommand(cwd), new InfoCommand(cwd), new ImportCommand(cwd), new LicenseCommand(cwd),
                new BuildCommand(cwd), new StartCommand(cwd, false), new StartCommand(cwd, true), new TestCommand(cwd), new CheckCommand(cwd),
                new RunCommand(cwd), new CleanCommand(cwd), new PatchCommand(cwd), new JavaCommand(cwd, false),
                new JavaCommand(cwd, true), new CacheCommand(cwd), new DoctorCommand(cwd), new UpgradeCommand(cwd), new VersionCommand(cwd), new HelpCommand(cwd),
                new CompletionsCommand(cwd), new ApiCommand(cwd));
    }

    public static Command find(Path cwd, String name) {
        for (Command c : all(cwd)) {
            if (c.name().equals(name) || c.aliases().contains(name)) return c;
        }
        return new ExtensionCommand(cwd, name);
    }

    public static int run(Path cwd, String name, List<String> args) {
        return find(cwd, name).run(args);
    }

    public static String help() {
        StringBuilder out = new StringBuilder();
        out.append("jpm ").append(Main.VERSION).append(" - one command for Java: dependencies, builds, running, tests and patches.\n\n");
        out.append("Usage: jpm <command> [options]\n");
        List<Command> all = all(Path.of(""));
        for (Command.Group g : Command.Group.values()) {
            out.append('\n').append(g.title).append('\n');
            for (Command c : all) {
                for (Command.Usage u : c.usages()) {
                    if (u.group() == g) out.append(line(u.synopsis(), u.summary()));
                }
            }
        }
        out.append("\nAny other command runs an extension: `jpm format` runs `jpm-format`.\n");
        out.append("Global options: -v/--verbose (-vv for more), -q/--quiet, --color=auto|always|never, --offline\n");
        return out.toString();
    }

    private static String line(String synopsis, String summary) {
        StringBuilder out = new StringBuilder("  ").append(synopsis);
        int col = 2 + synopsis.length();
        if (col >= COLUMN - 1) {
            out.append('\n');
            col = 0;
        }
        out.append(" ".repeat(COLUMN - col));
        int width = COLUMN;
        for (String word : summary.split(" ")) {
            if (width > COLUMN && width + 1 + word.length() > WIDTH) {
                out.append('\n').append(" ".repeat(COLUMN));
                width = COLUMN;
            } else if (width > COLUMN) {
                out.append(' ');
                width++;
            }
            out.append(word);
            width += word.length();
        }
        return out.append('\n').toString();
    }

    static String referenceTable() {
        List<String> rows = new ArrayList<>();
        for (Command.Group g : Command.Group.values()) {
            rows.add("## " + g.title);
            rows.add("");
            rows.add("| Command | |");
            rows.add("|---|---|");
            for (Command c : all(Path.of(""))) {
                for (Command.Usage u : c.usages()) {
                    if (u.group() == g) rows.add("| `" + (u.synopsis().startsWith("jvm ") ? "" : "jpm ") + u.synopsis() + "` | " + u.docs().replace("docs/protocol.md", "protocol.md") + " |");
                }
            }
            if (g == Command.Group.MAINTENANCE) {
                rows.add("| `jpm <anything else>` | Runs the extension `<name>`: the one the project declares, else `jpm-<name>` from `~/.jpm/bin` or `PATH` (see [Extensions](extensions.md)) |");
            }
            rows.add("");
        }
        return String.join("\n", rows).stripTrailing() + "\n";
    }
}
