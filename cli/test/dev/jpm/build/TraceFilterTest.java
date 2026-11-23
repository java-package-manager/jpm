package dev.jpm.build;

import dev.jpm.api.json.Jsonc;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceFilterTest {
    @Test
    @SuppressWarnings("unchecked")
    void theTestFrameworkAndTheTestClassesStayOut(@TempDir Path tmp) {
        Fs.write(tmp.resolve("shop/CartTest.class"), "");
        Fs.write(tmp.resolve("shop/CartTest$Nested.class"), "");
        Map<String, Object> filter = (Map<String, Object>) Jsonc.parse(TestRunner.traceFilter(tmp));
        List<Object> rules = (List<Object>) filter.get("rules");
        assertTrue(rules.contains(Map.of("includeClasses", "**")), rules.toString());
        assertTrue(rules.contains(Map.of("excludeClasses", "org.junit.**")), rules.toString());
        assertTrue(rules.contains(Map.of("excludeClasses", "shop.CartTest")), rules.toString());
        assertTrue(rules.contains(Map.of("excludeClasses", "shop.CartTest$Nested")), rules.toString());
    }
}
