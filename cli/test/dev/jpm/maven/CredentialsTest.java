package dev.jpm.maven;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialsTest {
    @Test
    void theVariablesAreNamedAfterTheHost() {
        assertEquals("JPM_REPO_REPO_EXAMPLE_DEV", Credentials.prefix("https://repo.example.dev/releases"));
        assertEquals("JPM_REPO_MAVEN_PKG_GITHUB_COM", Credentials.prefix("https://maven.pkg.github.com/me/repo"));
        assertNull(Credentials.prefix("file:///tmp/repo"));
    }

    @Test
    void aTokenWinsOverAUsernameAndPassword() {
        String url = "https://repo.example.dev/releases";
        assertEquals("Bearer t0k", Credentials.authorization(url, Map.of("JPM_REPO_REPO_EXAMPLE_DEV_TOKEN", "t0k",
                "JPM_REPO_REPO_EXAMPLE_DEV_USERNAME", "me")));
        assertEquals("Basic bWU6c2VjcmV0", Credentials.authorization(url, Map.of("JPM_REPO_REPO_EXAMPLE_DEV_USERNAME", "me",
                "JPM_REPO_REPO_EXAMPLE_DEV_PASSWORD", "secret")));
        assertNull(Credentials.authorization(url, Map.of()));
    }

    @Test
    void credentialsAreNeverSentInPlainTextToAnotherMachine() {
        Map<String, String> env = Map.of("JPM_REPO_REPO_EXAMPLE_DEV_TOKEN", "t", "JPM_REPO_127_0_0_1_TOKEN", "t");
        assertNull(Credentials.authorization("http://repo.example.dev/releases", env));
        assertEquals("Bearer t", Credentials.authorization("http://127.0.0.1:8080/releases", env));
    }

    @Test
    void redirectsKeepCredentialsOnlyOnTheSameOrigin() {
        assertTrue(Credentials.sameOrigin("https://repo.example.dev/a", "https://repo.example.dev:443/b"));
        assertFalse(Credentials.sameOrigin("https://repo.example.dev/a", "https://cdn.example.dev/b"));
        assertFalse(Credentials.sameOrigin("https://repo.example.dev/a", "http://repo.example.dev/a"));
    }
}
