package dev.jpm.version;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionSpecsTest {
    private static final List<Version> VERSIONS = List.of("1.0", "1.2.0", "1.10.0", "2.0.0", "2.1.0-beta", "2.2.0-SNAPSHOT")
            .stream().map(Version::of).toList();

    private static String best(String spec) {
        return VersionSpecs.parse(spec).best(VERSIONS).map(Version::toString).orElse("none");
    }

    private static boolean m(String spec, String version) {
        return VersionSpecs.parse(spec).matches(Version.of(version));
    }

    @Test
    void maven() {
        assertEquals("1.2.0", best("1.2.0"));
        assertTrue(VersionSpecs.parse("1.2.0").isExact());
        assertEquals("1.10.0", best("[1.0,2.0)"));
        assertEquals("2.0.0", best("[1.5,)"));
        assertEquals("1.0", best("(,1.0]"));
        assertEquals("1.2.0", best("[1.2.0]"));
        assertEquals("2.0.0", best("(,1.0],[1.2,)"));
        assertFalse(m("(,1.0],[1.2,)", "1.1"));
        assertEquals("2.0.0", best("RELEASE"));
        assertEquals("2.2.0-SNAPSHOT", best("LATEST"));
    }

    @Test
    void gradle() {
        assertEquals("1.10.0", best("1.+"));
        assertEquals("1.2.0", best("1.2.+"));
        assertEquals("2.2.0-SNAPSHOT", best("+"), "Gradle's + takes the newest of any kind");
        assertTrue(m("1.+", "1.10.0") && !m("1.+", "2.0.0") && !m("1.2.+", "1.20.0"));
        assertEquals("1.10.0", best("]1.0,2.0["));
        assertFalse(m("]1.0,2.0[", "1.0"));
        assertEquals("1.10.0", best("[1.0,2.0["));
        assertFalse(m("[1.0,2.0[", "2.0.0"));
        assertEquals("2.0.0", best("latest.release"));
        assertEquals("2.1.0-beta", best("latest.milestone"));
        assertEquals("2.2.0-SNAPSHOT", best("latest.integration"));
        assertEquals("1.2.0", best("1.2.0!!"));
        assertTrue(VersionSpecs.parse("1.2.0!!").isExact());
        assertEquals("1.2.0", best("[1.0,2.0)!!1.2.0"), "the preferred version inside the range");
        assertEquals("1.10.0", VersionSpecs.parse("[1.0,2.0)!!1.5").best(VERSIONS).map(Version::toString).orElse("none"),
                "a preferred version that is not published falls back to the range");
        assertThrows(IllegalArgumentException.class, () -> VersionSpecs.parse("[1.0,2.0)!!3.0"));
        assertThrows(IllegalArgumentException.class, () -> VersionSpecs.parse("!!1.0"));
    }

    @Test
    void npm() {
        assertEquals("1.10.0", best("^1.2"));
        assertEquals("1.2.0", best("~1.2"));
        assertEquals("1.10.0", best("~1"));
        assertEquals("2.0.0", best(">=0.1"));
        assertEquals("2.0.0", best(">= 0.1"));
        assertEquals("1.2.0", best(">=1.0 <1.5"));
        assertEquals("1.10.0", best("1.x"));
        assertEquals("1.2.0", best("1.2.*"));
        assertEquals("1.2.0", best("1.0 - 1.5"));
        assertEquals("2.0.0", best("^1 || ^2"));
        assertEquals("2.0.0", best("*"));
        assertEquals("1.2.0", best("=1.2.0"));
        assertTrue(VersionSpecs.parse("=1.2.0").isExact());
        assertEquals("2.1.0-beta", best(">=2.1.0-beta"), "a pre-release in the range lets pre-releases in");
        assertEquals("none", best(">2.0.0"), "pre-releases and snapshots need a - in the range");
    }

    @Test
    void npmRangesWorkOnMavenVersionsWithQualifiers() {
        List<Version> guava = List.of("32.1.3-jre", "33.0.0-jre", "33.4.8-jre", "33.4.8-android", "34.0.0-rc1").stream().map(Version::of).toList();
        assertTrue(m("^33", "33.4.8-jre"));
        assertFalse(m("^33", "34.0.0-rc1"));
        assertEquals("33.4.8-jre", VersionSpecs.parse("^33").best(guava).map(Version::toString).orElse("none"));
        assertTrue(m("~1.2", "1.2.3.Final"));
        assertTrue(m(">=5.0", "5.3.0.Final"));
        assertTrue(m("1.+", "1.3.Final"));
    }

    @Test
    void bareVersionsAreExactForMavenAndRangesForGitTags() {
        assertTrue(VersionSpecs.parse("1.2").isExact());
        assertFalse(VersionSpecs.parse("1.2").matches(Version.of("1.2.5")));
        assertTrue(VersionSpecs.parse("1.2", true).matches(Version.of("1.2.5")));
        assertTrue(VersionSpecs.parse("v1.2.3", true).matches(Version.of("v1.2.3")));
    }

    @Test
    void tellsRequirementsFromPreferences() {
        for (String bare : List.of("1.2.3", "33.0-jre", "5.3.0.Final", "1.0-SNAPSHOT")) {
            assertTrue(VersionSpecs.isBareVersion(bare), bare);
        }
        for (String spec : List.of("[1.0,2.0)", "1.+", "+", "^1.2", "~1", ">=0.1", "1.x", "1.0 - 2.0", "latest.release", "RELEASE", "1.2!!")) {
            assertFalse(VersionSpecs.isBareVersion(spec), spec);
            assertTrue(VersionSpecs.looksLikeSpec(spec), spec);
        }
        assertFalse(VersionSpecs.looksLikeSpec("release-candidate"));
        assertFalse(VersionSpecs.looksLikeSpec("main"));
    }
}
