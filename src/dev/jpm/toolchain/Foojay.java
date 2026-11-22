package dev.jpm.toolchain;

import dev.jpm.json.Jsonc;
import dev.jpm.util.Http;
import dev.jpm.util.JpmException;
import dev.jpm.version.Version;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Client for the Foojay Disco API (api.foojay.io), the vendor-neutral index of JDK downloads.
 * {@code JPM_FOOJAY_URL} points it at a mirror.
 */
public final class Foojay {
    private Foojay() {}

    /** A downloadable JDK package. */
    public record Package(String id, String distribution, String javaVersion, int major, String filename,
                          String archiveType, String libc, String infoUri) {}

    /** Where the package lives and how to verify it. */
    public record Download(String url, String sha256) {}

    static String base() {
        String b = System.getenv("JPM_FOOJAY_URL");
        if (b == null || b.isBlank()) b = "https://api.foojay.io/disco/v3.0";
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    public static String os() {
        String n = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (n.contains("win")) return "windows";
        if (n.contains("mac") || n.contains("darwin")) return "macos";
        if (n.contains("linux")) return Files.exists(Path.of("/etc/alpine-release")) ? "alpine_linux" : "linux";
        return n.replaceAll("[^a-z]", "");
    }

    public static String arch() {
        String a = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        return switch (a) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64" -> "aarch64";
            case "x86", "i386", "i686" -> "x86";
            default -> a;
        };
    }

    private static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Finds the best package for a spec on this OS and architecture. */
    public static Package find(JavaSpec spec) {
        String dist = spec.installDistribution();
        String os = os();
        StringBuilder url = new StringBuilder(base()).append("/packages?distribution=").append(q(dist))
                .append("&architecture=").append(q(arch()))
                .append("&operating_system=").append(q(os.equals("alpine_linux") ? "linux" : os))
                .append("&package_type=jdk&release_status=ga&directly_downloadable=true");
        for (String t : archiveTypes()) url.append("&archive_type=").append(q(t));
        if (spec.version() != null) url.append("&version=").append(q(spec.version()));
        if (!spec.exact()) url.append("&latest=available");
        List<Package> all = parsePackages(Http.getString(url.toString()));
        Package best = choose(all, spec, os.equals("alpine_linux") ? "musl" : os.equals("linux") ? "glibc" : null);
        if (best == null) {
            throw new JpmException("No " + spec + " download found for " + os + "/" + arch()
                    + " (distribution " + dist + "). See `jpm java list --available " + dist + "`.");
        }
        return best;
    }

    static List<String> archiveTypes() {
        return os().equals("windows") ? List.of("zip") : List.of("tar.gz", "tgz", "zip");
    }

    /** Picks the highest matching version, preferring the right C library and no bundled JavaFX. */
    static Package choose(List<Package> all, JavaSpec spec, String libc) {
        Package best = null;
        for (Package p : all) {
            if (spec.major() != null && p.major != spec.major()) continue;
            if (spec.exact() && !JavaSpec.versionMatches(p.javaVersion, spec.version())) continue;
            if (libc != null && p.libc.equals(otherLibc(libc))) continue;
            if (best == null) { best = p; continue; }
            int c = Version.of(p.javaVersion).compareTo(Version.of(best.javaVersion));
            if (c > 0 || (c == 0 && archiveRank(p.archiveType) < archiveRank(best.archiveType))) best = p;
        }
        return best;
    }

    private static int archiveRank(String type) {
        return switch (type == null ? "" : type) {
            case "tar.gz", "tgz" -> 0;
            case "zip" -> 1;
            default -> 2;
        };
    }

    private static String otherLibc(String libc) {
        return libc.equals("musl") ? "glibc" : "musl";
    }

    @SuppressWarnings("unchecked")
    static List<Package> parsePackages(String json) {
        Map<String, Object> root = (Map<String, Object>) Jsonc.parse(json);
        List<Package> out = new ArrayList<>();
        Object result = root.get("result");
        if (!(result instanceof List<?> list)) return out;
        for (Object o : list) {
            Map<String, Object> p = (Map<String, Object>) o;
            if (Boolean.TRUE.equals(p.get("javafx_bundled"))) continue;
            Object major = p.get("jdk_version");
            Map<String, Object> links = (Map<String, Object>) p.get("links");
            if (!(major instanceof BigDecimal m) || links == null) continue;
            String libc = String.valueOf(p.getOrDefault("lib_c_type", ""));
            out.add(new Package((String) p.get("id"), (String) p.get("distribution"), (String) p.get("java_version"),
                    m.intValue(), (String) p.get("filename"), (String) p.get("archive_type"), libc, (String) links.get("pkg_info_uri")));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Download download(Package p) {
        Map<String, Object> root = (Map<String, Object>) Jsonc.parse(Http.getString(p.infoUri));
        List<Object> result = (List<Object>) root.get("result");
        if (result == null || result.isEmpty()) throw new JpmException("No download information for " + p.filename);
        Map<String, Object> info = (Map<String, Object>) result.get(0);
        String url = (String) info.get("direct_download_uri");
        String sum = (String) info.get("checksum");
        String type = (String) info.get("checksum_type");
        if (sum != null && sum.isBlank()) sum = null;
        if (sum == null && info.get("checksum_uri") instanceof String cu && !cu.isBlank()) {
            sum = Http.getString(cu).trim().split("\\s+")[0];
            type = "sha256";
        }
        if (sum != null && type != null && !type.equalsIgnoreCase("sha256")) sum = null;
        return new Download(url, sum == null ? null : sum.toLowerCase(Locale.ROOT));
    }

    /** Latest available versions of a distribution, newest first. */
    public static List<String> available(String distribution) {
        String os = os();
        StringBuilder url = new StringBuilder(base()).append("/packages?distribution=").append(q(distribution))
                .append("&architecture=").append(q(arch()))
                .append("&operating_system=").append(q(os.equals("alpine_linux") ? "linux" : os))
                .append("&package_type=jdk&release_status=ga&latest=available");
        for (String t : archiveTypes()) url.append("&archive_type=").append(q(t));
        TreeSet<String> versions = new TreeSet<>((a, b) -> Version.of(b).compareTo(Version.of(a)));
        for (Package p : parsePackages(Http.getString(url.toString()))) versions.add(p.javaVersion);
        return new ArrayList<>(versions);
    }
}
