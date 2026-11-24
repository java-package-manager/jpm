package dev.jpm.maven;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A package that project.lock says came from one repository is looked for there first, then on the same host, and
 * only then in the other repositories.
 */
class PreferredRepositoryTest {
    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    private final List<String> asked = new CopyOnWriteArrayList<>();

    private String serve(String name, Map<String, String> files) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (!path.contains(".sha") && !path.endsWith(".md5")) asked.add(name + path);
            String body = files.get(path);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        for (HttpServer s : servers) s.stop(0);
    }

    @Test
    void theLockedRepositoryThenItsHostThenTheRest() throws IOException {
        String other = serve("other", Map.of());
        String host = serve("host", Map.of("/beta/g/a/2.0-beta/a-2.0-beta.jar", "beta jar"));
        MavenRepo repo = new MavenRepo(List.of(other + "/maven", host + "/releases", other + "/second", host + "/beta"), true);
        assertEquals(List.of(other + "/maven/", host + "/releases/", other + "/second/", host + "/beta/"), repo.order("g", "a"));

        repo.prefer("g", "a", host + "/releases");
        assertEquals(List.of(host + "/releases/", host + "/beta/", other + "/maven/", other + "/second/"), repo.order("g", "a"));
        assertNotNull(repo.fetch("g", "a", "2.0-beta", null, "jar"));
        assertEquals(List.of("host/releases/g/a/2.0-beta/a-2.0-beta.jar", "host/beta/g/a/2.0-beta/a-2.0-beta.jar"), asked);
        assertEquals(host + "/beta/", repo.servedBy("g", "a", "2.0-beta", null, "jar"));

        repo.prefer("g", "a", "https://not.searched/");
        assertTrue(repo.order("g", "a").get(0).startsWith(host));
    }
}
