package dev.jpm.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellSetupTest {
    @Test
    void migratesTheOldInstallerLine() {
        String old = "alias ll='ls -l'\n\n# Added by the jpm installer\nexport PATH=\"$HOME/.jpm/bin:$PATH\"\nexport EDITOR=vim\n";
        assertTrue(ShellSetup.hasOldLine(old));
        String next = ShellSetup.withSourceLine(old);
        assertTrue(next.contains(ShellSetup.sourceLine()), next);
        assertTrue(ShellSetup.sourcesEnv(next));
        assertFalse(next.contains("export PATH=\"$HOME/.jpm/bin"), next);
        assertTrue(next.contains("export EDITOR=vim"));
        assertEquals(next, ShellSetup.withSourceLine(next), "added only once");
    }

    @Test
    void appendsWhenThereIsNoLine() {
        String next = ShellSetup.withSourceLine("export A=1");
        assertTrue(next.startsWith("export A=1\n\n" + ShellSetup.MARKER + "\n"), next);
        assertTrue(ShellSetup.withSourceLine("").startsWith(ShellSetup.MARKER));
    }

    @Test
    void envScriptKeepsTheFolderFirst() {
        String env = ShellSetup.posixEnv();
        assertTrue(env.contains("PROMPT_COMMAND"));
        assertTrue(env.contains("add-zsh-hook precmd __jpm_path"));
        assertTrue(env.contains("${__jpm_rest%%:$__jpm_bin:*}"), "longest-suffix removal survives formatting");
    }
}
