package dev.jpm.build;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.json.Jsonc;
import dev.jpm.json.JsoncEdit;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.GitModule;
import dev.jpm.git.GitRepo;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dependency patches.
 *
 * <ol>
 *   <li>{@code jpm patch <dep>} unpacks the dependency's source (the Git checkout, or the Maven sources jar)
 *       into {@code .cache/patch/<name>}, as a Git repository whose first commit is the pristine source.</li>
 *   <li>You edit it.</li>
 *   <li>{@code jpm patch --commit <dep>} saves the diff to {@code patches/} and records it in the manifest.</li>
 * </ol>
 */
public final class Patches {
    private Patches() {}

    private static final List<String> GIT_ID = List.of("-c", "user.name=jpm", "-c", "user.email=jpm@localhost",
            "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false");

    public static String safeName(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    public static Path workDir(Project p, String id) {
        return p.dir.resolve(".cache").resolve("patch").resolve(safeName(id));
    }

    /** Owning module of a dependency, for recording the patch in the right manifest. */
    private static Project.Module owner(Project p, String id) {
        for (Project.Module m : p.modules) {
            for (Manifest.Dependency d : m.manifest.dependencies) if (d.key().toString().equals(id)) return m;
        }
        return null;
    }

    private static Manifest patchManifest(Project p, String id) {
        Project.Module m = owner(p, id);
        return m != null ? m.manifest : p.root;
    }

    public static void start(Project p, Graph g, MavenRepo repo, String dep) {
        String id = DepKey.parse(dep).toString();
        Graph.Pkg pkg = g.get(id);
        if (pkg == null || pkg.kind == Graph.Kind.LOCAL) throw new JpmException(dep + " is not a dependency of this project");
        Path work = workDir(p, id);
        if (Files.exists(work)) {
            throw new JpmException(id + " is already unpacked at " + work + ".\nEdit it, then run `jpm patch --commit " + id
                    + "`, or delete the folder to start over.");
        }
        GitRepo.requireGit();
        if (pkg.kind == Graph.Kind.GIT) {
            Path src = GitModule.locate(DepKey.parse(id), pkg.commit).checkout();
            Fs.copyTree(src, work);
            Fs.deleteTree(work.resolve(".jpm-complete"));
        } else {
            Path sources = repo.fetch(pkg.group(), pkg.name(), pkg.version, "sources", "jar");
            if (sources == null) {
                throw new JpmException(id + " " + pkg.version + " has no sources jar on Maven Central, so it cannot be patched from source");
            }
            Jars.extract(sources, work);
            Fs.deleteTree(work.resolve("META-INF"));
        }
        git(work, "init", "-q");
        git(work, "add", "-A");
        git(work, "commit", "-q", "--no-verify", "-m", "pristine " + id + " " + pkg.version);
        Fs.write(work.resolve(".git").resolve("jpm-patch"), id + "\n" + pkg.version + "\n");
        Manifest owner = patchManifest(p, id);
        String existing = owner.patches.get(id);
        if (existing != null) {
            Path pf = owner.dir().resolve(existing);
            Exec.Result r = Exec.capture(work, cmd("apply", "--whitespace=nowarn", pf.toString()));
            if (!r.ok()) {
                Log.warn("the existing patch " + existing + " no longer applies; starting from the pristine source\n" + r.err().strip());
            } else {
                Log.info("Applied the existing patch " + existing + " so you can keep editing it.");
            }
        }
        Log.step("Unpacked", id + " " + pkg.version + " to " + p.dir.relativize(work));
        Log.info("Edit the files there, then run `jpm patch --commit " + id + "`.");
    }

    public static void commit(Project p, String dep) {
        String id = DepKey.parse(dep).toString();
        Path work = workDir(p, id);
        if (!Files.isDirectory(work.resolve(".git"))) {
            throw new JpmException("No patch in progress for " + id + ". Run `jpm patch " + id + "` first.");
        }
        git(work, "add", "-A");
        Exec.Result diff = Exec.checked(work, cmd("diff", "--cached", "--no-color", "--no-ext-diff", "--binary", "--full-index", "HEAD"));
        Manifest owner = patchManifest(p, id);
        String rel = "patches/" + safeName(id) + ".patch";
        String src = Fs.read(owner.file);
        if (diff.out().isBlank()) {
            if (owner.patches.containsKey(id)) {
                Fs.write(owner.file, JsoncEdit.remove(src, List.of("patches"), id));
                try {
                    Files.deleteIfExists(owner.dir().resolve(owner.patches.get(id)));
                } catch (java.io.IOException ignored) {
                }
                Log.step("Removed", "patch for " + id + " (no changes left)");
            } else {
                Log.info("No changes to " + id + "; nothing to save.");
            }
            Fs.deleteTree(work);
            return;
        }
        String target = owner.patches.getOrDefault(id, rel);
        Fs.write(owner.dir().resolve(target), diff.out());
        // a patch with options keeps them: only its "file" is written
        Fs.write(owner.file, owner.patchOptions.containsKey(id)
                ? JsoncEdit.set(src, List.of("patches", id), "file", Jsonc.quote(target))
                : JsoncEdit.set(src, List.of("patches"), id, Jsonc.quote(target)));
        Fs.deleteTree(work);
        Log.step("Saved", target + " and recorded it in " + owner.dir().relativize(owner.file));
    }

    // ---------------------------------------------------------------- applying

    /** Copies {@code src} to {@code dest} and applies the patch there. */
    public static void applyTo(Path src, Path dest, Path patch, String id, String version) {
        Fs.deleteTree(dest);
        Fs.copyTree(src, dest);
        Fs.deleteTree(dest.resolve(".jpm-complete"));
        apply(dest, patch, id, version);
    }

    static void apply(Path dir, Path patch, String id, String version) {
        if (!Files.exists(patch)) throw new JpmException("Patch file " + patch + " for " + id + " does not exist");
        GitRepo.requireGit();
        // Make the directory its own repository root so paths in the patch resolve against it.
        if (!Files.exists(dir.resolve(".git"))) Exec.capture(dir, cmd("init", "-q"));
        Exec.Result r = Exec.capture(dir, cmd("apply", "--whitespace=nowarn", patch.toString()));
        if (!r.ok()) {
            throw new JpmException("Patch " + patch.getFileName() + " no longer applies to " + id + " " + version + ":\n"
                    + r.err().strip() + "\nRun `jpm patch " + id + "` to redo it against this version.");
        }
    }

    /** Files a patch touches (new paths), from its {@code +++ b/...} lines. */
    public static Set<String> touched(Path patch) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : Fs.read(patch).split("\n")) {
            if (line.startsWith("+++ b/")) out.add(line.substring(6).trim());
            else if (line.startsWith("rename to ")) out.add(line.substring(10).trim());
        }
        return out;
    }

    private static void git(Path dir, String... args) {
        Exec.checked(dir, cmd(args));
    }

    private static List<String> cmd(String... args) {
        List<String> c = new ArrayList<>();
        c.add("git");
        c.addAll(GIT_ID);
        c.addAll(List.of(args));
        return c;
    }
}
