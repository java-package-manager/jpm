package dev.jpm.api.json;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsoncTest {
    @Test
    void parsesCommentsAndTrailingCommas() {
        Object v = Jsonc.parse("""
                // leading
                {
                  "a": 1, /* inline */
                  "b": [true, false, null,],
                  "c": { "d": "x\\ny" }, // trailing
                }
                """);
        Map<?, ?> m = (Map<?, ?>) v;
        assertEquals(new BigDecimal("1"), m.get("a"));
        assertEquals(java.util.Arrays.asList(true, false, null), m.get("b"));
        assertEquals("x\ny", ((Map<?, ?>) m.get("c")).get("d"));
    }

    @Test
    void reportsLineOfErrors() {
        Jsonc.ParseException e = assertThrows(Jsonc.ParseException.class, () -> Jsonc.parse("{\n  \"a\": 1\n  \"b\": 2\n}"));
        assertEquals(3, e.line);
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThrows(Jsonc.ParseException.class, () -> Jsonc.parse("{\"a\":1,\"a\":2}"));
    }

    @Test
    void writesRoundTrip() {
        Object v = Jsonc.parse("{\"a\": [\"x\", \"y\"], \"b\": {\"c\": true}}");
        assertEquals(v, Jsonc.parse(Jsonc.write(v)));
    }
}
