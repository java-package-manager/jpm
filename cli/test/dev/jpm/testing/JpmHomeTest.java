package dev.jpm.testing;

import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpmHomeTest {
    @Test
    void everyTestHasItsOwnEmptyHome() throws Exception {
        assertNotEquals(JpmHome.outerRepository().getParent(), Fs.home());
        try (var files = Files.list(Fs.home())) {
            assertTrue(files.findAny().isEmpty(), Fs.home().toString());
        }
    }
}
