package dev.jpm.cli.impl;

import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.build.Env;
import dev.jpm.build.Scripts;
import dev.jpm.util.JpmException;

import java.nio.file.Path;
import java.util.List;

final class BuildCommand extends Command {

    BuildCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "build";
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.BUILD, "build", "Compile and package into dist/ (--release, --profile <name>)",
                        "Build into `dist/` (`--release`, `--profile <name>`)"),
                new Usage(Group.TOOLCHAIN, "build --bundle", "Also build a runnable jar-in-jar bundle with isolated modules",
                        "Also build `dist/<name>-<version>-bundle.jar` with the runtime dependencies: a runnable jar-in-jar with isolated modules for a module with a `main`, one flat jar (like a shadow jar) for one without, such as a Paper or Velocity plugin; `--flat` makes it flat either way. `\"package\": \"bundle\"` makes it the default"),
                new Usage(Group.TOOLCHAIN, "build --native", "Also build a native executable with GraalVM",
                        "Also build a native executable of `main` with GraalVM into `dist/`"));
    }

    @Override
    List<String> flags() {
        return List.of("--release", "--native", "--bundle", "--flat", "--frozen", "--profile");
    }

    @Override
    int run(List<String> args) {
        boolean release = flag(args, "--release");
        boolean nativeImage = flag(args, "--native");
        boolean bundleFlag = flag(args, "--bundle");
        boolean flat = flag(args, "--flat");
        boolean frozen = flag(args, "--frozen");
        String profile = option(args, "--profile");
        if (release && profile == null) profile = "release";
        noMoreFlags(args);
        Ctx c = open(frozen);
        Builder b = c.builder(Env.Mode.PRODUCTION);
        List<Project.Module> mods = targets(c.project(), args);
        boolean all = mods.size() == c.project().modules.size() && c.project().root.isWorkspaceRoot();
        Project.Module rootMod = c.project().rootModule();
        if (all) Scripts.hooks(b, rootMod, c.project().root, "pre", "build", profile, Env.Mode.PRODUCTION);
        for (Project.Module m : mods) {
            Builder mb = b.forModule(m);
            Scripts.hooks(mb, m, m.manifest, "pre", "build", profile, Env.Mode.PRODUCTION);
            mb.jar(m);
            if (bundleFlag || flat || m.manifest.packaging.equals("bundle")) {
                if (flat || m.manifest.mains.isEmpty()) mb.flatBundle(m);
                else mb.bundle(m);
            }
            if (nativeImage) {
                if (!m.manifest.mains.isEmpty()) mb.nativeImage(m, null);
                else if (mods.size() == 1) throw new JpmException(m.manifest.file + " has no \"main\" to build natively");
            }
            Scripts.hooks(mb, m, m.manifest, "post", "build", profile, Env.Mode.PRODUCTION);
        }
        if (all) Scripts.hooks(b, rootMod, c.project().root, "post", "build", profile, Env.Mode.PRODUCTION);
        return 0;
    }
}
