package dev.jpm.log;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogTest {
    private static UnaryOperator<String> env(String... pairs) {
        Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m::get;
    }

    @Test
    void colorOnATerminalUnlessSomethingSaysOtherwise() {
        assertTrue(Log.Color.decide(null, env(), true));
        assertFalse(Log.Color.decide(null, env(), false));
        assertFalse(Log.Color.decide(null, env("TERM", "dumb"), true));
        assertFalse(Log.Color.decide(null, env("NO_COLOR", "1"), true));
        assertTrue(Log.Color.decide(null, env("NO_COLOR", ""), true), "an empty NO_COLOR does not count");
        assertTrue(Log.Color.decide(null, env("FORCE_COLOR", "1"), false));
        assertFalse(Log.Color.decide(null, env("FORCE_COLOR", "0"), true));
    }

    @Test
    void theFlagWinsOverJpmColorWhichWinsOverTheRest() {
        assertTrue(Log.Color.decide(Log.Color.ALWAYS, env("NO_COLOR", "1", "JPM_COLOR", "0"), false));
        assertFalse(Log.Color.decide(Log.Color.NEVER, env("FORCE_COLOR", "1", "JPM_COLOR", "1"), true));
        assertTrue(Log.Color.decide(null, env("JPM_COLOR", "1", "NO_COLOR", "1"), false));
        assertFalse(Log.Color.decide(null, env("JPM_COLOR", "never", "FORCE_COLOR", "1"), true));
        assertFalse(Log.Color.decide(Log.Color.AUTO, env("JPM_COLOR", "auto", "NO_COLOR", "1"), true));
        assertNull(Log.Color.parse("sometimes"));
    }

    @Test
    void warningsAreOrangeWhereTheTerminalHas256Colors() {
        assertTrue(Log.Color.has256(env("TERM", "xterm-256color")));
        assertTrue(Log.Color.has256(env("COLORTERM", "truecolor")));
        assertFalse(Log.Color.has256(env("TERM", "xterm")));
    }

    @Test
    void verboseEnvironment() {
        assertEquals(Log.Level.INFO, Log.Level.fromVerboseEnv(null));
        assertEquals(Log.Level.INFO, Log.Level.fromVerboseEnv("0"));
        assertEquals(Log.Level.DEBUG, Log.Level.fromVerboseEnv("1"));
        assertEquals(Log.Level.DEBUG, Log.Level.fromVerboseEnv("yes"));
        assertEquals(Log.Level.TRACE, Log.Level.fromVerboseEnv("trace"));
    }
}
