package dev.jpm.cli;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.build.Env;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Install;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * {@code jpm dev}: runs main with development env variables and restarts it when sources, assets, the
 * manifest or env files change. (A true in-process hot reload, e.g. on JetBrains Runtime, is an open
 * question in the spec; restarting is the dependable baseline.)
 */
final class DevLoop {
    private final Commands commands;
    private final String entry;
    private final List<String> appArgs;

    DevLoop(Commands commands, String entry, List<String> appArgs) {
        this.commands = commands;
        this.entry = entry;
        this.appArgs = appArgs;
    }

    int run() {
        Process proc = null;
        String snapshot = null;
        while (true) {
            Project project = commands.findProject();
            String now = snapshot(project);
            if (!now.equals(snapshot)) {
                if (snapshot != null) Log.step("Restarting", "files changed");
                stop(proc);
                proc = null;
                snapshot = now;
                try {
                    MavenRepo repo = new MavenRepo(project.root.repositories);
                    Graph g = Install.run(project, repo, new Install.Options(false, false, Set.of(), true));
                    Builder b = new Builder(project, g, repo, Env.Mode.DEVELOPMENT);
                    Project.Module m = commands.singleForRun(project);
                    List<String> cmd = commands.javaCommand(b.forModule(m), m, entry, appArgs);
                    proc = Exec.start(m.dir, cmd, Env.forChild(project.dir, Env.Mode.DEVELOPMENT));
                    Log.info(Log.dim("watching for changes (Ctrl+C to stop)"));
                } catch (JpmException e) {
                    Main.report(e);
                    Log.info(Log.dim("waiting for changes"));
                }
            }
            try {
                if (proc != null && proc.waitFor(400, TimeUnit.MILLISECONDS)) {
                    int code = proc.exitValue();
                    Log.info(Log.dim("process exited with code " + code + "; waiting for changes"));
                    proc = null;
                } else if (proc == null) {
                    Thread.sleep(400);
                }
            } catch (InterruptedException e) {
                stop(proc);
                return 130;
            }
        }
    }

    private static void stop(Process p) {
        if (p == null || !p.isAlive()) return;
        p.destroy();
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Names, sizes and modification times of everything that affects the running program. */
    private static String snapshot(Project p) {
        List<Path> roots = new ArrayList<>();
        roots.add(p.dir.resolve(Manifest.FILE));
        for (String f : List.of(".env", ".env.development", ".env.local")) roots.add(p.dir.resolve(f));
        for (Project.Module m : p.modules) {
            roots.add(m.dir.resolve(Manifest.FILE));
            roots.addAll(m.srcDirs());
            for (Path a : m.assetsDirs()) if (m.srcDirs().stream().noneMatch(a::startsWith)) roots.add(a);
            roots.add(m.dir.resolve("patches"));
        }
        StringBuilder sb = new StringBuilder();
        for (Path r : roots) {
            List<Path> files = Files.isDirectory(r) ? Fs.files(r, null) : Files.exists(r) ? List.of(r) : List.of();
            for (Path f : files) {
                try {
                    sb.append(f).append(' ').append(Files.size(f)).append(' ').append(Files.getLastModifiedTime(f).toMillis()).append('\n');
                } catch (IOException ignored) {
                }
            }
        }
        return Integer.toHexString(Objects.hash(sb.toString())) + sb.length();
    }
}
