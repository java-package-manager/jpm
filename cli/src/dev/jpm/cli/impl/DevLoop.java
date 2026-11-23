package dev.jpm.cli.impl;

import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.build.Env;
import dev.jpm.build.HotReload;
import dev.jpm.cli.Main;
import dev.jpm.log.Log;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Install;
import dev.jpm.util.Exec;
import dev.jpm.util.JpmException;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class DevLoop {
    private final StartCommand commands;
    private final String entry;
    private final List<String> appArgs;

    private final boolean reload;

    DevLoop(StartCommand commands, String entry, List<String> appArgs, boolean reload) {
        this.commands = commands;
        this.entry = entry;
        this.appArgs = appArgs;
        this.reload = reload;
    }

    int run() {
        Process proc = null;
        String rest = null, code = null;
        Builder builder = null;
        Project.Module module = null;
        Path channel = null;
        while (true) {
            Project project = commands.findProject();
            Project.Module here = singleOrNull(project);
            Path moduleDir = here == null ? null : here.dir;
            String restNow = Watch.snapshot(project, false, f -> moduleDir != null && f.startsWith(moduleDir) && Watch.code(f)
                    && here.srcDirs().stream().anyMatch(f::startsWith));
            String codeNow = here == null ? "" : Watch.code(here);
            boolean restChanged = !restNow.equals(rest), codeChanged = !codeNow.equals(code);
            if (restChanged || codeChanged) {
                boolean reloaded = false;
                if (!restChanged && reload && proc != null && proc.isAlive() && builder != null) {
                    code = codeNow;
                    reloaded = reload(fresh.get(), module, channel);
                    if (!reloaded && proc.isAlive() && lastCompileFailed) {
                        continue;
                    }
                }
                if (!reloaded) {
                    if (rest != null) Log.step("Restarting", restChanged || !reload ? "files changed" : "the change cannot be reloaded");
                    stop(proc);
                    proc = null;
                    rest = restNow;
                    code = codeNow;
                    try {
                        MavenRepo repo = new MavenRepo(project.root.repositories);
                        Graph g = Install.run(project, repo, new Install.Options(false, false, Set.of(), true));
                        Builder b = new Builder(project, g, repo, Env.Mode.DEVELOPMENT);
                        Project.Module m = commands.singleForRun(project);
                        builder = b.forModule(m);
                        fresh = () -> new Builder(project, g, repo, Env.Mode.DEVELOPMENT).forModule(m);
                        module = m;
                        List<String> cmd = commands.javaCommand(builder, m, entry, appArgs);
                        if (reload) {
                            channel = m.cacheDir().resolve("reload");
                            cmd.addAll(1, HotReload.jvmArgs(builder, m, channel));
                        }
                        proc = Exec.start(m.dir, cmd, Env.forChild(project.dir, Env.Mode.DEVELOPMENT));
                        Log.info(Log.dim("watching for changes (Ctrl+C to stop)"));
                    } catch (JpmException e) {
                        builder = null;
                        Main.report(e);
                        Log.info(Log.dim("waiting for changes"));
                    }
                }
            }
            try {
                if (proc != null && proc.waitFor(400, TimeUnit.MILLISECONDS)) {
                    int exit = proc.exitValue();
                    Log.info(Log.dim("process exited with code " + exit + "; waiting for changes"));
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

    private boolean lastCompileFailed;
    private java.util.function.Supplier<Builder> fresh;

    private Project.Module singleOrNull(Project project) {
        try {
            return commands.singleForRun(project);
        } catch (JpmException e) {
            return null;
        }
    }

    private boolean reload(Builder b, Project.Module m, Path channel) {
        lastCompileFailed = false;
        Path classes = Builder.classesDir(m);
        java.util.Map<String, String> before = HotReload.classes(classes);
        try {
            b.compileMain(m);
        } catch (JpmException e) {
            Main.report(e);
            Log.info(Log.dim("the app keeps running the code it had; waiting for changes"));
            lastCompileFailed = true;
            return false;
        }
        List<String> changed = HotReload.changed(before, HotReload.classes(classes));
        if (changed.isEmpty()) return true;
        try {
            HotReload.Result r = HotReload.reload(channel, classes, changed);
            if (r.ok()) {
                Log.step("Reloaded", changed.size() + (changed.size() == 1 ? " class" : " classes") + Log.dim(" (" + r.redefined() + " loaded)"));
                return true;
            }
            Log.info(Log.dim("cannot reload: " + r.reason()));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
