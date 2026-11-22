package dev.jpm.cli;

import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** {@code jpm} — the Java package manager and build tool. */
public final class Main {
    /** From project.jsonc, through jpm's own build-time defines. */
    public static final String VERSION = dev.jpm.BuildConfig.VERSION;

    private Main() {}

    static final String HELP = """
            jpm %s - one command for Java: dependencies, builds, running, tests and patches.

            Usage: jpm <command> [options]

            Project
              init [name]                 Create a project in the current directory
              add <dep>[@version]         Add dependencies (--dev, --test; -r <url> adds a Maven repository;
                                          -g installs an extension)
              remove <dep>                Remove dependencies (-g removes an extension)
              install                     Resolve dependencies and write project.lock (--frozen in CI)
              update [dep...]             Re-resolve within ranges (--latest goes past them;
                                          -g updates the global extensions)
              outdated                    Show dependencies with newer versions
              why <dep>                   Show why a dependency is in the graph
              info [--json]               Describe the project (modules, dependencies); --json for extensions
              import [dir]                Create project.jsonc from a Maven or Gradle build (--dry-run, --force)
              schema [--path]             Print the JSON Schema of project.jsonc (--path writes it to ~/.jpm
                                          and prints where, for editors)

            Build and run
              build                       Compile and package into dist/ (--release, --profile <name>)
              start [entry] [-- args]     Build, then run main with production env variables
              dev [entry] [-- args]       Run main with development env variables, restarting on changes
              test [filter...]            Run tests on the JUnit Platform (--tag, --exclude-tag select by JUnit tag)
              run <script> [args]         Run a script from the manifest; <module>:<script> for another module's
              clean                       Delete build output

            Dependencies
              patch <dep>                 Unpack a dependency's source for editing
              patch --commit <dep>        Save your edits to patches/ and record them in the manifest

            Toolchain
              java list [--available]     List installed JDKs, or what can be installed
              java install <java>         Install a JDK or GraalVM (21, temurin-17, graalvm-21, ...)
              java remove <java>          Remove a JDK jpm installed
              java use <java>             Set "java" in the manifest (and install it)
              jvm use <java>              Choose the JDK that `java` runs outside projects (shims in ~/.jpm/bin)
              jvm current                 Show which JDK `java` runs here, and why
              jvm exec [java] <cmd>       Run one command with a JDK
              build --bundle              Also build a runnable jar-in-jar bundle with isolated modules
              build --native              Also build a native executable with GraalVM
              start --trace               Run under the native-image tracing agent to record its config

            Maintenance
              upgrade [version]           Upgrade jpm to the latest release (--check only reports,
                                          --canary installs the newest build of the main branch)

            Any other command runs an extension: `jpm format` runs `jpm-format`.
            Global options: -v/--verbose (-vv for more), -q/--quiet, --color=auto|always|never, --offline
            """;

    public static void main(String[] argv) {
        int code;
        try {
            code = run(new ArrayList<>(Arrays.asList(argv)));
        } catch (JpmException e) {
            report(e);
            Log.stackTrace(e.getCause());
            code = e.exitCode();
        } catch (RuntimeException e) {
            Log.error("unexpected failure: " + e);
            if (Log.verbose()) Log.stackTrace(e);
            else Log.info("Run with -v for details.");
            code = 70;
        }
        Log.flush();
        System.exit(code);
    }

    /** Prints a failure: what went wrong, where, and what to do next. */
    static void report(JpmException e) {
        Log.error(e.getMessage());
        if (e.location() != null) Log.info("  --> " + e.location());
        if (e.hint() != null) Log.info("  hint: " + e.hint());
    }

    /** True when started as {@code jvm} (a link or copy of the binary, or the jvm launcher script). */
    static boolean invokedAsJvm() {
        if ("jvm".equals(System.getenv("JPM_AS"))) return true;
        return isJvmName(invokedName());
    }

    static boolean isJvmName(String path) {
        if (path == null || path.isEmpty()) return false;
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase();
        return name.equals("jvm") || name.equals("jvm.exe");
    }

    /**
     * The name the program was started under. On Linux the executable path resolves symlinks (a `jvm`
     * symlink would read as `jpm`), so argv[0] is taken from /proc/self/cmdline instead.
     */
    static String invokedName() {
        try {
            java.nio.file.Path cmdline = java.nio.file.Path.of("/proc/self/cmdline");
            if (java.nio.file.Files.isReadable(cmdline)) {
                byte[] b = java.nio.file.Files.readAllBytes(cmdline);
                int end = 0;
                while (end < b.length && b[end] != 0) end++;
                if (end > 0) return new String(b, 0, end, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (java.io.IOException | RuntimeException ignored) {
        }
        try {
            return ProcessHandle.current().info().command().orElse("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Applies the output options anywhere in {@code args} and removes them: {@code -v}/{@code --verbose}
     * (twice, or {@code -vv}, for trace messages), {@code -q}/{@code --quiet} (only before the command, where
     * {@code global} is true) and {@code --color=WHEN}.
     */
    static void logOptions(List<String> args, boolean global) {
        for (int i = 0; i < args.size(); ) {
            if (args.get(i).equals("--")) return;
            if (!logOption(args, i, global)) i++;
        }
    }

    private static boolean logOption(List<String> args, int i, boolean global) {
        String a = args.get(i);
        switch (a) {
            case "-v", "--verbose" -> Log.configure(Log.verbose() ? Log.Level.TRACE : Log.Level.DEBUG, colorFlag);
            case "-vv" -> Log.configure(Log.Level.TRACE, colorFlag);
            case "--quiet" -> Log.configure(Log.Level.WARN, colorFlag);
            case "-q" -> {
                if (!global) return false;
                Log.configure(Log.Level.WARN, colorFlag);
            }
            default -> {
                String when;
                if (a.startsWith("--color=")) when = a.substring(8);
                else if (a.equals("--color") && i + 1 < args.size() && Log.Color.parse(args.get(i + 1)) != null) {
                    when = args.remove(i + 1);
                } else return false;
                Log.Color c = Log.Color.parse(when);
                if (c == null) throw new JpmException("--color takes auto, always or never, not \"" + when + "\"", 2);
                colorFlag = c;
                Log.configure(null, c);
            }
        }
        args.remove(i);
        return true;
    }

    private static Log.Color colorFlag;

    static int run(List<String> args) {
        // Started as java, javac, ...: a shim that runs the right JDK's tool, with arguments untouched.
        // Only the native binary can be a shim: on a JVM, argv[0] is the JVM's own java.
        // JPM_AS=shim:<tool> simulates one (tests, the jar build).
        String as = System.getenv("JPM_AS");
        if (as != null && as.startsWith("shim:")) return Shims.run(as.substring(5), args);
        boolean nativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null;
        String invoked = invokedName();
        if (nativeImage && Shims.isTool(invoked)) return Shims.run(invoked, args);
        if (invokedAsJvm()) {
            if (args.isEmpty() || args.get(0).equals("-h") || args.get(0).equals("--help")) {
                Log.print(JavaCommands.JVM_HELP);
                return 0;
            }
            if (args.get(0).equals("--version")) {
                Log.out("jvm (jpm " + VERSION + ")");
                return 0;
            }
            logOptions(args, false);
            return new Commands().java(args, true);
        }
        // global flags, before the command
        while (!args.isEmpty() && args.get(0).startsWith("-")) {
            String a = args.get(0);
            if (a.equals("--offline")) { System.setProperty("jpm.offline", "true"); args.remove(0); }
            else if (a.equals("-h") || a.equals("--help")) { Log.print(HELP.formatted(VERSION)); return 0; }
            else if (a.equals("--version")) return Upgrade.version();
            else if (!logOption(args, 0, true)) break;
        }
        logOptions(args, false);
        if (args.isEmpty()) {
            Log.print(HELP.formatted(VERSION));
            return 0;
        }
        String cmd = args.remove(0);
        Commands c = new Commands();
        return switch (cmd) {
            case "help" -> { Log.print(HELP.formatted(VERSION)); yield 0; }
            case "version" -> Upgrade.version();
            case "upgrade", "self-update" -> Upgrade.run(args);
            case "init" -> c.init(args);
            case "add" -> c.add(args);
            case "remove", "rm" -> c.remove(args);
            case "install", "i" -> c.install(args);
            case "update" -> c.update(args);
            case "outdated" -> c.outdated(args);
            case "why" -> c.why(args);
            case "build" -> c.build(args);
            case "start" -> c.start(args, false);
            case "dev" -> c.start(args, true);
            case "test" -> c.test(args);
            case "run" -> c.runScript(args);
            case "clean" -> c.clean(args);
            case "patch" -> c.patch(args);
            case "import" -> c.importBuild(args);
            case "info" -> c.info(args);
            case "schema" -> Schema.run(args);
            case "java" -> c.java(args, false);
            case "jvm" -> c.java(args, true);
            default -> c.extension(cmd, args);
        };
    }
}
