package dev.jpm.maven;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * Credentials for Maven repositories that need them, from the environment, never from {@code project.jsonc}:
 * {@code JPM_REPO_<HOST>_TOKEN} (sent as a bearer token), or {@code JPM_REPO_<HOST>_USERNAME} and
 * {@code _PASSWORD} (basic authentication). {@code <HOST>} is the repository's host name in upper case with
 * every other character than a letter or digit as {@code _}, so {@code https://repo.example.dev/releases} reads
 * {@code JPM_REPO_REPO_EXAMPLE_DEV_USERNAME}. They are sent only over HTTPS, or to this machine.
 */
public final class Credentials {
    private Credentials() {}

    /** The {@code <HOST>} part of the variables for a repository URL, or null when it has no host. */
    public static String name(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (host == null || host.isEmpty()) return null;
        return host.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    /** The prefix of the variables for a repository URL, such as {@code JPM_REPO_REPO_EXAMPLE_DEV}, or null. */
    public static String prefix(String url) {
        String n = name(url);
        return n == null ? null : "JPM_REPO_" + n;
    }

    /** The {@code Authorization} header for a URL from the process environment, or null when there are no credentials. */
    public static String authorization(String url) {
        return authorization(url, System.getenv());
    }

    /** The {@code Authorization} header for a URL from these variables, or null when there are none or the URL is not safe for them. */
    public static String authorization(String url, Map<String, String> env) {
        String prefix = prefix(url);
        if (prefix == null || !safe(url)) return null;
        String token = env.get(prefix + "_TOKEN");
        if (token != null && !token.isEmpty()) return "Bearer " + token;
        String user = env.get(prefix + "_USERNAME"), password = env.get(prefix + "_PASSWORD");
        if (user == null || user.isEmpty()) return null;
        String pair = user + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    /** Whether credentials may be sent to a URL: HTTPS, or plain HTTP to this machine. */
    public static boolean safe(String url) {
        URI u = URI.create(url);
        if ("https".equalsIgnoreCase(u.getScheme())) return true;
        String h = u.getHost();
        return "http".equalsIgnoreCase(u.getScheme()) && h != null
                && (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1"));
    }

    /** Whether two URLs are on the same scheme, host and port, so credentials for one may follow a redirect to the other. */
    public static boolean sameOrigin(String a, String b) {
        URI x = URI.create(a), y = URI.create(b);
        return x.getScheme() != null && x.getScheme().equalsIgnoreCase(y.getScheme()) && x.getHost() != null
                && x.getHost().equalsIgnoreCase(y.getHost()) && port(x) == port(y);
    }

    private static int port(URI u) {
        if (u.getPort() != -1) return u.getPort();
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }
}
