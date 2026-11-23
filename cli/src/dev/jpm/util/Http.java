package dev.jpm.util;

import dev.jpm.log.Log;
import dev.jpm.util.impl.NetworkSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * jpm's one way to make HTTP requests: clients with the proxy and trust settings of
 * {@link NetworkSettings}, plain GETs and downloads with progress.
 */
public final class Http {
    private Http() {
    }

    private static HttpClient client;

    /**
     * The shared client, with a 20 second connect timeout.
     */
    public static synchronized HttpClient client() {
        if (client == null) {
            client = client(Duration.ofSeconds(20));
        }
        return client;
    }

    /**
     * A new client with another connect timeout, for requests that must give up quickly.
     */
    public static HttpClient client(Duration connectTimeout) {
        return NetworkSettings.client(connectTimeout).build();
    }

    private static HttpClient noRedirects;

    /**
     * The shared client that does not follow redirects, for requests that carry credentials and follow them by hand.
     */
    public static synchronized HttpClient clientWithoutRedirects() {
        if (noRedirects == null) {
            noRedirects = NetworkSettings.client(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();
        }
        return noRedirects;
    }

    private static HttpRequest get(String url, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("User-Agent", "jpm").GET().build();
    }

    /**
     * The body of a POST of JSON; fails on any status but 200.
     */
    public static String postJson(String url, String json) {
        if (Fs.offline()) throw new JpmException("Offline: cannot reach " + url);
        Log.debug("POST " + url);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("User-Agent", "jpm")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<String> r = client().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) throw new JpmException("POST " + url + " failed with HTTP " + r.statusCode());
            return r.body();
        } catch (IOException e) {
            throw new JpmException("Could not reach " + url + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JpmException("Interrupted");
        }
    }

    /**
     * The body of a GET request; fails on any status but 200.
     */
    public static String getString(String url) {
        if (Fs.offline()) throw new JpmException("Offline: cannot fetch " + url);
        Log.debug("GET " + url);
        try {
            HttpResponse<String> r = client().send(get(url, Duration.ofSeconds(60)), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) throw new JpmException("GET " + url + " failed with HTTP " + r.statusCode());
            return r.body();
        } catch (IOException e) {
            throw new JpmException("Could not fetch " + url + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JpmException("Interrupted");
        }
    }

    /**
     * Downloads to {@code target} (atomically), printing progress for large files, and returns the
     * SHA-256 of what was downloaded.
     */
    public static String download(String url, Path target, String label) {
        if (Fs.offline()) throw new JpmException("Offline: cannot download " + url);
        Log.debug("GET " + url);
        try {
            HttpResponse<InputStream> r = client().send(get(url, Duration.ofMinutes(30)), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) {
                r.body().close();
                throw new JpmException("Download of " + url + " failed with HTTP " + r.statusCode());
            }
            long total = r.headers().firstValueAsLong("Content-Length").orElse(-1);
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp);
                 Log.Progress progress = Log.progress("Downloading", label)) {
                byte[] buf = new byte[1 << 16];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    done += n;
                    progress.update(done, total);
                }
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw new JpmException("Could not download " + url + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JpmException("Interrupted");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
