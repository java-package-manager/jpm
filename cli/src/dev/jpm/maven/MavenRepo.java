package dev.jpm.maven;

import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads from Maven repositories into the shared cache {@code ~/.jpm/repository}. */
public final class MavenRepo {
    public static final String CENTRAL = "https://repo.maven.apache.org/maven2/";
    private static final Duration METADATA_TTL = Duration.ofHours(24);

    private final List<String> repos;
    private final Path cache;
    private final boolean offline;
    private boolean refreshMetadata;
    private final HttpClient http;
    private final ConcurrentHashMap<String, List<Version>> versionsMemo = new ConcurrentHashMap<>();

    /** Maven Central (or {@code JPM_MAVEN_CENTRAL}), then the extra repositories. */
    public MavenRepo(List<String> extra) {
        this(withCentral(extra), true);
    }

    /** Exactly these repositories, without Maven Central: for tests. */
    MavenRepo(List<String> repositories, boolean exact) {
        List<String> r = new ArrayList<>();
        for (String e : repositories) r.add(slash(e));
        this.repos = r;
        this.cache = Fs.home().resolve("repository");
        this.offline = Fs.offline();
        this.http = dev.jpm.util.Http.client();
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

    /** Whether to fetch {@code maven-metadata.xml} again even when the cached copy is fresh. */
    public void setRefreshMetadata(boolean b) {
        this.refreshMetadata = b;
    }

    /** The download cache, {@code ~/.jpm/repository}. */
    public Path cacheDir() {
        return cache;
    }

    /** The path of a file in Maven's repository layout. */
    public static String path(String group, String artifact, String version, String classifier, String ext) {
        return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version
                + (classifier == null || classifier.isEmpty() ? "" : "-" + classifier) + "." + ext;
    }

    /** Where a file is in the cache, whether it is there or not. */
    public Path local(String group, String artifact, String version, String classifier, String ext) {
        return cache.resolve(path(group, artifact, version, classifier, ext));
    }

    private static final java.util.regex.Pattern TIMESTAMPED = java.util.regex.Pattern.compile("(.+)-\\d{8}\\.\\d{6}-\\d+");

    /** 0.1-SNAPSHOT for the timestamped snapshot 0.1-20240720.200737-2; null for other versions. */
    static String snapshotBase(String version) {
        java.util.regex.Matcher m = TIMESTAMPED.matcher(version);
        return m.matches() ? m.group(1) + "-SNAPSHOT" : null;
    }

    /** Why the last {@link #fetch} of a file found nothing although a repository could not be asked, by cache path. */
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
            if (r.found()) return local;
            if (r.problem() != null) problems.add(r.problem());
            if (snapshot) {
                String timestamped = snapshotVersion(repo, group, artifact, version, classifier, ext, problems);
                String file = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + timestamped
                        + (classifier == null || classifier.isEmpty() ? "" : "-" + classifier) + "." + ext;
                if (timestamped != null) {
                    Result t = download(repo + file, local);
                    if (t.found()) return local;
                    if (t.problem() != null) problems.add(t.problem());
                }
            }
            String base = snapshotBase(version);
            if (base != null) {
                String file = group.replace('.', '/') + "/" + artifact + "/" + base + "/" + artifact + "-" + version
                        + (classifier == null || classifier.isEmpty() ? "" : "-" + classifier) + "." + ext;
                Result t = download(repo + file, local);
                if (t.found()) return local;
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

    /** For tests: {@link #snapshotVersion(String, String, String, String)}. */
    public static String snapshotVersionForTests(String metadata, String version, String classifier, String ext) {
        return snapshotVersion(metadata, version, classifier, ext);
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    /** A file in the cache, downloaded first if needed; fails when no repository has it (ADR 0005). */
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

    /** All published versions of a module, from maven-metadata.xml. */
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
                        && Files.getLastModifiedTime(local).toInstant().plus(METADATA_TTL).isAfter(java.time.Instant.now());
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
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .header("User-Agent", "jpm")
                    .GET().build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
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
