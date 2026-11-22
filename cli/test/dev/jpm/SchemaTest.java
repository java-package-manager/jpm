package dev.jpm;

import dev.jpm.api.json.Jsonc;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps src/assets/dev/jpm/project.schema.json and the manifest parser in step. */
class SchemaTest {
    private static final Path SCHEMA = Path.of("src/assets/dev/jpm/project.schema.json");

    @Test
    @SuppressWarnings("unchecked")
    void schemaDescribesExactlyTheFieldsJpmReads() {
        Map<String, Object> schema = (Map<String, Object>) Jsonc.parse(Fs.read(SCHEMA));
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertEquals(new TreeSet<>(Manifest.FIELDS), new TreeSet<>(props.keySet()));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"), "unknown fields are errors in jpm, so in the schema too");
        assertEquals(Manifest.SCHEMA_URL, schema.get("$id"));
    }

    @Test
    void jpmReadsTheFullExample() {
        Path f = dev.jpm.testing.Checkout.root().resolve("schema/examples/valid/everything.jsonc");
        Manifest m = Manifest.parse(f, Fs.read(f));
        assertEquals("my-app", m.name);
        assertTrue(m.dependencies.stream().anyMatch(Manifest.Dependency::runtimeOnly));
        assertEquals(2, m.mains.size());
    }
}
