package dev.jpm.util.impl;


import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proxies from the environment and certificates from the system, for the native binary.
 */
class NetworkSettingsTest {
    private static String hostPort(List<Proxy> l) {
        Proxy p = l.get(0);
        if (p.type() == Proxy.Type.DIRECT) return "direct";
        InetSocketAddress a = (InetSocketAddress) p.address();
        return a.getHostString() + ":" + a.getPort();
    }

    @Test
    void proxiesFromTheEnvironment() {
        Assumptions.assumeTrue(System.getProperty("https.proxyHost") == null && System.getProperty("http.proxyHost") == null,
                "system properties take precedence");
        ProxySelector s = NetworkSettings.proxySelector(Map.of(
                "HTTPS_PROXY", "http://proxy.corp:3128",
                "http_proxy", "plain.corp:8080",
                "NO_PROXY", "localhost, .internal.corp,example.org"));
        assertEquals("proxy.corp:3128", hostPort(s.select(URI.create("https://repo.maven.apache.org/maven2/"))));
        assertEquals("plain.corp:8080", hostPort(s.select(URI.create("http://repo.example.com/"))));
        assertEquals("direct", hostPort(s.select(URI.create("https://localhost:8080/"))));
        assertEquals("direct", hostPort(s.select(URI.create("https://nexus.internal.corp/"))));
        assertEquals("direct", hostPort(s.select(URI.create("https://repo.example.org/"))));
        assertEquals("proxy.corp:3128", hostPort(s.select(URI.create("https://notexample.org/"))));
    }

    /**
     * A request goes through the proxy HTTP_PROXY names: a local server stands in for it.
     */
    @Test
    void clientsUseTheProxyFromTheEnvironment() throws Exception {
        Assumptions.assumeTrue(System.getProperty("https.proxyHost") == null && System.getProperty("http.proxyHost") == null,
                "system properties take precedence");
        com.sun.net.httpserver.HttpServer proxy = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        proxy.createContext("/", ex -> {
            seen.add(ex.getRequestURI().toString());
            byte[] b = "via proxy".getBytes();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        proxy.start();
        try {
            String addr = "http://127.0.0.1:" + proxy.getAddress().getPort();
            java.net.http.HttpClient c = NetworkSettings.client(java.time.Duration.ofSeconds(5), Map.of("HTTP_PROXY", addr)).build();
            java.net.http.HttpResponse<String> r = c.send(java.net.http.HttpRequest.newBuilder(URI.create("http://repo.invalid/g/a/1.0/a-1.0.pom")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals("via proxy", r.body());
            assertEquals(List.of("http://repo.invalid/g/a/1.0/a-1.0.pom"), seen);
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void parsesProxySettings() {
        assertEquals(new InetSocketAddress("h", 80).getPort(), ((InetSocketAddress) NetworkSettings.proxy("h").address()).getPort());
        assertEquals(3128, ((InetSocketAddress) NetworkSettings.proxy("http://u:p@h:3128/").address()).getPort());
        assertNull(NetworkSettings.proxy(null));
        assertTrue(NetworkSettings.bypass("anything", List.of("*")));
        assertFalse(NetworkSettings.bypass("corp.com", List.of(".internal.corp.com")));
    }

    @Test
    void readsCertificateBundles(@TempDir Path dir) throws Exception {
        javax.net.ssl.TrustManagerFactory f = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        f.init((java.security.KeyStore) null);
        java.security.cert.X509Certificate[] cas = ((javax.net.ssl.X509TrustManager) f.getTrustManagers()[0]).getAcceptedIssuers();
        Assumptions.assumeTrue(cas.length > 1);
        StringBuilder pem = new StringBuilder();
        java.util.Base64.Encoder enc = java.util.Base64.getMimeEncoder(64, "\n".getBytes());
        for (int i = 0; i < 2; i++) {
            pem.append("-----BEGIN CERTIFICATE-----\n").append(enc.encodeToString(cas[i].getEncoded())).append("\n-----END CERTIFICATE-----\n");
        }
        Path bundle = dir.resolve("bundle.pem");
        Files.writeString(bundle, pem);
        assertEquals(2, NetworkSettings.systemCertificates(Map.of("SSL_CERT_FILE", bundle.toString())).size());
        Path certDir = dir.resolve("certs");
        Files.createDirectories(certDir);
        Files.copy(bundle, certDir.resolve("one.pem"));
        Files.writeString(certDir.resolve("README"), "not a certificate");
        assertEquals(2, NetworkSettings.systemCertificates(Map.of("SSL_CERT_DIR", certDir.toString())).size());
        assertTrue(NetworkSettings.readCertificates(dir.resolve("missing.pem")).isEmpty());
    }
}
