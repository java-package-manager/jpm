package dev.jpm.cli.impl;

import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitIgnoreTest {
    @Test
    void anExistingGitignoreGetsTheMissingEntries(@TempDir Path dir) {
        Fs.write(dir.resolve(".gitignore"), ".idea/\nout\n/dist\n");
        assertEquals(List.of(".cache/", ".env.local"), InitCommand.ignoreJpmFolders(dir));
        assertEquals(".idea/\nout\n/dist\n\n# jpm\n.cache/\n.env.local\n", Fs.read(dir.resolve(".gitignore")));
        assertEquals(List.of(), InitCommand.ignoreJpmFolders(dir));
    }

    @Test
    void aNewOneHasThemAll(@TempDir Path dir) {
        InitCommand.ignoreJpmFolders(dir);
        assertEquals(".cache/\ndist/\n.env.local\n", Fs.read(dir.resolve(".gitignore")));
    }
}
