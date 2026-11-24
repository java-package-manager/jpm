package dev.jpm.cli.impl;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.api.json.Jsonc;
import dev.jpm.build.Builder;
import dev.jpm.build.Compiler;
import dev.jpm.build.Env;
import dev.jpm.build.Jars;
import dev.jpm.git.GitRepo;
import dev.jpm.log.Log;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.GitModule;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Install;
import dev.jpm.resolve.Lockfile;
import dev.jpm.toolchain.JavaSpec;
import dev.jpm.toolchain.Jdk;
import dev.jpm.toolchain.Toolchain;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.version.Tags;
import dev.jpm.version.Version;
import dev.jpm.version.VersionSpecs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class GlobalExtensions {
    private GlobalExtensions() {
    }


    static Path globalBin() {
        return Fs.home().resolve("bin");
    }

    static int add(List<String> args) {
        for (String a : args) {
            DepArgs.DepArg d = DepArgs.parse(a);
            if (d.key().isWorkspace()) throw new JpmException("Workspace modules cannot be installed globally");
            String spec = d.spec() != null ? d.spec() : DepArgs.latestSpec(new MavenRepo(List.of()), d.key());
            Graph.Pkg pkg = install(d.key(), spec);
            String name = globalName(d.key());
            Log.step("Installed", "jpm-" + name + " (" + d.key() + " " + describe(pkg) + "); run it with `jpm " + name + "`");
        }
        return 0;
    }

    private static String globalName(DepKey key) {
        return Manifest.nameOf(key);
    }

    private static String describe(Graph.Pkg pkg) {
        if (pkg == null) return "?";
        return pkg.version + (pkg.commit != null && !pkg.version.startsWith(pkg.commit.substring(0, 7)) ? " (" + pkg.commit.substring(0, 7) + ")" : "");
    }

    static Graph.Pkg install(DepKey key, String spec) {
        String name = globalName(key);
        Path dir = Fs.home().resolve("global").resolve(name);
        MavenRepo repo = new MavenRepo(List.of());
        repo.setRefreshMetadata(true);
        Fs.mkdirs(dir);
        Fs.write(dir.resolve(Manifest.FILE), "{\n  \"name\": " + Jsonc.quote("jpm-global-" + name) + ",\n  \"version\": \"0.0.0\",\n"
                + "  \"runtime\": { " + Jsonc.quote(key.toString()) + ": " + Jsonc.quote(spec) + " }\n}\n");
        Project p = Project.load(dir);
        Graph g = Install.run(p, repo, new Install.Options(false, true, Set.of(), true));
        Launch l = launch(p, g, key, repo);
        Graph.Pkg pkg = g.get(key.toString());
        Fs.mkdirs(globalBin());
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        Path launcher = globalBin().resolve("jpm-" + name + (win ? ".cmd" : ""));
        String classpath = Compiler.join(l.classpath());
        if (win) {
            Fs.write(launcher, "@echo off\r\n\"" + l.java() + "\" -cp \"" + classpath + "\" " + l.main() + " %*\r\n");
        } else {
            Fs.write(launcher, "#!/bin/sh\nexec \"" + l.java() + "\" -cp \"" + classpath + "\" " + l.main() + " \"$@\"\n");
            try {
                launcher.toFile().setExecutable(true);
            } catch (SecurityException ignored) {
            }
        }
        return pkg;
    }

    record Launch(String java, List<Path> classpath, String main) {}

    static Launch launch(Project p, Graph g, DepKey key, MavenRepo repo) {
        Builder b = new Builder(p, g, repo, Env.Mode.PRODUCTION);
        List<Path> cp = b.classpath(p.modules.get(0), Builder.Cp.RUNTIME);
        Graph.Pkg pkg = g.get(key.toString());
        String main = null;
        if (pkg.kind == Graph.Kind.GIT) {
            Map<String, String> mains = GitModule.locate(key, pkg.commit).manifest().mains;
            if (!mains.isEmpty()) main = mains.getOrDefault("main", mains.values().iterator().next());
        } else if (!b.paths(pkg.id, null).isEmpty()) {
            main = Jars.mainClass(b.paths(pkg.id, null).get(0));
        }
        if (main == null) throw new JpmException(key + " has no main class, so it cannot run as an extension");
        Jdk jdk = Toolchain.require(JavaSpec.any());
        return new Launch(jdk.java(), cp, main);
    }

    static int update(List<String> names, boolean latest) {
        List<Path> dirs = selected(names);
        if (dirs.isEmpty()) return 0;
        MavenRepo repo = new MavenRepo(List.of());
        repo.setRefreshMetadata(true);
        for (Path dir : dirs) {
            String name = dir.getFileName().toString();
            Manifest m = Manifest.load(dir);
            if (m.dependencies.size() != 1) {
                Log.warn("skipping " + dir + ": it is not a global extension made by `jpm add -g`");
                continue;
            }
            Manifest.Dependency d = m.dependencies.get(0);
            Lockfile lock = Lockfile.read(dir.resolve(Manifest.LOCK));
            Graph.Pkg before = lock == null ? null : lock.graph.get(d.key().toString());
            String spec = d.spec();
            boolean pinned = d.key().isGit() && spec.matches("[0-9a-f]{7,40}");
            String newest = pinned ? spec : DepArgs.latestSpec(repo, d.key());
            if (latest && !pinned) spec = newest;
            Graph.Pkg after = install(d.key(), spec);
            boolean changed = before == null || !Objects.equals(before.version, after.version) || !Objects.equals(before.commit, after.commit);
            if (changed) {
                Log.step("Updated", "jpm-" + name + " " + (before == null ? "" : describe(before) + " -> ") + describe(after)
                        + (spec.equals(d.spec()) ? "" : Log.dim(" (" + d.spec() + " -> " + spec + ")")));
            } else {
                Log.step("Up to date", "jpm-" + name + " " + describe(after)
                        + (spec.equals(d.spec()) ? "" : Log.dim(" (now follows " + spec + " instead of " + d.spec() + ")")));
            }
            if (!latest && !pinned && !newest.equals(spec)) {
                Log.info(spec.startsWith("#")
                        ? "  jpm-" + name + " has releases now (" + newest + "). Run `jpm update -g --latest " + name + "` to follow them instead of the "
                                + spec.substring(1) + " branch."
                        : "  newer outside " + spec + ": " + newest + ". Run `jpm update -g --latest " + name + "` to move to it.");
            }
        }
        return 0;
    }

    static int check(List<String> names) {
        List<Path> dirs = selected(names);
        if (dirs.isEmpty()) return 0;
        MavenRepo repo = new MavenRepo(List.of());
        repo.setRefreshMetadata(true);
        int outdated = 0;
        for (Path dir : dirs) {
            String name = dir.getFileName().toString();
            Manifest m = Manifest.load(dir);
            if (m.dependencies.size() != 1) {
                Log.warn("skipping " + dir + ": it is not a global extension made by `jpm add -g`");
                continue;
            }
            Manifest.Dependency d = m.dependencies.get(0);
            Lockfile lock = Lockfile.read(dir.resolve(Manifest.LOCK));
            Graph.Pkg before = lock == null ? null : lock.graph.get(d.key().toString());
            Available a = available(repo, d.key(), d.spec(), before);
            String current = before == null ? "?" : describe(before);
            if (a.update() != null) {
                outdated++;
                Log.step("Outdated", "jpm-" + name + " " + current + " -> " + a.update());
            } else {
                Log.step("Up to date", "jpm-" + name + " " + current);
            }
            if (a.outside() != null) {
                Log.info("  newer outside " + d.spec() + ": " + a.outside() + ". Run `jpm update -g --latest " + name + "` to move to it.");
            }
        }
        if (outdated > 0) Log.info("Run `jpm update -g` to install " + (outdated == 1 ? "the update." : "the updates."));
        return 0;
    }

    record Available(String update, String outside) {}

    static Available available(MavenRepo repo, DepKey key, String spec, Graph.Pkg before) {
        if (key.isGit() && spec.matches("[0-9a-f]{7,40}")) return new Available(null, null);
        if (key.isGit() && spec.startsWith("#")) {
            String head = GitRepo.of(key).branchHead(spec.substring(1));
            boolean behind = before == null || before.commit == null || !head.equals(before.commit);
            return new Available(behind ? head.substring(0, 7) : null, null);
        }
        List<Version> releases = new ArrayList<>();
        if (key.isGit()) {
            Set<String> tags = GitRepo.of(key).tags().keySet();
            for (String t : tags) {
                String v = Tags.version(key.module(), t, tags);
                if (v != null) releases.add(Version.of(v));
            }
        } else if (key.isMaven()) {
            releases.addAll(repo.versions(key.group(), key.artifact()));
        } else {
            String newest = DepArgs.latestSpec(repo, key).replaceFirst("^\\^", "");
            if (!newest.startsWith("#") && !newest.equals("*")) releases.add(Version.of(newest));
        }
        releases.removeIf(v -> v.isPreRelease() || v.isSnapshot());
        Version have = before == null || before.version == null ? null : Version.of(before.version);
        Version best = VersionSpecs.parse(spec, key.isGit()).best(releases).orElse(null);
        Version newest = releases.stream().max(Comparator.naturalOrder()).orElse(null);
        String update = best != null && (have == null || best.compareTo(have) > 0) ? best.toString() : null;
        Version reach = update != null ? best : have;
        String outside = newest != null && (reach == null || newest.compareTo(reach) > 0) ? newest.toString() : null;
        return new Available(update, outside);
    }

    private static List<Path> selected(List<String> names) {
        Path root = Fs.home().resolve("global");
        List<Path> dirs = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (var s = Files.list(root)) {
                s.filter(d -> Files.isRegularFile(d.resolve(Manifest.FILE))).sorted().forEach(dirs::add);
            } catch (IOException e) {
                throw new JpmException("Could not read " + root + ": " + e.getMessage());
            }
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String n : names) wanted.add(n.contains(":") ? globalName(DepArgs.parse(n).key()) : n.replaceFirst("^jpm-", ""));
        for (String n : wanted) {
            if (dirs.stream().noneMatch(d -> d.getFileName().toString().equals(n))) {
                throw new JpmException("No global extension named " + n + ". Install it with `jpm add -g <dependency>`.");
            }
        }
        if (dirs.isEmpty()) Log.info("No global extensions are installed. Install one with `jpm add -g <dependency>`.");
        if (!wanted.isEmpty()) dirs.removeIf(d -> !wanted.contains(d.getFileName().toString()));
        return dirs;
    }

    static int remove(List<String> args) {
        for (String a : args) {
            String name = a.contains(":") ? DepArgs.parse(a).key().mavenArtifact().replaceFirst("^jpm-", "") : a.replaceFirst("^jpm-", "");
            Path dir = Fs.home().resolve("global").resolve(name);
            boolean any = Files.exists(dir);
            Fs.deleteTree(dir);
            for (String ext : new String[]{"", ".cmd"}) {
                try {
                    any |= Files.deleteIfExists(globalBin().resolve("jpm-" + name + ext));
                } catch (IOException ignored) {
                }
            }
            if (!any) throw new JpmException("No global extension named " + name);
            Log.step("Removed", "jpm-" + name);
        }
        return 0;
    }
}
