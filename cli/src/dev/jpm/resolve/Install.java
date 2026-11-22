package dev.jpm.resolve;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.maven.MavenRepo;
import dev.jpm.util.Hash;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Brings {@code project.lock} and the package cache up to date. */
public final class Install {
    private Install() {}

    public record Options(boolean frozen, boolean updateAll, Set<String> update, boolean quiet) {
        public static Options normal() { return new Options(false, false, Set.of(), true); }
    }

    public static Graph run(Project project, MavenRepo repo, Options o) {
        Path lockPath = project.lockFile();
        Lockfile lock = Lockfile.read(lockPath);
        String inputs = ResolveInputs.inputs(project);
        boolean fresh = lock != null && inputs.equals(lock.inputs) && !o.updateAll() && o.update().isEmpty();
        Graph graph;
        if (fresh) {
            graph = lock.graph;
            ResolveInputs.addLocal(graph, project);
            for (Project.Module m : project.isolatedModules()) {
                Graph iso = graph.isolated.get(m.name());
                if (iso == null) {
                    throw new JpmException(Manifest.LOCK + " has no section for the isolated module " + m.name() + ". Run `jpm install`.");
                }
                ResolveInputs.addLocal(iso, project.isolatedView(m));
            }
            graph.isolated.keySet().retainAll(project.isolatedModules().stream().map(Project.Module::name).toList());
        } else {
            if (o.frozen()) {
                throw new JpmException(lock == null ? Manifest.LOCK + " is missing and --frozen was given"
                        : Manifest.LOCK + " is out of date with " + Manifest.FILE + " and --frozen was given. Run `jpm install` and commit the lockfile.");
            }
            if (!o.quiet() || lock == null || !inputs.equals(lock.inputs)) Log.step("Resolving", "dependencies");
            if (o.updateAll()) repo.setRefreshMetadata(true);
            Graph prev = lock == null ? null : lock.graph;
            graph = new Resolver(project, repo, prev, o.update(), o.updateAll()).resolve();
            for (Project.Module m : project.isolatedModules()) {
                Graph before = prev == null ? null : prev.isolated.get(m.name());
                graph.isolated.put(m.name(), new Resolver(project.isolatedView(m), repo, before, o.update(), o.updateAll()).resolve());
            }
        }
        fetch(graph, repo);
        for (Graph iso : graph.isolated.values()) fetch(iso, repo);
        Lockfile out = new Lockfile(inputs, graph);
        if (!fresh || !out.render().equals(lock == null ? null : lock.render())) {
            out.write(lockPath);
            if (!fresh) {
                report(lock == null ? null : lock.graph, graph);
                for (Map.Entry<String, Graph> e : graph.isolated.entrySet()) {
                    Graph before = lock == null ? null : lock.graph.isolated.get(e.getKey());
                    Log.info(Log.dim("  isolated " + e.getKey() + ":"));
                    report(before, e.getValue());
                }
            }
        }
        return graph;
    }

    /** Downloads Maven jars in parallel, recording or verifying their checksums. */
    public static void fetch(Graph graph, MavenRepo repo) {
        List<Graph.Pkg> todo = new ArrayList<>();
        for (Graph.Pkg p : graph.pkgs.values()) {
            if (p.kind == Graph.Kind.MAVEN && p.hasJar) todo.add(p);
            if (p.kind == Graph.Kind.GRADLE) {
                GradleDist.Installed g = GradleDist.require(p.version, p.sha256);
                p.sha256 = g.sha256();
                p.artifact = g.home();
            }
            if (p.kind == Graph.Kind.EXTENSION) {
                ExtensionDeps.Result r = ExtensionDeps.resolve(dev.jpm.DepKey.parse(p.id), p.version);
                String sha = r.sha256();
                if (p.sha256 != null && !p.sha256.equals(sha)) {
                    throw new JpmException("Checksum mismatch for " + p.id + " " + p.version + " (from jpm-" + dev.jpm.DepKey.parse(p.id).group()
                            + "):\n  locked:   " + p.sha256 + "\n  provided: " + sha);
                }
                p.sha256 = sha;
            }
        }
        if (todo.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, todo.size()));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Graph.Pkg p : todo) {
                fs.add(pool.submit(() -> {
                    Path jar = repo.local(p.group(), p.name(), p.version, p.classifier(), "jar");
                    boolean had = jar.toFile().exists();
                    jar = repo.require(p.group(), p.name(), p.version, p.classifier(), "jar");
                    if (!had) Log.debug("downloaded " + p);
                    String sha = Hash.sha256(jar);
                    if (p.sha256 != null && !p.sha256.equals(sha)) {
                        throw new JpmException("Checksum mismatch for " + p.id + " " + p.version + ":\n  locked:   " + p.sha256
                                + "\n  download: " + sha + "\nIf the cached file is corrupt, delete " + jar + " and try again.");
                    }
                    p.sha256 = sha;
                    p.artifact = jar;
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException re) throw re;
                    throw new JpmException("Download failed: " + e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new JpmException("Interrupted");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void report(Graph before, Graph after) {
        int added = 0, changed = 0, removed = 0;
        for (Graph.Pkg p : after.pkgs.values()) {
            if (p.kind == Graph.Kind.LOCAL) continue;
            Graph.Pkg b = before == null ? null : before.get(p.id);
            if (b == null) {
                added++;
                Log.info("  " + Log.bold("+") + " " + p.id + " " + p.version);
            } else if (!b.version.equals(p.version) || (p.commit != null && !p.commit.equals(b.commit))) {
                changed++;
                Log.info("  " + Log.bold("~") + " " + p.id + " " + b.version + " -> " + p.version);
            }
        }
        if (before != null) {
            for (Graph.Pkg b : before.pkgs.values()) {
                if (after.get(b.id) == null) {
                    removed++;
                    Log.info("  " + Log.bold("-") + " " + b.id + " " + b.version);
                }
            }
        }
        long total = after.pkgs.values().stream().filter(p -> p.kind != Graph.Kind.LOCAL).count();
        Log.step("Locked", total + " package" + (total == 1 ? "" : "s")
                + (added + changed + removed > 0 ? " (" + added + " added, " + changed + " changed, " + removed + " removed)" : ""));
    }
}
