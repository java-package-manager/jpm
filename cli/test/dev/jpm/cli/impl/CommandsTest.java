package dev.jpm.cli.impl;

import dev.jpm.testing.Checkout;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsTest {
    private static final String START = "<!-- commands: generated from the command registry, `JPM_UPDATE_EXPECTED=1 bin/jpm test dev.jpm.cli.impl.CommandsTest` rewrites it -->\n";
    private static final String END = "<!-- /commands -->";

    @Test
    void theCommandReferenceIsTheRegistrys() {
        Path doc = Checkout.root().resolve("docs/commands.md");
        String text = Fs.read(doc).replace("\r\n", "\n");
        int a = text.indexOf(START), b = text.indexOf(END);
        assertTrue(a >= 0 && b > a, "docs/commands.md has no command table markers");
        String want = text.substring(0, a + START.length()) + "\n" + Commands.referenceTable() + "\n" + text.substring(b);
        if ("1".equals(System.getenv("JPM_UPDATE_EXPECTED"))) Fs.write(doc, want);
        else assertEquals(want, text, "the command table in docs/commands.md is out of date: JPM_UPDATE_EXPECTED=1 rewrites it");
    }

    @Test
    void namesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Command c : Commands.all(Path.of(""))) {
            assertTrue(seen.add(c.name()), c.name());
            for (String a : c.aliases()) assertTrue(seen.add(a), a);
        }
    }

    @Test
    void anyOtherNameIsAnExtension() {
        assertInstanceOf(ExtensionCommand.class, Commands.find(Path.of(""), "format"));
        assertInstanceOf(RemoveCommand.class, Commands.find(Path.of(""), "rm"));
    }

    @Test
    void helpListsEveryUsageLine() {
        String help = Commands.help();
        for (Command c : Commands.all(Path.of(""))) {
            for (Command.Usage u : c.usages()) assertTrue(help.contains("  " + u.synopsis()), u.synopsis());
        }
        for (String line : help.split("\n")) assertTrue(line.length() <= 108, line);
    }

    @Test
    void completionsNameEveryCommand() {
        List<Command> all = Commands.all(Path.of(""));
        for (String shell : Completions.SHELLS) {
            String script = Completions.script(shell, all);
            for (Command c : all) assertTrue(script.contains(c.name()), shell + ": " + c.name());
            assertFalse(script.isBlank());
        }
        assertTrue(Completions.script("bash", all).contains("add) COMPREPLY=($(compgen -W \"--dev -D --test -T -g --global -r --repo --repository\""));
    }
}
