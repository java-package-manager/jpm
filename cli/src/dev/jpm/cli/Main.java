package dev.jpm.cli;

import dev.jpm.cli.impl.Commands;
import dev.jpm.cli.impl.JavaCommand;
import dev.jpm.cli.impl.Shims;
import dev.jpm.cli.impl.Upgrade;
import dev.jpm.log.Log;
import dev.jpm.util.JpmException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code jpm} — the Java package manager and build tool.
 */
public final class Main {
    /**
     * From project.jsonc, through jpm's own build-time defines.
     */
    public static final String VERSION = dev.jpm.BuildConfig.VERSION;

    private Main() {
    }


    /**
     * Runs jpm, or a shim when started as {@code java}, {@code javac} and so on, and exits with the command's code.
     */
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

    /**
     * Prints a failure: what went wrong, where, and what to do next.
     */
    public static void report(JpmException e) {
        Log.error(e.getMessage());
        if (e.location() != null) Log.info("  --> " + e.location());
        if (e.hint() != null) Log.info("  hint: " + e.hint());
    }

    /**
     * True when started as {@code jvm} (a link or copy of the binary, or the jvm launcher script).
     */
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
        String as = System.getenv("JPM_AS");
        if (as != null && as.startsWith("shim:")) return Shims.run(as.substring(5), args);
        boolean nativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null;
        String invoked = invokedName();
        if (nativeImage && Shims.isTool(invoked)) return Shims.run(invoked, args);
        if (invokedAsJvm()) {
            if (args.isEmpty() || args.get(0).equals("-h") || args.get(0).equals("--help")) {
                Log.print(JavaCommand.JVM_HELP);
                return 0;
            }
            if (args.get(0).equals("--version")) {
                Log.out("jvm (jpm " + VERSION + ")");
                return 0;
            }
            logOptions(args, false);
            return Commands.run(Path.of("").toAbsolutePath(), "jvm", args);
        }
        while (!args.isEmpty() && args.get(0).startsWith("-")) {
            String a = args.get(0);
            if (a.equals("--offline")) {
                System.setProperty("jpm.offline", "true");
                args.remove(0);
            } else if (a.equals("-h") || a.equals("--help")) {
                Log.print(Commands.help());
                return 0;
            } else if (a.equals("--version")) return Upgrade.version();
            else if (!logOption(args, 0, true)) break;
        }
        logOptions(args, false);
        if (args.isEmpty()) {
            Log.print(Commands.help());
            return 0;
        }
        String cmd = args.remove(0);
        return Commands.run(Path.of("").toAbsolutePath(), cmd, args);
    }
}
