package dev.jpm.cli.impl;

import dev.jpm.Project;
import dev.jpm.log.Log;
import dev.jpm.maven.Credentials;
import dev.jpm.toolchain.JavaSpec;
import dev.jpm.toolchain.Jdk;
import dev.jpm.toolchain.Toolchain;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.Http;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DoctorCommand extends Command {
    private int errors, warnings;

    DoctorCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "doctor";
    }

    @Override
    List<Usage> usages() {
        return List.of(new Usage(Group.MAINTENANCE, "doctor", "Check this machine's jpm setup and say what to fix",
                "Check this machine, not the project: PATH order, the shims, the shell setup, installed JDKs and the default one,"
                        + " jpm's home and caches, and whether the Maven repositories answer; prints what to fix and exits 1 on an error."
                        + " `--offline` skips the network"));
    }

    @Override
    int run(List<String> args) {
        noMoreFlags(args);
        if (!args.isEmpty()) throw new JpmException("Usage: jpm doctor", 2);
        Log.out(Log.bold("jpm " + dev.jpm.BuildConfig.VERSION) + Log.dim("  " + Shims.selfOrJar()));
        section("PATH and shims");
        path();
        section("JDKs");
        jdks();
        section("jpm home");
        home();
        section("Repositories");
        repositories();
        Log.out("");
        if (errors == 0 && warnings == 0) Log.step("Checked", "everything looks good");
        else if (errors == 0) Log.step("Checked", warnings + (warnings == 1 ? " warning" : " warnings") + ", no errors");
        else Log.error(errors + (errors == 1 ? " problem" : " problems") + " to fix" + (warnings == 0 ? "" : ", and " + warnings
                + (warnings == 1 ? " warning" : " warnings")));
        return errors == 0 ? 0 : 1;
    }

    private void section(String title) {
        Log.out("");
        Log.out(Log.bold(title));
    }

    private void ok(String msg) {
        Log.out("  ok    " + msg);
    }

    private void warn(String msg, String fix) {
        warnings++;
        Log.out("  warn  " + msg);
        if (fix != null) Log.out(Log.dim("        fix: " + fix));
    }

    private void fail(String msg, String fix) {
        errors++;
        Log.out("  error " + msg);
        if (fix != null) Log.out(Log.dim("        fix: " + fix));
    }

    private void path() {
        Path bin = Shims.bin().toAbsolutePath().normalize();
        List<Path> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(java.io.File.pathSeparator)) if (!d.isEmpty()) dirs.add(Path.of(d).toAbsolutePath().normalize());
        int binAt = dirs.indexOf(bin);
        if (binAt < 0) {
            warn(bin + " is not on PATH, so `jpm` and the java shims are not found by name",
                    ShellSetup.windows() ? "add it to PATH in the system settings" : "add `" + ShellSetup.sourceLine() + "` to your shell's rc file, or run `jpm java setup`");
        } else {
            ok(bin + " is on PATH");
        }
        if (!Shims.installed()) {
            warn("no java shims in " + bin + ", so `java` outside jpm does not follow the project's \"java\"", "run `jpm java setup`");
        } else {
            List<String> missing = new ArrayList<>();
            for (String t : Shims.TOOLS) {
                Path link = bin.resolve(ShellSetup.windows() ? t + ".exe" : t);
                if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) missing.add(t);
                else if (Files.isSymbolicLink(link) && !Files.exists(link)) missing.add(t + " (broken link)");
            }
            if (missing.isEmpty()) ok("the java shims are installed");
            else warn("shims missing: " + String.join(", ", missing), "run `jpm java setup`");
            if (binAt >= 0) {
                for (int i = 0; i < binAt; i++) {
                    Path java = dirs.get(i).resolve(ShellSetup.windows() ? "java.exe" : "java");
                    if (Files.isExecutable(java) && !Files.isDirectory(java)) {
                        warn(java + " comes before the jpm shims on PATH, so `java` runs it instead",
                                "put " + bin + " first on PATH (`jpm java setup` writes that into your shell's rc file)");
                        break;
                    }
                }
            }
        }
        Path jpm = Exec.which("jpm");
        Path self = Shims.self();
        if (jpm != null && self != null) {
            try {
                if (!Files.isSameFile(jpm, self) && !jpm.toRealPath().equals(self.toRealPath())) {
                    warn("`jpm` on PATH is " + jpm + ", not this jpm (" + self + ")", "remove the other one, or put " + bin + " first on PATH");
                }
            } catch (IOException ignored) {
            }
        }
        if (!ShellSetup.windows() && System.getenv("JPM_NO_MODIFY_PATH") == null && !ShellSetup.configured()) {
            warn("your shell's rc files do not load " + ShellSetup.envFile(), "run `jpm java setup`");
        }
    }

    private void jdks() {
        List<Jdk> all = Jdk.discover();
        if (all.isEmpty()) {
            warn("no JDK found", "run `jpm java install 21` (projects install theirs on first use)");
        } else {
            for (Jdk j : all) ok(j.id() + "  " + Log.dim(j.home() + (j.managed() ? " (installed by jpm)" : "")));
        }
        Path root = Jdk.managedRoot();
        if (Files.isDirectory(root)) {
            Set<Path> good = new LinkedHashSet<>();
            for (Jdk j : all) if (j.managed()) good.add(j.home().toAbsolutePath().normalize());
            try (var s = Files.list(root)) {
                for (Path d : s.filter(Files::isDirectory).sorted().toList()) {
                    Path n = d.toAbsolutePath().normalize();
                    if (good.stream().noneMatch(g -> g.startsWith(n))) {
                        fail(d + " is not a working JDK (an interrupted install?)", "delete it, or run `jpm java remove " + d.getFileName() + "`");
                    }
                }
            } catch (IOException e) {
                fail("cannot read " + root + ": " + e.getMessage(), null);
            }
        }
        String spec = Shims.defaultSpec();
        if (spec != null) {
            Jdk j;
            try {
                j = Toolchain.find(JavaSpec.parse(spec));
            } catch (JpmException e) {
                j = null;
            }
            if (j == null) fail("the default java \"" + spec + "\" (from `jpm jvm use`) is not installed", "run `jpm java install " + spec + "`");
            else ok("the default java is " + j.id());
        }
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isBlank() && Jdk.probe(Path.of(jh)) == null) {
            warn("JAVA_HOME is " + jh + ", which is not a JDK", "point it at a JDK, or unset it");
        }
    }

    private void home() {
        Path home = Fs.home();
        if (!Files.isDirectory(home)) {
            ok(home + " does not exist yet (it is created on first use)");
            return;
        }
        if (!Files.isWritable(home)) fail(home + " is not writable", "fix its permissions, or set JPM_HOME to a folder you own");
        else ok(home + " is writable");
        int parts = 0;
        Path repo = home.resolve("repository");
        if (Files.isDirectory(repo)) {
            try (var s = Files.walk(repo)) {
                parts = (int) s.filter(p -> p.getFileName().toString().endsWith(".part")).count();
            } catch (IOException | java.io.UncheckedIOException ignored) {
            }
        }
        if (parts > 0) warn(parts + " unfinished downloads in " + repo, "run `jpm cache clean repository`, or delete the .part files");
        long total = 0;
        for (String n : CacheCommand.FOLDERS.keySet()) if (Files.isDirectory(home.resolve(n))) total += CacheCommand.size(home.resolve(n));
        ok("caches use " + CacheCommand.human(total) + Log.dim(" (`jpm cache size`)"));
        try {
            long free = Files.getFileStore(home).getUsableSpace();
            if (free < 1024L * 1024 * 1024) warn("only " + CacheCommand.human(free) + " free on the disk of " + home, "free some space, or run `jpm cache clean`");
            else ok(CacheCommand.human(free) + " free on its disk");
        } catch (IOException ignored) {
        }
    }

    private void repositories() {
        List<String> repos = new ArrayList<>();
        String central = System.getProperty("jpm.mavenCentral", System.getenv("JPM_MAVEN_CENTRAL"));
        repos.add(central != null && !central.isBlank() ? central : "https://repo.maven.apache.org/maven2/");
        try {
            Project p = Project.find(cwd);
            for (String r : p.root.repositories) if (!repos.contains(r)) repos.add(r);
        } catch (JpmException ignored) {
        }
        if (Fs.offline()) {
            ok("offline: not checked");
            return;
        }
        String proxy = System.getenv("HTTPS_PROXY") != null ? System.getenv("HTTPS_PROXY") : System.getenv("https_proxy");
        if (proxy != null && !proxy.isBlank()) ok("HTTPS goes through a proxy" + Log.dim(" (HTTPS_PROXY)"));
        for (String r : repos) {
            String url = r.endsWith("/") ? r : r + "/";
            if (url.startsWith("file:")) {
                Path dir = Path.of(URI.create(url));
                if (Files.isDirectory(dir)) ok(url);
                else fail(url + " does not exist", "fix \"repositories\" in project.jsonc");
                continue;
            }
            String auth = Credentials.authorization(url);
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("User-Agent", "jpm")
                        .method("HEAD", HttpRequest.BodyPublishers.noBody());
                if (auth != null) b.header("Authorization", auth);
                HttpResponse<Void> resp = (auth == null ? Http.client(Duration.ofSeconds(10)) : Http.clientWithoutRedirects())
                        .send(b.build(), HttpResponse.BodyHandlers.discarding());
                int s = resp.statusCode();
                String p = Credentials.prefix(url);
                if (s == 401 || s == 403) {
                    if (auth == null) warn(url + " answered HTTP " + s + Log.dim(" (fine if it only refuses listings)"),
                            "if it needs a login, set " + p + "_USERNAME and " + p + "_PASSWORD (or " + p + "_TOKEN)");
                    else fail(url + " refuses the credentials in " + p + "_*", "check " + p + "_USERNAME and _PASSWORD (or _TOKEN)");
                } else if (s >= 500) {
                    warn(url + " answered HTTP " + s, "try again later");
                } else {
                    ok(url + Log.dim(" (HTTP " + s + (auth != null ? ", with credentials" : "") + ")"));
                }
            } catch (IOException e) {
                fail(url + " cannot be reached: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
                        "check the network and proxy settings (HTTPS_PROXY, NO_PROXY), or work with --offline");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
