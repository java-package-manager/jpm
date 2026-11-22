package dev.jpm.cli;

import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShimsTest {
    @Test
    void tellsJdksFromCommands() {
        assertTrue(JavaCommands.looksLikeSpec("21"));
        assertTrue(JavaCommands.looksLikeSpec("temurin-17"));
        assertTrue(JavaCommands.looksLikeSpec("graalvm"));
        assertFalse(JavaCommands.looksLikeSpec("java"));
        assertFalse(JavaCommands.looksLikeSpec("javac"));
        assertFalse(JavaCommands.looksLikeSpec("-jar"));
        assertFalse(JavaCommands.looksLikeSpec("dist/app.jar"));
        assertFalse(JavaCommands.looksLikeSpec("mvn"));
    }

    @Test
    void recognizesShimNames() {
        assertTrue(Shims.isTool("/home/u/.jpm/bin/java"));
        assertTrue(Shims.isTool("C:\\Users\\u\\.jpm\\bin\\JAVAC.EXE"));
        assertTrue(Shims.isTool("native-image"));
        assertFalse(Shims.isTool("/home/u/.jpm/bin/jpm"));
        assertFalse(Shims.isTool("jvm"));
        assertEquals("javac", Shims.toolName("C:\\x\\javac.exe"));
    }

    @Test
    void findsTheProjectThatDecides(@TempDir Path dir) {
        Fs.write(dir.resolve("project.jsonc"), "{ \"name\": \"ws\", \"version\": \"1.0.0\", \"workspace\": [\"a\"] }");
        Fs.write(dir.resolve("a/project.jsonc"), "{ \"name\": \"a\", \"java\": \"17\" }");
        assertEquals("17", Shims.projectManifest(dir.resolve("a")).java);
        assertEquals("17", Shims.projectManifest(dir).java, "from the workspace root, a module's java applies");
        assertNull(Shims.projectManifest(dir.getParent().resolve("elsewhere-" + System.nanoTime())));
    }

    @Test
    void depthOfNestedShims() {
        assertEquals(0, Shims.depth(null));
        assertEquals(0, Shims.depth("x"));
        assertEquals(3, Shims.depth(" 3 "));
    }
}
