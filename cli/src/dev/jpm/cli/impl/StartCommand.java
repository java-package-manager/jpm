package dev.jpm.cli.impl;

import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.build.Compiler;
import dev.jpm.build.Env;
import dev.jpm.build.Scripts;
import dev.jpm.toolchain.Jdk;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class StartCommand extends Command {
    private final boolean development;

    StartCommand(Path cwd, boolean development) {
        super(cwd);
        this.development = development;
    }

    @Override
    String name() {
        return development ? "dev" : "start";
    }

    @Override
    List<Usage> usages() {
        if (development) {
            return List.of(new Usage(Group.BUILD, "dev [entry] [-- args]", "Run main with development env variables, reloading code on changes",
                    "Run `main` with the development env: a change to the module's code is recompiled and reloaded into the running"
                            + " JVM (method bodies; any change on JetBrains Runtime), anything else, or a change that cannot be reloaded,"
                            + " restarts it; `--restart` always restarts"));
        }
        return List.of(
                new Usage(Group.BUILD, "start [entry] [-- args]", "Build, then run main with production env variables",
                        "Build, then run `main` with the production env"),
                new Usage(Group.TOOLCHAIN, "start --trace", "Run under the native-image tracing agent to record its config",
                        "Run under the native-image tracing agent, recording configuration for native builds"));
    }

    @Override
    List<String> flags() {
        return development ? List.of("--restart") : List.of("--trace");
    }

    @Override
    int run(List<String> args) {
        List<String> appArgs = passthrough(args);
        boolean trace = flag(args, "--trace");
        boolean restart = development && flag(args, "--restart");
        noMoreFlags(args);
        if (trace && development) throw new JpmException("--trace works with `jpm start`, not `jpm dev`");
        String entry = args.isEmpty() ? null : args.remove(0);
        appArgs.addAll(0, args);
        Env.Mode mode = development ? Env.Mode.DEVELOPMENT : Env.Mode.PRODUCTION;
        if (!development) {
            Ctx c = open(false);
            Project.Module m = single(c.project(), "run");
            Builder b = c.builder(mode).forModule(m);
            Scripts.hooks(b, m, m.manifest, "pre", "start", null, mode);
            List<String> cmd = javaCommand(b, m, entry, appArgs, trace);
            int code = Exec.inherit(m.dir, cmd, Env.forChild(c.project().dir, mode));
            if (trace) {
                Log.step("Traced", "native-image configuration merged into "
                        + m.dir.relativize(traceDir(m)) + "; `jpm build --native` uses it");
            }
            return code;
        }
        return new DevLoop(this, entry, appArgs, !restart).run();
    }

    List<String> javaCommand(Builder b, Project.Module m, String entry, List<String> appArgs) {
        return javaCommand(b, m, entry, appArgs, false);
    }

    static Path traceDir(Project.Module m) {
        return Builder.nativeConfigDir(m);
    }

    List<String> javaCommand(Builder b, Project.Module m, String entry, List<String> appArgs, boolean trace) {
        String main = mainClass(m, entry);
        b.compileMain(m);
        Jdk jdk = trace ? b.graalvm(m.manifest) : b.jdk(m.manifest);
        List<String> cmd = new ArrayList<>();
        cmd.add(jdk.java());
        if (trace) {
            boolean earlier = Files.exists(traceDir(m).resolve("reflect-config.json"));
            Fs.mkdirs(traceDir(m));
            cmd.add("-agentlib:native-image-agent=config-" + (earlier ? "merge" : "output") + "-dir=" + traceDir(m));
        }
        String opts = System.getenv("JPM_JVM_ARGS");
        if (opts != null && !opts.isBlank()) cmd.addAll(List.of(opts.trim().split("\\s+")));
        cmd.addAll(launchArgs(b, m, main));
        cmd.addAll(appArgs);
        return cmd;
    }

    static List<String> launchArgs(Builder b, Project.Module m, String main) {
        Path classes = Builder.classesDir(m);
        String module = dev.jpm.build.Modules.declaredName(classes);
        List<String> args = new ArrayList<>();
        if (module == null) {
            args.add("-cp");
            args.add(Compiler.join(b.runtimeClasspath(m)));
            args.add(main);
            return args;
        }
        Path moduleInfo = dev.jpm.build.Modules.moduleInfo(m.srcDirs());
        dev.jpm.build.Modules.Declaration decl = moduleInfo != null ? dev.jpm.build.Modules.declaration(b.jdk(m.manifest), moduleInfo)
                : new dev.jpm.build.Modules.Declaration(module, Set.of());
        List<List<Path>> split = dev.jpm.build.Modules.split(decl, b.classpath(m, Builder.Cp.RUNTIME));
        List<Path> modulePath = new ArrayList<>();
        modulePath.add(classes);
        modulePath.addAll(split.get(0));
        args.add("--module-path");
        args.add(Compiler.join(modulePath));
        if (!split.get(1).isEmpty()) {
            args.add("-cp");
            args.add(Compiler.join(split.get(1)));
            args.add("--add-reads");
            args.add(module + "=ALL-UNNAMED");
        }
        args.add("--add-modules");
        args.add("ALL-MODULE-PATH");
        args.add("-m");
        args.add(module + "/" + main);
        return args;
    }

    private static String mainClass(Project.Module m, String entry) {
        Map<String, String> mains = m.manifest.mains;
        if (entry != null) {
            if (mains.containsKey(entry)) return mains.get(entry);
            if (entry.contains(".")) return entry;
            throw new JpmException("No entry point named \"" + entry + "\" in " + m.manifest.file
                    + (mains.isEmpty() ? "" : ". Entry points: " + String.join(", ", mains.keySet())));
        }
        if (mains.isEmpty()) throw new JpmException(m.manifest.file + " has no \"main\"");
        if (mains.size() == 1) return mains.values().iterator().next();
        if (mains.containsKey("main")) return mains.get("main");
        throw new JpmException(m.name() + " has several entry points; pick one: jpm start <" + String.join("|", mains.keySet()) + ">");
    }

    Ctx openForDev() {
        return open(false);
    }

    Project findProject() {
        return Project.find(cwd);
    }

    Project.Module singleForRun(Project p) {
        return single(p, "run");
    }

    Path cwd() {
        return cwd;
    }
}
