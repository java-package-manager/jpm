package dev.jpm.check;

import java.nio.file.Path;

/**
 * Something {@code jpm check} found wrong: the one shape jpm prints, writes to SARIF and JUnit reports, and sends
 * over the protocol.
 *
 * @param severity an error fails {@code jpm check}; a warning only with {@code --fail-on-warning}
 * @param message  what is wrong
 * @param file     where, or null
 * @param line     the 1-based line in {@code file}, or null
 * @param hint     how to fix it, or null
 * @param check    the id of the check that found it
 */
public record Problem(Severity severity, String message, Path file, Integer line, String hint, String check) {
    /**
     * How bad a problem is.
     */
    public enum Severity {
        ERROR, WARNING
    }

    /**
     * An error of a check.
     */
    public static Problem error(String check, String message, Path file, Integer line) {
        return new Problem(Severity.ERROR, message, file, line, null, check);
    }

    /**
     * A warning of a check.
     */
    public static Problem warning(String check, String message, Path file, Integer line) {
        return new Problem(Severity.WARNING, message, file, line, null, check);
    }

    /**
     * The same problem with a hint.
     */
    public Problem withHint(String hint) {
        return new Problem(severity, message, file, line, hint, check);
    }
}
