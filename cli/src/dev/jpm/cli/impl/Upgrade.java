package dev.jpm.cli.impl;

import dev.jpm.cli.Main;

import dev.jpm.toolchain.Foojay;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.Http;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;
import dev.jpm.version.Version;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class Upgrade {
    private Upgrade() {}

    private static final java.util.regex.Pattern CANARY = java.util.regex.Pattern.compile("(.+)-([0-9a-f]{7,40})");

    static boolean isCanary(String version) {
        return CANARY.matcher(version).matches();
    }

    static String releaseOf(String version) {
        java.util.regex.Matcher m = CANARY.matcher(version);
        return m.matches() ? m.group(1) : version;
    }

    static String canaryBase() {
        String b = System.getenv("JPM_INSTALL_BASE");
        if (b != null && !b.isBlank()) return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        return "https://github.com/" + repo() + "/releases/download/canary";
    }

    static String repo() {
        String r = System.getenv("JPM_REPO");
        return r == null || r.isBlank() ? "java-package-manager/jpm" : r;
    }

    static String base(String version) {
        String b = System.getenv("JPM_INSTALL_BASE");
        if (b != null && !b.isBlank()) return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        return version == null
                ? "https://github.com/" + repo() + "/releases/latest/download"
                : "https://github.com/" + repo() + "/releases/download/" + version;
    }

    static String asset() {
        String os = Foojay.os();
        if (os.equals("alpine_linux")) os = "linux";
        String arch = Foojay.arch().equals("aarch64") ? "arm64" : Foojay.arch();
        return "jpm-" + os + "-" + arch + (os.equals("windows") ? ".exe" : "");
    }

    static String checksum(String sums, String asset) {
        for (String line : sums.split("\r?\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length >= 2 && (p[1].equals(asset) || p[1].equals("*" + asset))) return p[0].toLowerCase();
        }
        return null;
    }

    private static String fetch(String url) {
        if (url.startsWith("file:")) {
            Path p = Path.of(URI.create(url));
            if (!Files.isRegularFile(p)) throw new JpmException("Not found: " + url);
            return Fs.read(p);
        }
        return Http.getString(url);
    }

    private static String download(String url, Path target) {
        if (url.startsWith("file:")) {
            try {
                Files.copy(Path.of(URI.create(url)), target, StandardCopyOption.REPLACE_EXISTING);
                return dev.jpm.util.Hash.sha256(target);
            } catch (IOException e) {
                throw new JpmException("Could not read " + url + ": " + e.getMessage());
            }
        }
        return Http.download(url, target, "jpm");
    }

    public static int version() {
        Log.out("jpm " + Main.VERSION);
        String notice = updateNotice(Main.VERSION);
        if (notice != null) Log.info(notice);
        return 0;
    }

    static String updateNotice(String current) {
        if (Fs.offline() || System.getenv("JPM_NO_UPDATE_CHECK") != null) return null;
        return updateNotice(current, base(null) + "/VERSION", canaryBase() + "/VERSION");
    }

    static String updateNotice(String current, String latestUrl, String canaryUrl) {
        String latest = quickFetch(latestUrl);
        boolean onCanary = isCanary(current);
        String release = onCanary ? releaseOf(current) : current;
        java.util.List<String> lines = new java.util.ArrayList<>();
        try {
            if (latest != null && !isCanary(latest) && Version.of(latest).compareTo(Version.of(release)) > 0) {
                lines.add("jpm " + latest + " is available. Run `jpm upgrade`.");
            }
        } catch (RuntimeException ignored) {
        }
        if (onCanary) {
            String canary = quickFetch(canaryUrl);
            if (canary != null && !canary.equals(current) && isCanary(canary)) {
                lines.add("jpm " + canary + " is the newest canary build. Run `jpm upgrade --canary`.");
            }
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private static String quickFetch(String url) {
        try {
            if (url.startsWith("file:")) {
                Path p = Path.of(URI.create(url));
                return Files.isRegularFile(p) ? Fs.read(p).strip() : null;
            }
            java.net.http.HttpClient client = dev.jpm.util.Http.client(java.time.Duration.ofSeconds(2));
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(3)).header("User-Agent", "jpm").GET().build();
            java.net.http.HttpResponse<String> r = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            String body = r.body() == null ? "" : r.body().strip();
            return r.statusCode() == 200 && !body.isEmpty() && body.length() < 100 && !body.contains("\n") ? body : null;
        } catch (Exception e) {
            return null;
        }
    }

    static int run(List<String> args) {
        boolean check = args.remove("--check");
        boolean force = args.remove("--force");
        boolean canary = args.remove("--canary");
        if (args.size() > 1 || (canary && !args.isEmpty())) {
            throw new JpmException("Usage: jpm upgrade [version | --canary] [--check] [--force]");
        }
        String wanted = args.isEmpty() ? null : args.get(0).replaceFirst("^v", "");
        String current = Main.VERSION;

        String base;
        String latest;
        if (canary) {
            try {
                latest = fetch(canaryBase() + "/VERSION").strip();
            } catch (JpmException e) {
                throw new JpmException("Could not find the newest jpm canary build (" + e.getMessage() + ")");
            }
            base = base(latest);
            if (check) {
                Log.info(latest.equals(current) ? "jpm " + current + " is the newest canary build."
                        : "jpm " + latest + " is the newest canary build (you have " + current + "). Run `jpm upgrade --canary`.");
                return 0;
            }
            if (latest.equals(current) && !force) {
                Log.info("jpm " + current + " is already the newest canary build.");
                return 0;
            }
        } else {
            base = base(wanted);
            if (wanted != null) {
                latest = wanted;
            } else {
                try {
                    latest = fetch(base + "/VERSION").strip();
                } catch (JpmException e) {
                    throw new JpmException("Could not find the latest jpm release (" + e.getMessage() + ")");
                }
            }
            if (latest.equals(current) && !force) {
                Log.info("jpm " + current + " is already the " + (wanted == null ? "latest version" : "installed version") + ".");
                return 0;
            }
            boolean onCanary = isCanary(current) && wanted == null;
            int cmp = Version.of(latest).compareTo(Version.of(onCanary ? releaseOf(current) : current));
            if (onCanary && cmp <= 0 && !force) {
                Log.info("jpm " + current + " is a canary build after the latest release (" + latest + "). Run `jpm upgrade --canary`"
                        + " for newer canary builds, or `jpm upgrade " + latest + "` to go back to the release.");
                return 0;
            }
            if (check) {
                if (cmp > 0) Log.info("jpm " + latest + " is available (you have " + current + "). Run `jpm upgrade`.");
                else Log.info("jpm " + current + " is up to date" + (cmp < 0 ? " (newer than the latest release, " + latest + ")" : "") + ".");
                return 0;
            }
            if (cmp == 0 && !force && !(wanted != null && !wanted.equals(current))) {
                Log.info("jpm " + current + " is already the " + (wanted == null ? "latest version" : "installed version") + ".");
                return 0;
            }
            if (cmp < 0 && wanted == null && !force) {
                Log.info("jpm " + current + " is newer than the latest release (" + latest + "); nothing to do.");
                return 0;
            }
        }

        Path self = Shims.self();
        if (self == null) {
            throw new JpmException("`jpm upgrade` replaces the native jpm binary, but this is the jar build. Update the source"
                    + " (git pull) and run ./bootstrap.sh, or install the binary with the install script.");
        }
        try {
            self = self.toRealPath();
        } catch (IOException ignored) {
        }

        String asset = asset();
        Log.step("Upgrading", "jpm " + current + " -> " + latest + Log.dim(" (" + asset + ")"));
        String expected = checksum(fetch(base + "/SHA256SUMS"), asset);
        if (expected == null) throw new JpmException("The " + latest + " release has no " + asset + " for this platform");
        Path tmp = self.resolveSibling(self.getFileName() + ".new");
        String actual = download(base + "/" + asset, tmp);
        if (!expected.equals(actual)) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw new JpmException("Checksum mismatch for " + asset + " (expected " + expected + ", got " + actual + "); nothing was changed");
        }
        tmp.toFile().setExecutable(true);
        replace(self, tmp);

        Exec.Result r = Exec.capture(null, List.of(self.toString(), "jvm", "setup"));
        if (!r.ok()) Log.warn("could not refresh the java shims: " + (r.err() + r.out()).strip());
        Exec.Result v = Exec.capture(null, List.of(self.toString(), "--version"));
        Log.step("Upgraded", (v.ok() ? v.out().strip() : "jpm " + latest) + Log.dim(" (" + self + ")"));
        return 0;
    }

    private static void replace(Path self, Path fresh) {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        try {
            if (win) {
                Path old = self.resolveSibling(self.getFileName() + ".old");
                try {
                    Files.deleteIfExists(old);
                } catch (IOException ignored) {
                }
                Files.move(self, old, StandardCopyOption.REPLACE_EXISTING);
                Files.move(fresh, self);
            } else {
                Files.move(fresh, self, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            throw new JpmException("Could not replace " + self + ": " + e.getMessage()
                    + (Files.isWritable(self.getParent()) ? "" : " (no write access to " + self.getParent() + ")"));
        }
    }
}
