package dev.jpm.version;

import dev.jpm.version.impl.MavenRange;
import dev.jpm.version.impl.NpmRange;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTest {
    private static int cmp(String a, String b) {
        return Integer.signum(Version.of(a).compareTo(Version.of(b)));
    }

    @Test
    void ordersLikeMaven() {
        assertEquals(0, cmp("1.0", "1.0.0"));
        assertEquals(0, cmp("1.0-final", "1"));
        assertEquals(-1, cmp("1.0-alpha", "1.0-beta"));
        assertEquals(-1, cmp("1.0-rc1", "1.0"));
        assertEquals(-1, cmp("1.0-SNAPSHOT", "1.0"));
        assertEquals(1, cmp("1.0-sp1", "1.0"));
        assertEquals(-1, cmp("1.9", "1.10"));
        assertEquals(1, cmp("33.0.0-jre", "32.1.3-jre"));
        assertEquals(0, cmp("v1.2.3", "1.2.3"));
        assertEquals(0, cmp("1.2.3+abc", "1.2.3"));
        assertTrue(Version.of("2.0.0-M1").isPreRelease());
        assertFalse(Version.of("33.0.0-jre").isPreRelease());
    }

    @Test
    void mavenRanges() {
        MavenRange r = MavenRange.parse("[33.0,34.0)");
        assertTrue(r.matches(Version.of("33.0")));
        assertTrue(r.matches(Version.of("33.4.0-jre")));
        assertFalse(r.matches(Version.of("34.0")));
        assertTrue(MavenRange.parse("(,1.0],[1.2,)").matches(Version.of("1.5")));
        assertFalse(MavenRange.parse("(,1.0],[1.2,)").matches(Version.of("1.1")));
        assertTrue(MavenRange.parse("1.18.34").isExact());
        assertTrue(MavenRange.parse("[1.5]").isExact());
        assertThrows(IllegalArgumentException.class, () -> MavenRange.parse("^1.2"));
        assertEquals("33.2", r.best(List.of(Version.of("32.9"), Version.of("33.1"), Version.of("33.2"), Version.of("34.0"))).orElseThrow().toString());
    }

    @Test
    void npmRanges() {
        assertTrue(NpmRange.parse("^1.2").matches(Version.of("1.9.0")));
        assertFalse(NpmRange.parse("^1.2").matches(Version.of("2.0.0")));
        assertFalse(NpmRange.parse("^1.2").matches(Version.of("1.1.9")));
        assertTrue(NpmRange.parse("^0.2.3").matches(Version.of("0.2.9")));
        assertFalse(NpmRange.parse("^0.2.3").matches(Version.of("0.3.0")));
        assertTrue(NpmRange.parse("~1.2.3").matches(Version.of("1.2.9")));
        assertFalse(NpmRange.parse("~1.2.3").matches(Version.of("1.3.0")));
        assertTrue(NpmRange.parse("1.x").matches(Version.of("1.5.0")));
        assertTrue(NpmRange.parse(">=1.0 <2.0").matches(Version.of("1.5.0")));
        assertTrue(NpmRange.parse("1.0 - 2.0").matches(Version.of("2.0.5")));
        assertFalse(NpmRange.parse("1.0 - 2.0").matches(Version.of("2.1.0")));
        assertTrue(NpmRange.parse("^1.0 || ^3.0").matches(Version.of("3.1.0")));
        assertTrue(NpmRange.parse("1.2.3").matches(Version.of("v1.2.3")));
        assertFalse(NpmRange.parse("^1.2").matches(Version.of("1.3.0-beta")));
        assertTrue(NpmRange.parse("^1.3.0-beta").matches(Version.of("1.3.0-beta.2")));
        assertTrue(NpmRange.parse("*").matches(Version.of("0.0.1")));
    }

    @Test
    void tellsRangesFromTagsAndHashes() {
        assertTrue(NpmRange.looksLikeRange("^1.2"));
        assertTrue(NpmRange.looksLikeRange("1.2.3"));
        assertTrue(NpmRange.looksLikeRange("*"));
        assertFalse(NpmRange.looksLikeRange("release-candidate"));
        assertFalse(NpmRange.looksLikeRange("a1b2c3d"));
    }
}
