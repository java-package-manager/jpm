package dev.jpm.maven;

import dev.jpm.api.json.Jsonc;
import dev.jpm.log.Log;
import dev.jpm.util.Fs;
import dev.jpm.util.Http;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Finds Maven coordinates by name, for editors that complete {@code group:artifact}: first what the download
 * cache has, then Maven Central's search service ({@code JPM_MAVEN_SEARCH_URL}, else https://search.maven.org).
 */
public final class MavenSearch {
    private MavenSearch() {
    }

    /**
     * A package that matches: its key and, when the search service says, its newest version.
     *
     * @param key    {@code group:artifact}
     * @param latest the newest version, or null
     */
    public record Hit(String key, String latest) {}

    /**
     * Where the search service is.
     */
    static String url() {
        String v = System.getenv("JPM_MAVEN_SEARCH_URL");
        return v != null && !v.isBlank() ? v.replaceAll("/+$", "") : "https://search.maven.org";
    }

    /**
     * Up to {@code limit} packages for {@code query}: a word of a group or artifact, or {@code group:} and the start of
     * an artifact. The cache's come first; when the service cannot be asked (offline, failing), only those.
     */
    public static List<Hit> search(String query, int limit) {
        String q = query.strip();
        Map<String, Hit> out = new LinkedHashMap<>();
        if (q.isEmpty() || limit <= 0) return List.of();
        for (String key : cached(q)) {
            if (out.size() >= limit) break;
            out.putIfAbsent(key, new Hit(key, null));
        }
        if (out.size() < limit && !Fs.offline()) {
            try {
                for (Hit h : remote(q, limit)) {
                    if (out.size() >= limit) break;
                    Hit before = out.get(h.key());
                    if (before == null || before.latest() == null) out.put(h.key(), h);
                }
            } catch (JpmException e) {
                Log.debug("maven search: " + e.getMessage());
                if (out.isEmpty()) throw e;
            }
        }
        return List.copyOf(out.values());
    }

    private static List<String> cached(String q) {
        int colon = q.indexOf(':');
        if (colon <= 0) return List.of();
        String group = q.substring(0, colon), prefix = q.substring(colon + 1);
        Path dir = Fs.home().resolve("repository").resolve(group.replace('.', '/'));
        if (!Files.isDirectory(dir)) return List.of();
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path a : s.sorted().toList()) {
                String name = a.getFileName().toString();
                if (!Files.isDirectory(a) || !name.startsWith(prefix) || !isArtifact(a)) continue;
                out.add(group + ":" + name);
            }
        } catch (IOException e) {
            Log.debug("maven search: " + e.getMessage());
        }
        return out;
    }

    private static boolean isArtifact(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.anyMatch(p -> p.getFileName().toString().startsWith("maven-metadata") || Files.isDirectory(p) && hasPom(p));
        }
    }

    private static boolean hasPom(Path versionDir) {
        try (Stream<Path> s = Files.list(versionDir)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(".pom"));
        } catch (IOException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Hit> remote(String q, int limit) {
        int colon = q.indexOf(':');
        String solr = colon > 0 ? "g:\"" + q.substring(0, colon) + "\"" + (colon + 1 < q.length() ? " AND a:" + q.substring(colon + 1) + "*" : "") : q;
        String url = url() + "/solrsearch/select?q=" + URLEncoder.encode(solr, StandardCharsets.UTF_8) + "&rows=" + limit + "&wt=json";
        Object parsed;
        try {
            parsed = Jsonc.parse(Http.getString(url));
        } catch (Jsonc.ParseException e) {
            throw new JpmException("The Maven search service answered something that is not JSON: " + e.getMessage());
        }
        List<Hit> out = new ArrayList<>();
        if (parsed instanceof Map<?, ?> m && m.get("response") instanceof Map<?, ?> r && r.get("docs") instanceof List<?> docs) {
            for (Object d : docs) {
                if (!(d instanceof Map<?, ?> doc) || !(doc.get("g") instanceof String g) || !(doc.get("a") instanceof String a)) continue;
                Object v = doc.get("latestVersion");
                out.add(new Hit(g + ":" + a, v instanceof String s ? s : null));
            }
        }
        return out;
    }
}
