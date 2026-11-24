package dev.jpm.resolve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnownExtensionsTest {
    @Test
    void aKnownNameIsItsDependencyAndEverythingElseStaysAsItIs() {
        assertEquals("github:java-package-manager/jpm-publish", KnownExtensions.expand("publish"));
        assertEquals("github:java-package-manager/jpm-format@^0.4", KnownExtensions.expand("format@^0.4"));
        assertEquals("github:java-package-manager/jpm-format#main", KnownExtensions.expand("format#main"));
        assertEquals("com.google.guava:guava", KnownExtensions.expand("com.google.guava:guava"));
        assertEquals("github:me/publish", KnownExtensions.expand("github:me/publish"));
        assertEquals("frobnicate", KnownExtensions.expand("frobnicate"));
        assertTrue(KnownExtensions.named("changeset").isPresent());
    }
}
