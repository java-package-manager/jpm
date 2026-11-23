package dev.jpm.cli.impl;

import dev.jpm.cli.Main;

import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.build.Env;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Install;
import dev.jpm.util.Exec;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class DevLoop {
    private final StartCommand commands;
    private final String entry;
    private final List<String> appArgs;

    DevLoop(StartCommand commands, String entry, List<String> appArgs) {
        this.commands = commands;
        this.entry = entry;
        this.appArgs = appArgs;
    }

    int run() {
        Process proc = null;
        String snapshot = null;
        while (true) {
            Project project = commands.findProject();
            String now = Watch.snapshot(project, false);
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
}
