package dev.jpm.git;

import dev.jpm.DepKey;
import dev.jpm.log.Log;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A cached bare mirror of a Git repository, driven through the system {@code git} command so SSH keys
 * and credential helpers work as usual.
 */
public final class GitRepo {
    private static final Map<String, GitRepo> OPEN = new LinkedHashMap<>();

    private final DepKey key;
    private final String url;
    private final Path mirror;
    private boolean fetched;

    private GitRepo(DepKey repoKey) {
        this.key = repoKey;
        this.url = repoKey.gitUrl();
        this.mirror = Fs.home().resolve("git").resolve(repoKey.host()).resolve(repoKey.group()).resolve(repoKey.artifact() + ".git");
    }

    /**
     * The repository of a Git dependency, one per repository for the whole run.
     */
    public static synchronized GitRepo of(DepKey key) {
        DepKey rk = key.repoKey();
        return OPEN.computeIfAbsent(rk.toString(), k -> new GitRepo(rk));
    }

    /**
     * The repository's key, without a module.
     */
    public DepKey key() {
        return key;
    }

    /**
     * Fails with a clear message when {@code git} is not on PATH.
     */
    public static void requireGit() {
        if (Exec.which("git") == null) {
            throw new JpmException("Git dependencies need the `git` command, which was not found on PATH");
        }
    }

    /**
     * Clones or updates the mirror. Called at most once per process. The mirror always fetches from the URL the key
     * names now, so a repository that moved (or another {@code JPM_GIT_BASE_<HOST>}) is followed.
     */
    public synchronized void fetch() {
        if (fetched) return;
        requireGit();
        if (Fs.offline()) {
            if (!Files.isDirectory(mirror)) throw new JpmException("Offline and " + key + " is not cached");
            fetched = true;
            return;
        }
        if (!Files.isDirectory(mirror)) {
            Log.step("Cloning", url);
            Fs.mkdirs(mirror.getParent());
            Exec.Result r = Exec.capture(mirror.getParent(), Exec.cmd("git", "clone", "--mirror", "--quiet", url, mirror.getFileName().toString()),
                    Map.of("GIT_TERMINAL_PROMPT", "0"));
            if (!r.ok()) {
                Fs.deleteTree(mirror);
                throw new JpmException("Could not clone " + url + ":\n" + r.err().strip());
            }
        } else {
            Log.step("Fetching", url);
            Exec.Result set = Exec.capture(mirror, Exec.cmd("git", "remote", "set-url", "origin", url));
            if (!set.ok()) throw new JpmException("Could not point " + mirror + " at " + url + ":\n" + set.err().strip());
            Exec.Result r = Exec.capture(mirror, Exec.cmd("git", "fetch", "--quiet", "--prune", "--prune-tags", "origin"),
                    Map.of("GIT_TERMINAL_PROMPT", "0"));
            if (!r.ok()) throw new JpmException("Could not fetch " + url + ":\n" + r.err().strip());
        }
        fetched = true;
    }

    /**
     * Ensures the mirror exists and contains the commit, fetching only if needed.
     */
    public void ensureCommit(String commit) {
        if (Files.isDirectory(mirror) && hasCommit(commit)) return;
        fetch();
        if (!hasCommit(commit)) throw new JpmException(key + ": commit " + commit + " not found in " + url);
    }

    private boolean hasCommit(String commit) {
        return Exec.capture(mirror, Exec.cmd("git", "cat-file", "-e", commit + "^{commit}")).ok();
    }

    /**
     * Tag name to commit hash (peeled).
     */
    public Map<String, String> tags() {
        fetch();
        Exec.Result r = Exec.checked(mirror, Exec.cmd("git", "for-each-ref", "refs/tags",
                "--format=%(refname:strip=2) %(objectname) %(*objectname)"));
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : r.out().split("\n")) {
            if (line.isBlank()) continue;
            String[] p = line.trim().split(" ");
            out.put(p[0], p.length > 2 && !p[2].isEmpty() ? p[2] : p[1]);
        }
        return out;
    }

    /**
     * Tag name to when it was made: the tagger date of an annotated tag, the commit date of a lightweight one.
     */
    public Map<String, Instant> tagDates() {
        fetch();
        Exec.Result r = Exec.checked(mirror, Exec.cmd("git", "for-each-ref", "refs/tags", "--format=%(refname:strip=2) %(creatordate:unix)"));
        Map<String, Instant> out = new LinkedHashMap<>();
        for (String line : r.out().split("\n")) {
            String[] p = line.trim().split(" ");
            if (p.length == 2 && p[1].matches("\\d+")) out.put(p[0], Instant.ofEpochSecond(Long.parseLong(p[1])));
        }
        return out;
    }

    /**
     * The newest commit of a branch, after fetching.
     */
    public String branchHead(String branch) {
        fetch();
        Exec.Result r = Exec.capture(mirror, Exec.cmd("git", "rev-parse", "--verify", "refs/heads/" + branch + "^{commit}"));
        if (!r.ok()) throw new JpmException(key + ": branch \"" + branch + "\" not found");
        return r.out().trim();
    }

    /**
     * True if the remote has a branch with that name.
     */
    public boolean hasBranch(String branch) {
        fetch();
        return Exec.capture(mirror, Exec.cmd("git", "rev-parse", "--verify", "--quiet", "refs/heads/" + branch + "^{commit}")).ok();
    }

    /**
     * The remote's default branch.
     */
    public String defaultBranch() {
        fetch();
        Exec.Result r = Exec.capture(mirror, Exec.cmd("git", "symbolic-ref", "--short", "HEAD"));
        return r.ok() ? r.out().trim() : "main";
    }

    /**
     * Full hash for a (possibly abbreviated) commit, or null.
     */
    public String resolveCommit(String rev) {
        if (!Files.isDirectory(mirror) || !hasCommit(rev)) fetch();
        Exec.Result r = Exec.capture(mirror, Exec.cmd("git", "rev-parse", "--verify", rev + "^{commit}"));
        return r.ok() ? r.out().trim() : null;
    }

    /**
     * Reads a file at a commit, or null if absent.
     */
    public String show(String commit, String path) {
        Exec.Result r = Exec.capture(mirror, Exec.cmd("git", "show", commit + ":" + path));
        return r.ok() ? r.out() : null;
    }

    /**
     * Checks out a commit into the shared source cache and returns the directory.
     */
    public Path checkout(String commit) {
        ensureCommit(commit);
        Path dir = Fs.home().resolve("git-src").resolve(key.host()).resolve(key.group()).resolve(key.artifact()).resolve(commit);
        if (Files.isDirectory(dir.resolve(".jpm-complete"))) return dir;
        Fs.deleteTree(dir);
        Fs.mkdirs(dir.getParent());
        Exec.checked(dir.getParent(), Exec.cmd("git", "clone", "--quiet", "--shared", "--no-checkout", mirror.toString(), dir.getFileName().toString()));
        Exec.checked(dir, Exec.cmd("git", "-c", "advice.detachedHead=false", "checkout", "--quiet", commit));
        Fs.mkdirs(dir.resolve(".jpm-complete"));
        return dir;
    }
}
