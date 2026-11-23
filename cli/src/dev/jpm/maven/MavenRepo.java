package dev.jpm.maven;

import dev.jpm.log.Log;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.version.Version;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads from Maven repositories into the shared cache {@code ~/.jpm/repository}.
 */
public final class MavenRepo {
    public static final String CENTRAL = "https://repo.maven.apache.org/maven2/";
    private static final Duration METADATA_TTL = Duration.ofHours(24);

    private final List<String> repos;
    private final Path cache;
    private final boolean offline;
    private boolean refreshMetadata;
    private final HttpClient http;
    private final ConcurrentHashMap<String, List<Version>> versionsMemo = new ConcurrentHashMap<>();

    /**
     * Maven Central (or {@code JPM_MAVEN_CENTRAL}), then the extra repositories.
     */
    public MavenRepo(List<String> extra) {
        this(withCentral(extra), true);
    }

    /**
     * Exactly these repositories, without Maven Central: for tests.
     */
    MavenRepo(List<String> repositories, boolean exact) {
        List<String> r = new ArrayList<>();
        for (String e : repositories) r.add(slash(e));
        this.repos = new java.util.concurrent.CopyOnWriteArrayList<>(r);
        this.cache = Fs.home().resolve("repository");
        this.offline = Fs.offline();
        this.http = dev.jpm.util.Http.client();
    }

    /**
     * Adds repositories that a Git dependency's manifest lists, after those already searched, so that the
     * dependencies it brings resolve from where it says they are.
     */
    public void addRepositories(List<String> more) {
        for (String m : more) {
            String r = slash(m);
            if (!repos.contains(r)) {
                repos.add(r);
                versionsMemo.clear();
            }
        }
    }

    private static List<String> withCentral(List<String> extra) {
        List<String> r = new ArrayList<>();
        String central = System.getProperty("jpm.mavenCentral", System.getenv("JPM_MAVEN_CENTRAL"));
        r.add(central != null && !central.isBlank() ? central : CENTRAL);
        r.addAll(extra);
        return r;
    }

    private static String slash(String s) {
        return s.endsWith("/") ? s : s + "/";
    }

    /**
     * Whether to fetch {@code maven-metadata.xml} again even when the cached copy is fresh.
     */
    public void setRefreshMetadata(boolean b) {
        this.refreshMetadata = b;
    }

    /**
     * The download cache, {@code ~/.jpm/repository}.
     */
    public Path cacheDir() {
        return cache;
    }

    /**
     * The path of a file in Maven's repository layout.
     */
    public static String path(String group, String artifact, String version, String classifier, String ext) {
        return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version
                + (classifier == null || classifier.isEmpty() ? "" : "-" + classifier) + "." + ext;
    }

    /**
     * Where a file is in the cache, whether it is there or not.
     */
    public Path local(String group, String artifact, String version, String classifier, String ext) {
        return cache.resolve(path(group, artifact, version, classifier, ext));
    }

    private static final java.util.regex.Pattern TIMESTAMPED = java.util.regex.Pattern.compile("(.+)-\\d{8}\\.\\d{6}-\\d+");

    /**
     * 0.1-SNAPSHOT for the timestamped snapshot 0.1-20240720.200737-2; null for other versions.
     */
    static String snapshotBase(String version) {
        java.util.regex.Matcher m = TIMESTAMPED.matcher(version);
        return m.matches() ? m.group(1) + "-SNAPSHOT" : null;
    }

    /**
     * Why the last {@link #fetch} of a file found nothing although a repository could not be asked, by cache path.
     */
    private final ConcurrentHashMap<Path, String> failures = new ConcurrentHashMap<>();

    /**
     * Fetches a file into the cache, returning null if no repository has it. "Not found" is only remembered
     * (in a {@code .missing} file) when every repository answered 404 or 410: a rate limit, a server error,
     * a refusal or a network error is not proof the file does not exist, so the next run asks again.
     */
    public Path fetch(String group, String artifact, String version, String classifier, String ext) {
        Path local = local(group, artifact, version, classifier, ext);
        if (Files.exists(local)) return local;
        Path missing = local.resolveSibling(local.getFileName() + ".missing");
        String searched = String.join("\n", repos);
        if (Files.exists(missing) && !refreshMetadata && Fs.read(missing).equals(searched)) return null;
        String rel = path(group, artifact, version, classifier, ext);
        boolean snapshot = version.endsWith("-SNAPSHOT");
        List<String> problems = new ArrayList<>();
        for (String repo : repos) {
            Result r = download(repo + rel, local);
            if (r.found()) return verified(repo + rel, local);
            if (r.problem() != null) problems.add(r.problem());
            if (snapshot) {
                String timestamped = snapshotVersion(repo, group, artifact, version, classifier, ext, problems);
                String file = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + timestamped
                        + (classifier == null || classifier.isEmpty() ? "" : "-" + classifier) + "." + ext;
                if (timestamped != null) {
                    Result t = download(repo + file, local);
                    if (t.found()) return verified(repo + file, local);
                    if (t.problem() != null) problems.add(t.problem());
                }
            }
            String base = snapshotBase(version);
            if (base != null) {
                String file = group.replace('.', '/') + "/" + artifact + "/" + base + "/" + artifact + "-" + version
                        + (classifier == null || classifier.isEmpty() ? "" : "-" + classifier) + "." + ext;
                Result t = download(repo + file, local);
                if (t.found()) return verified(repo + file, local);
                if (t.problem() != null) problems.add(t.problem());
            }
        }
        if (problems.isEmpty()) {
            failures.remove(local);
            if (!offline) Fs.write(missing, searched);
        } else {
            failures.put(local, String.join("; ", problems));
            Log.debug("not caching a miss for " + rel + ": " + String.join("; ", problems));
        }
        return null;
    }

    /**
     * The checksums a repository may publish next to a file, strongest first (ADR 0017).
     */
    private static final List<String> CHECKSUMS = List.of("sha512", "sha256", "sha1");

    /**
     * {@code local}, just downloaded from {@code url}, checked against the strongest checksum the repository publishes
     * next to it; a file that does not match is deleted and fails. A repository that publishes none is trusted, and
     * the lockfile then pins what was downloaded.
     */
    private Path verified(String url, Path local) {
        for (String algorithm : CHECKSUMS) {
            Path sum = local.resolveSibling(local.getFileName() + "." + algorithm + ".download");
            Result r = download(url + "." + algorithm, sum);
            if (!r.found()) continue;
            String published = Fs.read(sum).strip().split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
            try {
                Files.deleteIfExists(sum);
            } catch (IOException ignored) {
            }
            if (!published.matches("[0-9a-f]{40,128}")) {
                Log.debug(url + "." + algorithm + " is not a checksum; ignoring it");
                continue;
            }
            String actual = dev.jpm.util.Hash.of(algorithm, local);
            if (!actual.equals(published)) {
                try {
                    Files.deleteIfExists(local);
                } catch (IOException ignored) {
                }
                throw new JpmException("Checksum mismatch for " + url + ":\n  " + algorithm + " published: " + published
                        + "\n  downloaded:       " + actual).withHint("the download or the repository is corrupt or was tampered with;"
                                + " nothing was kept. Try again, and report it to the repository if it stays");
            }
            Log.debug("verified " + url + " (" + algorithm + ")");
            return local;
        }
        Log.debug(url + " has no published checksum");
        return local;
    }

    /**
     * The timestamped version a snapshot repository publishes for {@code version} (e.g. 1.0-SNAPSHOT is
     * 1.0-20250101.120000-3), for the given extension and classifier, or null if the repository has none.
     */
    String snapshotVersion(String repo, String group, String artifact, String version, String classifier, String ext,
                           List<String> problems) {
        String rel = group.replace('.', '/') + "/" + artifact + "/" + version + "/maven-metadata.xml";
        Path meta = cache.resolve(rel).resolveSibling("maven-metadata-" + Integer.toHexString(repo.hashCode()) + ".xml");
        Result r = download(repo + rel, meta);
        if (r.problem() != null) problems.add(r.problem());
        if (!r.found()) return null;
        return snapshotVersion(Fs.read(meta), version, classifier, ext);
    }

    static String snapshotVersion(String metadata, String version, String classifier, String ext) {
        String cls = classifier == null ? "" : classifier;
        Matcher sv = Pattern.compile("<snapshotVersion>(.*?)</snapshotVersion>", Pattern.DOTALL).matcher(metadata);
        String found = null;
        while (sv.find()) {
            String block = sv.group(1);
            String e = tag(block, "extension"), c = tag(block, "classifier"), v = tag(block, "value");
            if (v != null && ext.equals(e) && cls.equals(c == null ? "" : c)) found = v;
        }
        if (found != null) return found;
        String ts = tag(metadata, "timestamp"), build = tag(metadata, "buildNumber");
        if (ts != null && build != null) return version.substring(0, version.length() - "SNAPSHOT".length()) + ts + "-" + build;
        return null;
    }

    /**
     * For tests: {@link #snapshotVersion(String, String, String, String)}.
     */
    public static String snapshotVersionForTests(String metadata, String version, String classifier, String ext) {
        return snapshotVersion(metadata, version, classifier, ext);
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    /**
     * A file in the cache, downloaded first if needed; fails when no repository has it (ADR 0005).
     */
    public Path require(String group, String artifact, String version, String classifier, String ext) {
        Path p = fetch(group, artifact, version, classifier, ext);
        if (p == null) {
            String failure = failures.get(local(group, artifact, version, classifier, ext));
            if (failure != null) {
                throw new JpmException("Could not download " + group + ":" + artifact + ":" + version
                        + (classifier != null ? ":" + classifier : "") + " (" + ext + "): " + failure)
                        .withHint("this looks like a network or repository problem, not a missing artifact: try again");
            }
            throw new JpmException("Could not find " + group + ":" + artifact + ":" + version
                    + (classifier != null ? ":" + classifier : "") + " (" + ext + ") in " + String.join(", ", repos))
                    .withHint("if it is published elsewhere, add that repository: `jpm add -r <url> " + group + ":" + artifact
                            + "`, or list it in \"repositories\" in project.jsonc");
        }
        return p;
    }

    /**
     * When a version was published (ADR 0018): the {@code Last-Modified} time the first repository that has its POM
     * sends for it, or the POM's modification time in a {@code file:} repository. Kept in the cache next to the POM.
     * Empty when no repository says; throws when none could be asked.
     */
    public Optional<Instant> released(String group, String artifact, String version) {
        Path stamp = local(group, artifact, version, null, "pom").resolveSibling(artifact + "-" + version + ".pom.released");
        if (Files.isRegularFile(stamp)) {
            String t = Fs.read(stamp).strip();
            return t.equals("unknown") ? Optional.empty() : Optional.of(Instant.parse(t));
        }
        String rel = path(group, artifact, version, null, "pom");
        List<String> problems = new ArrayList<>();
        for (String repo : repos) {
            Released r = lastModified(repo + rel);
            if (r.problem() != null) {
                problems.add(r.problem());
                continue;
            }
            if (!r.found()) continue;
            Fs.write(stamp, r.time() == null ? "unknown" : r.time().toString());
            return Optional.ofNullable(r.time());
        }
        if (!problems.isEmpty()) {
            throw new JpmException("Could not learn when " + group + ":" + artifact + " " + version + " was released: " + String.join("; ", problems));
        }
        return Optional.empty();
    }

    private record Released(boolean found, Instant time, String problem) {}

    private Released lastModified(String url) {
        if (url.startsWith("file:")) {
            try {
                Path src = Path.of(URI.create(url));
                if (!Files.isRegularFile(src)) return new Released(false, null, null);
                return new Released(true, Files.getLastModifiedTime(src).toInstant().truncatedTo(ChronoUnit.SECONDS), null);
            } catch (IOException | IllegalArgumentException e) {
                return new Released(false, null, url + ": " + e.getMessage());
            }
        }
        if (offline) return new Released(false, null, "offline");
        Log.debug("HEAD " + url);
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).header("User-Agent", "jpm")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody());
            String auth = Credentials.authorization(url);
            if (auth != null) b.header("Authorization", auth);
            HttpResponse<Void> resp = http.send(b.build(), HttpResponse.BodyHandlers.discarding());
            int status = resp.statusCode();
            if (status == 404 || status == 410) return new Released(false, null, null);
            if (status != 200) return new Released(false, null, url + " answered HTTP " + status);
            Instant time = resp.headers().firstValue("Last-Modified").map(MavenRepo::httpDate).orElse(null);
            return new Released(true, time, null);
        } catch (IOException e) {
            return new Released(false, null, url + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JpmException("Interrupted");
        }
    }

    private static Instant httpDate(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * All published versions of a module, from maven-metadata.xml.
     */
    public List<Version> versions(String group, String artifact) {
        return versionsMemo.computeIfAbsent(group + ":" + artifact, k -> loadVersions(group, artifact));
    }

    private List<Version> loadVersions(String group, String artifact) {
        List<Version> out = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < repos.size(); i++) {
            String repo = repos.get(i);
            Path local = cache.resolve(group.replace('.', '/') + "/" + artifact + "/maven-metadata-" + i + ".xml");
            boolean fresh = false;
            try {
                fresh = Files.exists(local) && !refreshMetadata
                        && Files.getLastModifiedTime(local).toInstant().plus(METADATA_TTL).isAfter(Instant.now());
            } catch (IOException ignored) {
            }
            if (!fresh && !offline) {
                Result r = download(repo + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml", local);
                if (r.problem() != null) problems.add(r.problem());
            }
            if (!Files.exists(local)) continue;
            Matcher m = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>").matcher(Fs.read(local));
            while (m.find()) {
                Version v = Version.of(m.group(1));
                if (!out.contains(v)) out.add(v);
            }
        }
        if (out.isEmpty()) {
            if (!problems.isEmpty()) {
                throw new JpmException("Could not list the versions of " + group + ":" + artifact + ": " + String.join("; ", problems)
                        + "\nThis looks like a network or repository problem, not a missing artifact: try again.");
            }
            throw new JpmException("Could not find " + group + ":" + artifact + " in " + String.join(", ", repos)
                    + (offline ? " (offline)" : ""));
        }
        out.sort(null);
        return out;
    }

    /**
     * The outcome of asking one repository for one file: {@code found}, or not found with {@code problem}
     * null when the repository said so (404 or 410), or not found with a problem when it could not tell
     * (refused, rate limited, failing, unreachable).
     */
    record Result(boolean found, String problem) {
        static final Result FOUND = new Result(true, null);
        static final Result ABSENT = new Result(false, null);
    }

    private HttpResponse<InputStream> send(String url, String auth) throws IOException, InterruptedException {
        if (auth == null) return http.send(request(url, null), HttpResponse.BodyHandlers.ofInputStream());
        HttpClient manual = dev.jpm.util.Http.clientWithoutRedirects();
        String at = url;
        for (int hops = 0; ; hops++) {
            HttpResponse<InputStream> resp = manual.send(request(at, auth), HttpResponse.BodyHandlers.ofInputStream());
            int s = resp.statusCode();
            if (s != 301 && s != 302 && s != 303 && s != 307 && s != 308 || hops == 10) return resp;
            String location = resp.headers().firstValue("Location").orElse(null);
            resp.body().close();
            if (location == null) return resp;
            String next = URI.create(at).resolve(location).toString();
            if (!Credentials.sameOrigin(url, next)) auth = null;
            Log.debug("redirected to " + next + (auth == null ? " (without credentials)" : ""));
            if (auth == null) return http.send(request(next, null), HttpResponse.BodyHandlers.ofInputStream());
            at = next;
        }
    }

    private static HttpRequest request(String url, String auth) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).header("User-Agent", "jpm").GET();
        if (auth != null) b.header("Authorization", auth);
        return b.build();
    }

    private Result download(String url, Path target) {
        if (url.startsWith("file:")) {
            try {
                Path src = Path.of(URI.create(url));
                if (!Files.isRegularFile(src)) return Result.ABSENT;
                Files.createDirectories(target.getParent());
                Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
                Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return Result.FOUND;
            } catch (IOException | IllegalArgumentException e) {
                throw new JpmException("Could not read " + url + ": " + e.getMessage());
            }
        }
        if (offline) return Result.ABSENT;
        Log.debug("GET " + url);
        try {
            String auth = Credentials.authorization(url);
            HttpResponse<InputStream> resp = send(url, auth);
            int status = resp.statusCode();
            if (status == 200) {
                Files.createDirectories(target.getParent());
                Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
                try (InputStream in = resp.body()) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    Files.deleteIfExists(tmp);
                    throw e;
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return Result.FOUND;
            }
            resp.body().close();
            if (status == 404 || status == 410) return Result.ABSENT;
            if ((status == 401 || status == 403) && Credentials.prefix(url) != null) {
                String p = Credentials.prefix(url);
                return new Result(false, url + " answered HTTP " + status + (auth == null
                        ? "; if the repository needs a login, set " + p + "_USERNAME and " + p + "_PASSWORD (or " + p + "_TOKEN)"
                        : "; check " + p + "_USERNAME and _PASSWORD (or _TOKEN)"));
            }
            return new Result(false, url + " answered HTTP " + status);
        } catch (IOException e) {
            Log.debug("download failed: " + e);
            return new Result(false, url + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JpmException("Interrupted");
        }
    }
}
