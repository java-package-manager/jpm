package dev.jpm.util;

import dev.jpm.log.Log;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Network settings for every HTTP client jpm makes, so the native binary behaves like the JVM tools around it:
 *
 * <ul>
 *   <li>proxies from {@code HTTPS_PROXY}, {@code HTTP_PROXY} and {@code NO_PROXY} (any case), unless the
 *       {@code https.proxyHost} / {@code http.proxyHost} system properties set one: a native image does not
 *       read {@code JAVA_TOOL_OPTIONS}, so the environment is the only way to configure it;</li>
 *   <li>certificate authorities from the system trust store ({@code SSL_CERT_FILE}, {@code SSL_CERT_DIR}, or the
 *       usual Linux bundles) besides the ones built in, which a native image fixes at build time: a proxy or
 *       network with its own CA then works without rebuilding jpm.</li>
 * </ul>
 */
public final class Net {
    private Net() {}

    private static volatile SSLContext ssl;

    /** A client builder with jpm's proxy and trust settings. */
    public static HttpClient.Builder client(Duration connectTimeout) {
        return client(connectTimeout, System.getenv());
    }

    static HttpClient.Builder client(Duration connectTimeout, Map<String, String> env) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(proxySelector(env))
                .connectTimeout(connectTimeout);
        SSLContext ctx = sslContext();
        if (ctx != null) b.sslContext(ctx);
        return b;
    }

    // ---------------------------------------------------------------- proxies

    /** System properties when they set a proxy, else the proxy environment variables, else none. */
    static ProxySelector proxySelector(Map<String, String> env) {
        if (System.getProperty("https.proxyHost") != null || System.getProperty("http.proxyHost") != null) {
            return ProxySelector.getDefault();
        }
        Proxy https = proxy(get(env, "HTTPS_PROXY"));
        Proxy http = proxy(get(env, "HTTP_PROXY"));
        if (https == null && http == null) return ProxySelector.getDefault();
        List<String> noProxy = new ArrayList<>();
        String np = get(env, "NO_PROXY");
        if (np != null) {
            for (String s : np.split(",")) if (!s.isBlank()) noProxy.add(s.trim().toLowerCase(Locale.ROOT));
        }
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                if (bypass(host, noProxy)) return List.of(Proxy.NO_PROXY);
                Proxy p = "https".equalsIgnoreCase(uri.getScheme()) ? (https != null ? https : http) : (http != null ? http : https);
                return List.of(p == null ? Proxy.NO_PROXY : p);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                Log.debug("proxy " + sa + " failed for " + uri + ": " + ioe.getMessage());
            }
        };
    }

    private static String get(Map<String, String> env, String name) {
        String v = env.get(name);
        if (v == null || v.isBlank()) v = env.get(name.toLowerCase(Locale.ROOT));
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** {@code http://host:port}, {@code host:port} or {@code http://user:pass@host:port} (credentials ignored). */
    static Proxy proxy(String spec) {
        if (spec == null) return null;
        try {
            URI u = URI.create(spec.contains("://") ? spec : "http://" + spec);
            if (u.getHost() == null) return null;
            int port = u.getPort() != -1 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
            if (u.getUserInfo() != null) Log.debug("proxy credentials in the environment are not supported; using " + u.getHost() + ":" + port);
            return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(u.getHost(), port));
        } catch (IllegalArgumentException e) {
            Log.warn("ignoring the proxy setting \"" + spec + "\": " + e.getMessage());
            return null;
        }
    }

    /** NO_PROXY: {@code *}, exact hosts, domain suffixes ({@code .example.com} or {@code example.com}). */
    static boolean bypass(String host, List<String> noProxy) {
        for (String n : noProxy) {
            if (n.equals("*")) return true;
            String d = n.startsWith("*.") ? n.substring(1) : n;
            if (d.startsWith(".")) {
                if (host.endsWith(d) || host.equals(d.substring(1))) return true;
            } else if (host.equals(d) || host.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- certificates

    private static final List<String> BUNDLES = List.of(
            "/etc/ssl/certs/ca-certificates.crt", // Debian, Ubuntu, Alpine, Arch
            "/etc/pki/tls/certs/ca-bundle.crt", // Fedora, RHEL
            "/etc/ssl/ca-bundle.pem", // openSUSE
            "/etc/ssl/cert.pem"); // macOS, BSDs

    /** The built-in trust plus the system's certificates, or null for the default when there are none to add. */
    static SSLContext sslContext() {
        if (ssl != null) return ssl;
        synchronized (Net.class) {
            if (ssl != null) return ssl;
            try {
                List<X509Certificate> extra = systemCertificates(System.getenv());
                if (extra.isEmpty()) return null;
                TrustManagerFactory def = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                def.init((KeyStore) null);
                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);
                int i = 0;
                for (X509Certificate c : extra) ks.setCertificateEntry("system-" + i++, c);
                TrustManagerFactory sys = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                sys.init(ks);
                X509TrustManager both = combine(trustManager(def), trustManager(sys));
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{both}, null);
                ssl = ctx;
                return ctx;
            } catch (Exception e) {
                Log.debug("system certificates not used: " + e);
                return null;
            }
        }
    }

    /** Certificates from SSL_CERT_FILE and SSL_CERT_DIR, else the first system bundle that exists. */
    static List<X509Certificate> systemCertificates(Map<String, String> env) {
        List<Path> files = new ArrayList<>();
        String file = env.get("SSL_CERT_FILE");
        if (file != null && !file.isBlank()) files.add(Path.of(file));
        String dir = env.get("SSL_CERT_DIR");
        if (dir != null && !dir.isBlank()) {
            for (String d : dir.split(java.io.File.pathSeparator)) {
                for (Path f : Fs.files(Path.of(d), null)) {
                    String n = f.getFileName().toString();
                    if (n.endsWith(".pem") || n.endsWith(".crt") || n.matches("[0-9a-f]{8}\\.\\d+")) files.add(f);
                }
            }
        }
        if (files.isEmpty()) {
            for (String b : BUNDLES) {
                if (Files.isRegularFile(Path.of(b))) {
                    files.add(Path.of(b));
                    break;
                }
            }
        }
        List<X509Certificate> out = new ArrayList<>();
        for (Path f : files) out.addAll(readCertificates(f));
        return out;
    }

    static List<X509Certificate> readCertificates(Path pem) {
        List<X509Certificate> out = new ArrayList<>();
        if (!Files.isRegularFile(pem)) return out;
        try (InputStream in = Files.newInputStream(pem)) {
            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                if (c instanceof X509Certificate x) out.add(x);
            }
        } catch (IOException | CertificateException e) {
            Log.debug("could not read certificates from " + pem + ": " + e.getMessage());
        }
        return out;
    }

    private static X509TrustManager trustManager(TrustManagerFactory f) {
        for (TrustManager t : f.getTrustManagers()) if (t instanceof X509TrustManager x) return x;
        throw new IllegalStateException("no X509 trust manager");
    }

    /** Trusts what either trusts. */
    private static X509TrustManager combine(X509TrustManager a, X509TrustManager b) {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    a.checkClientTrusted(chain, authType);
                } catch (CertificateException e) {
                    b.checkClientTrusted(chain, authType);
                }
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    a.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    b.checkServerTrusted(chain, authType);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                X509Certificate[] x = a.getAcceptedIssuers(), y = b.getAcceptedIssuers();
                X509Certificate[] all = Arrays.copyOf(x, x.length + y.length);
                System.arraycopy(y, 0, all, x.length, y.length);
                return all;
            }
        };
    }
}
