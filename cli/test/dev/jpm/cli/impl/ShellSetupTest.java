package dev.jpm.cli.impl;

import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    @Test
    void envScriptLoadsCompletionsInInteractiveBashAndZsh() {
        String env = ShellSetup.posixEnv();
        assertTrue(env.contains("*i*)"), env);
        assertTrue(env.contains("/jpm.bash\""), env);
        assertTrue(env.contains("/jpm.zsh\""), env);
        assertFalse(env.contains("fpath"), "zsh-only syntax stays out of the POSIX env file");
    }

    @Test
    void fishFindsTheCompletionsFolder() {
        String conf = ShellSetup.fishConf();
        assertTrue(conf.contains("set --global --append fish_complete_path \"" + ShellSetup.completionsDir().toAbsolutePath().normalize() + "\""), conf);
    }

    @Test
    void zshLoaderAddsTheFolderToFpathOnce() {
        String loader = ShellSetup.zshLoader();
        assertTrue(loader.contains("fpath[(Ie)$__jpm_completions]"), loader);
        assertTrue(loader.contains("${+functions[compdef]}"), loader);
        assertTrue(ShellSetup.completionFiles().get("_jpm").contains("if [ \"$funcstack[1]\" = _jpm ]"), "works when autoloaded from fpath");
    }

    @Test
    void completionsAreWrittenOnceAndKeptCurrent() {
        Path dir = ShellSetup.completionsDir();
        assertTrue(ShellSetup.installCompletions());
        for (String name : List.of("jpm.bash", "_jpm", "jpm.zsh", "jpm.fish")) assertTrue(Files.isRegularFile(dir.resolve(name)), name);
        assertFalse(ShellSetup.installCompletions(), "unchanged");
        Fs.write(dir.resolve("jpm.bash"), "old\n");
        assertTrue(ShellSetup.installCompletions(), "rewritten");
        assertEquals(ShellSetup.completionFiles().get("jpm.bash"), Fs.read(dir.resolve("jpm.bash")));
    }
}
