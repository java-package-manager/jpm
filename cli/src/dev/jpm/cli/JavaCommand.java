package dev.jpm.cli;

import java.nio.file.Path;
import java.util.List;

final class JavaCommand extends Command {
    private final boolean asJvm;

    JavaCommand(Path cwd, boolean asJvm) {
        super(cwd);
        this.asJvm = asJvm;
    }

    @Override
    String name() {
        return asJvm ? "jvm" : "java";
    }

    @Override
    List<Usage> usages() {
        if (asJvm) {
            return List.of(
                    new Usage(Group.TOOLCHAIN, "jvm use <java>", "Choose the JDK that `java` runs outside projects (shims in ~/.jpm/bin)"),
                    new Usage(Group.TOOLCHAIN, "jvm current", "Show which JDK `java` runs here, and why",
                            "Which JDK `java` runs here, and why. `jpm java …` and `jvm …` are the same commands"),
                    new Usage(Group.TOOLCHAIN, "jvm exec [java] <cmd>", "Run one command with a JDK"));
        }
        return List.of(
                new Usage(Group.TOOLCHAIN, "java list [--available]", "List installed JDKs, or what can be installed",
                        "Installed JDKs (`*` marks what `java` runs here), or what can be installed (`--available [vendor]`)"),
                new Usage(Group.TOOLCHAIN, "java install <java>", "Install a JDK or GraalVM (21, temurin-17, graalvm-21, ...)",
                        "Install a JDK or GraalVM into `~/.jpm/jdks` (`21`, `temurin-17`, `graalvm-21`, ...)"),
                new Usage(Group.TOOLCHAIN, "java remove <java>", "Remove a JDK jpm installed"),
                new Usage(Group.TOOLCHAIN, "java use <java>", "Set \"java\" in the manifest (and install it)",
                        "Set `\"java\"` in the manifest, installing it if needed"));
    }

    @Override
    List<String> flags() {
        return List.of("list", "install", "remove", "use", "current", "exec", "setup", "--available");
    }

    @Override
    int run(List<String> args) {
        return new JavaCommands(cwd, asJvm).run(args);
    }
}
