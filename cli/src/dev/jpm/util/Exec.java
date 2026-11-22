package dev.jpm.util;

import dev.jpm.log.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runs external processes. */
public final class Exec {
    private Exec() {}

    /** The exit code and output of a process. */
    public record Result(int exit, String out, String err) {
        /** True for exit code 0. */
        public boolean ok() { return exit == 0; }
    }

    /** Runs and captures output. */
    public static Result capture(Path dir, List<String> cmd) {
        return capture(dir, cmd, Map.of());
    }

    /** Runs a command with extra environment variables and returns its exit code and output. */
    public static Result capture(Path dir, List<String> cmd, Map<String, String> env) {
        Log.debug("$ " + String.join(" ", cmd) + (dir != null ? "  (in " + dir + ")" : ""));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (dir != null) pb.directory(dir.toFile());
        pb.environment().remove("JPM_AS");
        pb.environment().putAll(env);
        try {
            Process p = pb.start();
            p.getOutputStream().close();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread t = new Thread(() -> copy(p.getErrorStream(), err));
            t.start();
            byte[] out = p.getInputStream().readAllBytes();
            int code = p.waitFor();
            t.join();
            return new Result(code, new String(out, StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JpmException("Could not run " + cmd.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JpmException("Interrupted");
        }
    }

    /** Runs with inherited stdio and returns the exit code. */
    public static int inherit(Path dir, List<String> cmd, Map<String, String> env) {
        Log.debug("$ " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        if (dir != null) pb.directory(dir.toFile());
        pb.environment().remove("JPM_AS");
        pb.environment().putAll(env);
        try {
            Process p = pb.start();
            Runtime.getRuntime().addShutdownHook(new Thread(p::destroy));
            return p.waitFor();
        } catch (IOException e) {
            throw new JpmException("Could not run " + cmd.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    /** Starts a process with inherited stdio without waiting. */
    public static Process start(Path dir, List<String> cmd, Map<String, String> env) {
        Log.debug("$ " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        if (dir != null) pb.directory(dir.toFile());
        pb.environment().remove("JPM_AS");
        pb.environment().putAll(env);
        try {
            return pb.start();
        } catch (IOException e) {
            throw new JpmException("Could not run " + cmd.get(0) + ": " + e.getMessage());
        }
    }

    /** Runs a command and fails, with its error output, unless it exits with 0. */
    public static Result checked(Path dir, List<String> cmd) {
        Result r = capture(dir, cmd);
        if (!r.ok()) {
            throw new JpmException(String.join(" ", cmd) + " failed (exit " + r.exit + "):\n" + r.err.strip());
        }
        return r;
    }

    /** A mutable command line. */
    public static List<String> cmd(String... parts) {
        return new ArrayList<>(List.of(parts));
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        try {
            in.transferTo(out);
        } catch (IOException ignored) {
        }
    }

    /** Finds an executable on PATH. */
    public static Path which(String name) {
        return which(name, null);
    }

    /** Like {@link #which(String)}, skipping one directory (jpm's own bin folder, which holds the shims). */
    public static Path which(String name, Path skip) {
        return which(name, skip, p -> false);
    }

    /** Like {@link #which(String, Path)}, also skipping every candidate for which {@code skipFile} is true. */
    public static Path which(String name, Path skip, java.util.function.Predicate<Path> skipFile) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        Path skipNorm = skip == null ? null : skip.toAbsolutePath().normalize();
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            if (skipNorm != null && Path.of(dir).toAbsolutePath().normalize().equals(skipNorm)) continue;
            for (String ext : win ? new String[]{".exe", ".cmd", ".bat", ""} : new String[]{""}) {
                Path p = Path.of(dir, name + ext);
                if (java.nio.file.Files.isExecutable(p) && !java.nio.file.Files.isDirectory(p) && !skipFile.test(p)) return p;
            }
        }
        return null;
    }
}
