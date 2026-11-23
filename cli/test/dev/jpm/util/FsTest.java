package dev.jpm.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FsTest {
    @Test
    void deleteTreeRemovesReadOnlyFiles(@TempDir Path tmp) {
        Path dir = tmp.resolve("repo/.git/objects/4b");
        Path object = dir.resolve("9901068d0288ce5899d1859d70655dd48feacc");
        Fs.write(object, "blob");
        assertTrue(object.toFile().setReadOnly());
        Fs.deleteTree(tmp.resolve("repo"));
        assertFalse(Files.exists(tmp.resolve("repo")));
    }
}
