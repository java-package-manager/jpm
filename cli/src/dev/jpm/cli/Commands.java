package dev.jpm.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Every jpm command, in the order {@code jpm help} lists them. This list is the one source for
 * {@code jpm help}, the README's command table and shell completions.
 */
final class Commands {
    private Commands() {}

    /** Text width of {@code jpm help}. */
    private static final int WIDTH = 108;
    private static final int COLUMN = 30;

    /** The commands, for a jpm started in {@code cwd}. */
    static List<Command> all(Path cwd) {
        return List.of(
                new InitCommand(cwd), new AddCommand(cwd), new RemoveCommand(cwd), new InstallCommand(cwd), new UpdateCommand(cwd),
                new OutdatedCommand(cwd), new WhyCommand(cwd), new InfoCommand(cwd), new ImportCommand(cwd), new SchemaCommand(cwd),
                new BuildCommand(cwd), new StartCommand(cwd, false), new StartCommand(cwd, true), new TestCommand(cwd),
                new RunCommand(cwd), new CleanCommand(cwd), new PatchCommand(cwd), new JavaCommand(cwd, false),
                new JavaCommand(cwd, true), new UpgradeCommand(cwd), new VersionCommand(cwd), new HelpCommand(cwd),
                new CompletionsCommand(cwd));
    }

    /** The command with this name or alias; any other name runs the extension {@code jpm-<name>}. */
    static Command find(Path cwd, String name) {
        for (Command c : all(cwd)) {
            if (c.name().equals(name) || c.aliases().contains(name)) return c;
        }
        return new ExtensionCommand(cwd, name);
    }

    /** The text of {@code jpm help}. */
    static String help() {
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

    /** The command table in the README, between its {@code <!-- commands -->} markers. */
    static String readmeTable() {
        List<String> rows = new ArrayList<>();
        rows.add("| Command | |");
        rows.add("|---|---|");
        for (Command c : all(Path.of(""))) {
            for (Command.Usage u : c.usages()) rows.add("| `" + u.synopsis() + "` | " + u.docs() + " |");
        }
        rows.add("| `<anything else>` | Runs the extension `jpm-<name>` from `~/.jpm/bin` or `PATH`; install one with `jpm add -g <dep>` |");
        return String.join("\n", rows) + "\n";
    }
}
