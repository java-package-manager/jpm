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
    @SuppressWarnings("unchecked")
    void everyFieldHasADescriptionForEditorsToShow() {
        Map<String, Object> schema = (Map<String, Object>) Jsonc.parse(Fs.read(SCHEMA));
        java.util.List<String> missing = new java.util.ArrayList<>();
        undescribed(schema, "", missing);
        assertEquals(java.util.List.of(), missing, "fields without a \"description\"");
    }

    @SuppressWarnings("unchecked")
    private static void undescribed(Object node, String path, java.util.List<String> missing) {
        if (!(node instanceof Map<?, ?> m)) return;
        if (m.get("properties") instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> e : props.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> v && !v.containsKey("description") && !v.containsKey("$ref")) missing.add(path + "/" + e.getKey());
                undescribed(e.getValue(), path + "/" + e.getKey(), missing);
            }
        }
        for (String k : java.util.List.of("additionalProperties", "items")) undescribed(m.get(k), path, missing);
        if (m.get("patternProperties") instanceof Map<?, ?> pp) for (Object v : pp.values()) undescribed(v, path, missing);
        if (m.get("definitions") instanceof Map<?, ?> d) for (Map.Entry<?, ?> e : d.entrySet()) undescribed(e.getValue(), "#" + e.getKey(), missing);
        for (String k : java.util.List.of("oneOf", "anyOf", "allOf")) {
            if (m.get(k) instanceof java.util.List<?> l) for (Object v : l) undescribed(v, path, missing);
        }
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
