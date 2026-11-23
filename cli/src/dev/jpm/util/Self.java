package dev.jpm.util;

import java.nio.file.Path;

/**
 * How child processes (scripts, hooks, extensions) call back into this jpm.
 */
public final class Self {
    private Self() {
    }

    /**
     * The jpm to run: {@code JPM_EXE} when a launcher set it (the jar build runs as {@code java -jar}, which
     * cannot be called back by itself), else this native binary, else {@code jpm} from {@code PATH}.
     */
    public static String exe() {
        String fromLauncher = System.getenv("JPM_EXE");
        if (fromLauncher != null && !fromLauncher.isBlank()) return fromLauncher;
        String cmd = ProcessHandle.current().info().command().orElse(null);
        if (cmd != null) {
            String n = Path.of(cmd).getFileName().toString().toLowerCase();
            if (!n.equals("java") && !n.equals("java.exe")) return cmd;
        }
        return "jpm";
    }
}
