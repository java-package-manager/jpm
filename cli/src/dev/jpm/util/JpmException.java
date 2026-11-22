package dev.jpm.util;

import java.nio.file.Path;

/**
 * A failure the user can act on. jpm prints it without a stack trace (unless {@code --verbose}): the
 * message says what went wrong, the location where (a file and line, for manifest errors), and the hint
 * what to do next.
 */
public class JpmException extends RuntimeException {
    private final int exitCode;
    private final String location;
    private final String hint;

    public JpmException(String message) {
        this(message, 1);
    }

    public JpmException(String message, int exitCode) {
        this(message, null, exitCode, null, null);
    }

    public JpmException(String message, Throwable cause) {
        this(message, cause, 1, null, null);
    }

    private JpmException(String message, Throwable cause, int exitCode, String location, String hint) {
        super(message, cause);
        this.exitCode = exitCode;
        this.location = location;
        this.hint = hint;
    }

    /** A failure at a line of a file, such as a field in {@code project.jsonc}. */
    public static JpmException at(Path file, int line, String message) {
        return new JpmException(message, null, 1, file + (line > 0 ? ":" + line : ""), null);
    }

    /** The same failure with what to do next, for example the command that fixes it. */
    public JpmException withHint(String hint) {
        return new JpmException(getMessage(), getCause(), exitCode, location, hint);
    }

    public int exitCode() {
        return exitCode;
    }

    /** Where the failure is, as {@code file:line}, or null. */
    public String location() {
        return location;
    }

    /** What to do next, or null. */
    public String hint() {
        return hint;
    }
}
