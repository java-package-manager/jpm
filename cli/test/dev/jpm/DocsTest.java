package dev.jpm;

import dev.jpm.api.json.Jsonc;
import dev.jpm.resolve.KnownExtensions;
import dev.jpm.testing.Checkout;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated parts of docs/: the manifest field reference from the schema (the command reference is CommandsTest's).
 */
class DocsTest {
    static final String START = "<!-- fields: generated from project.schema.json, `JPM_UPDATE_EXPECTED=1 bin/jpm test dev.jpm.DocsTest` rewrites it -->\n";
    static final String END = "<!-- /fields -->";

    @Test
    @SuppressWarnings("unchecked")
    void theFieldReferenceIsTheSchemas() {
        Path doc = Checkout.root().resolve("docs/manifest.md");
        String text = Fs.read(doc).replace("\r\n", "\n");
        int a = text.indexOf(START), b = text.indexOf(END);
        assertTrue(a >= 0 && b > a, "docs/manifest.md has no field reference markers");
        Map<String, Object> schema = (Map<String, Object>) Jsonc.parse(Fs.read(Path.of("src/assets/dev/jpm/project.schema.json")));
        String want = text.substring(0, a + START.length()) + "\n" + new ManifestReference(schema).render() + "\n" + text.substring(b);
        if ("1".equals(System.getenv("JPM_UPDATE_EXPECTED"))) Fs.write(doc, want);
        else assertEquals(want, text, "the field reference in docs/manifest.md is out of date: JPM_UPDATE_EXPECTED=1 rewrites it");
    }

    static final String KNOWN_START = "<!-- known: generated from KnownExtensions, `JPM_UPDATE_EXPECTED=1 bin/jpm test dev.jpm.DocsTest` rewrites it -->\n";
    static final String KNOWN_END = "<!-- /known -->";

    @Test
    void theKnownExtensionsAreListed() {
        Path doc = Checkout.root().resolve("docs/extensions.md");
        String text = Fs.read(doc).replace("\r\n", "\n");
        int a = text.indexOf(KNOWN_START), b = text.indexOf(KNOWN_END);
        assertTrue(a >= 0 && b > a, "docs/extensions.md has no known-extensions markers");
        StringBuilder table = new StringBuilder("\n| Command | Extension | |\n|---|---|---|\n");
        for (KnownExtensions.Known k : KnownExtensions.ALL) {
            String repo = k.dependency().replaceFirst("^github:", "https://github.com/");
            table.append("| `jpm ").append(k.name()).append("` | [").append(k.dependency()).append("](").append(repo).append(") | ")
                    .append(k.description()).append(" |\n");
        }
        String want = text.substring(0, a + KNOWN_START.length()) + table + "\n" + text.substring(b);
        if ("1".equals(System.getenv("JPM_UPDATE_EXPECTED"))) Fs.write(doc, want);
        else assertEquals(want, text, "the known extensions in docs/extensions.md are out of date: JPM_UPDATE_EXPECTED=1 rewrites it");
    }
}
