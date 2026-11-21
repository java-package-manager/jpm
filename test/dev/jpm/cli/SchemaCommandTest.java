package dev.jpm.cli;

import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `jpm schema` serves the schema it was built with. */
class SchemaCommandTest {
    @Test
    void theBuiltInSchemaIsTheOneInTheSources() {
        assertEquals(Fs.read(Path.of("src/assets/dev/jpm/project.schema.json")), Schema.text());
    }

    @Test
    void pathWritesTheSchemaOncePerVersion(@TempDir Path home) throws Exception {
        Path file = Schema.install(home, "1.2.3");
        assertEquals(home.resolve("schemas/1.2.3/project.schema.json"), file);
        assertEquals(Schema.text(), Fs.read(file));
        var written = Files.getLastModifiedTime(file);
        Thread.sleep(20);
        assertEquals(file, Schema.install(home, "1.2.3"));
        assertEquals(written, Files.getLastModifiedTime(file), "an unchanged schema is not rewritten");
        Fs.write(file, "{}");
        Schema.install(home, "1.2.3");
        assertTrue(Fs.read(file).contains("\"$id\""), "a changed file is replaced");
    }
}
