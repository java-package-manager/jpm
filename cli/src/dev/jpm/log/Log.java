package dev.jpm.log;

import java.io.PrintStream;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * jpm's console output: the only class that writes to {@code System.out} and {@code System.err}.
 *
 * <p>Messages have a {@link Level} and go to stderr, so stdout carries only what a command produces
 * ({@link #out(String)}): help text, {@code jpm info}, {@code jpm why} and so on. {@code --quiet} shows
 * errors and warnings only, {@code --verbose} adds debug messages, {@code -vv} trace messages too.
 *
 * <p>Colors: errors are red, warnings orange (yellow on terminals with 16 colors), debug messages yellow.
 * Whether to use color is decided here once, see {@link Color}.
 */
public final class Log {
    private Log() {
    }

    /**
     * How much jpm prints, from least to most.
     */
    public enum Level {
        ERROR, WARN, INFO, DEBUG, TRACE;

        /**
         * Reads {@code JPM_VERBOSE}: unset, empty, {@code 0} or {@code false} is {@code INFO}; {@code 2} or
         * {@code trace} is {@code TRACE}; anything else is {@code DEBUG}.
         */
        public static Level fromVerboseEnv(String value) {
            if (value == null) return INFO;
            String v = value.strip().toLowerCase(Locale.ROOT);
            if (v.isEmpty() || v.equals("0") || v.equals("false")) return INFO;
            if (v.equals("2") || v.equals("trace")) return TRACE;
            return DEBUG;
        }
    }

    /**
     * When to color output: {@code --color=auto|always|never}.
     */
    public enum Color {
        AUTO, ALWAYS, NEVER;

        /**
         * Parses {@code auto}, {@code always} or {@code never}; null for anything else.
         */
        public static Color parse(String s) {
            if (s == null) return null;
            return switch (s.strip().toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "always", "1", "true", "yes" -> ALWAYS;
                case "never", "0", "false", "no" -> NEVER;
                default -> null;
            };
        }

        /**
         * Whether to color, from the first that is set: the {@code --color} flag, {@code JPM_COLOR}
         * ({@code 1}/{@code always}, {@code 0}/{@code never} or {@code auto}), {@code NO_COLOR} (never),
         * {@code FORCE_COLOR} (always, unless it is {@code 0}), and finally whether stderr is a terminal.
         */
        public static boolean decide(Color flag, UnaryOperator<String> env, boolean terminal) {
            Color c = flag != null && flag != AUTO ? flag : null;
            if (c == null) c = parse(env.apply("JPM_COLOR"));
            if (c == null || c == AUTO) {
                if (present(env.apply("NO_COLOR"))) c = NEVER;
                else if (env.apply("FORCE_COLOR") != null) c = "0".equals(env.apply("FORCE_COLOR").strip()) ? NEVER : ALWAYS;
            }
            if (c == null || c == AUTO) return terminal && !"dumb".equals(env.apply("TERM"));
            return c == ALWAYS;
        }

        /**
         * True when the terminal shows 256 colors: TERM ends in 256color, COLORTERM is set, or Windows Terminal.
         */
        public static boolean has256(UnaryOperator<String> env) {
            String term = env.apply("TERM");
            return (term != null && term.contains("256color")) || present(env.apply("COLORTERM")) || present(env.apply("WT_SESSION"));
        }
    }

    private static boolean present(String s) {
        return s != null && !s.isEmpty();
    }

    private static PrintStream out() {
        return System.out;
    }

    private static PrintStream err() {
        return System.err;
    }

    private static final boolean TERMINAL = System.console() != null;

    private static Level level = Level.fromVerboseEnv(System.getenv("JPM_VERBOSE"));
    private static boolean color = Color.decide(null, System::getenv, TERMINAL);
    private static boolean colors256 = Color.has256(System::getenv);

    /**
     * Sets the level and color mode from the command line; a null argument keeps what the environment chose.
     */
    public static void configure(Level newLevel, Color colorFlag) {
        if (newLevel != null) level = newLevel;
        color = Color.decide(colorFlag, System::getenv, TERMINAL);
    }

    /**
     * The level messages are shown at.
     */
    public static Level level() {
        return level;
    }

    /**
     * True when messages of this level are shown.
     */
    public static boolean enabled(Level l) {
        return l.compareTo(level) <= 0;
    }

    /**
     * True with {@code --verbose} or {@code JPM_VERBOSE}: debug messages and stack traces are shown.
     */
    public static boolean verbose() {
        return enabled(Level.DEBUG);
    }

    /**
     * True when output is colored. Child processes get {@code JPM_COLOR} from it.
     */
    public static boolean color() {
        return color;
    }

    /**
     * Variables for child processes (scripts, hooks, extensions) so that they, and the jpm they call back
     * into, print the way this jpm does: {@code JPM_COLOR}, and {@code JPM_VERBOSE} when verbose.
     */
    public static java.util.Map<String, String> childEnv() {
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("JPM_COLOR", color ? "1" : "0");
        if (enabled(Level.TRACE)) env.put("JPM_VERBOSE", "2");
        else if (enabled(Level.DEBUG)) env.put("JPM_VERBOSE", "1");
        return env;
    }

    /**
     * Where messages go instead of the console, for example to a protocol client (see {@link #sink}).
     */
    public interface Sink {
        /**
         * One message.
         *
         * @param level   its level
         * @param message its text, without colors
         */
        void log(Level level, String message);
    }

    private static volatile Sink sink;

    /**
     * Sends every message, and what would go to stdout, to {@code s} instead of the console; null prints again.
     * {@code jpm api} uses it, because its stdout carries protocol messages only.
     */
    public static void sink(Sink s) {
        sink = s;
    }

    /**
     * Hands the process's stdout to the caller (the {@code jpm api} protocol) and points {@code System.out} at
     * stderr, so that nothing else can write to it.
     */
    public static java.io.OutputStream claimStdout() {
        java.io.OutputStream stdout = new java.io.BufferedOutputStream(new java.io.FileOutputStream(java.io.FileDescriptor.out));
        System.setOut(System.err);
        return stdout;
    }

    /**
     * stderr, where output that must not reach stdout goes, such as a child's output under {@code jpm api}.
     */
    public static PrintStream stderr() {
        return err();
    }

    /**
     * True while a {@link #sink(Sink)} receives the messages: stdout is not jpm's to print on.
     */
    public static boolean sunk() {
        return sink != null;
    }

    private static boolean toSink(Level level, String msg) {
        Sink s = sink;
        if (s == null) return false;
        if (enabled(level)) s.log(level, msg);
        return true;
    }

    /**
     * An error: always shown, in red.
     */
    public static void error(String msg) {
        if (toSink(Level.ERROR, msg)) return;
        err().println(c("1;31", "error") + ": " + msg);
    }

    /**
     * A warning: shown unless only errors are, in orange (yellow on 16-color terminals).
     */
    public static void warn(String msg) {
        if (toSink(Level.WARN, msg)) return;
        if (enabled(Level.WARN)) err().println(c(colors256 ? "1;38;5;208" : "1;33", "warning") + ": " + msg);
    }

    /**
     * A plain message, for example a next step.
     */
    public static void info(String msg) {
        if (toSink(Level.INFO, msg)) return;
        if (enabled(Level.INFO)) err().println(msg);
    }

    /**
     * A status line: {@code verb} right-aligned in green, then the message ("   Compiled app 1.0.0").
     */
    public static void step(String verb, String msg) {
        if (toSink(Level.INFO, verb + " " + msg)) return;
        if (enabled(Level.INFO)) err().println(c("1;32", String.format("%12s", verb)) + " " + msg);
    }

    /**
     * A debug message, in yellow, with {@code --verbose}.
     */
    public static void debug(String msg) {
        if (toSink(Level.DEBUG, msg)) return;
        if (enabled(Level.DEBUG)) err().println(c("33", "  " + msg));
    }

    /**
     * A trace message, dim, with {@code -vv}.
     */
    public static void trace(String msg) {
        if (toSink(Level.TRACE, msg)) return;
        if (enabled(Level.TRACE)) err().println(c("2", "  " + msg));
    }

    /**
     * What a command produces, on stdout, at every level: for example {@code jpm info} or the help text.
     */
    public static void out(String line) {
        if (toSink(Level.INFO, line)) return;
        out().println(line);
    }

    /**
     * Like {@link #out(String)}, without a line break.
     */
    public static void print(String text) {
        if (toSink(Level.INFO, text.stripTrailing())) return;
        out().print(text);
    }

    /**
     * Output of a tool jpm ran, such as javac's errors and warnings, on stderr at every level.
     */
    public static void tool(String text) {
        if (text.isEmpty() || toSink(Level.INFO, text)) return;
        err().println(text);
    }

    /**
     * The stack trace of an unexpected failure, shown with {@code --verbose} only.
     */
    public static void stackTrace(Throwable t) {
        if (verbose() && t != null) t.printStackTrace(err());
    }

    /**
     * Flushes stdout and stderr before jpm exits.
     */
    public static void flush() {
        out().flush();
        err().flush();
    }

    /**
     * A progress line, redrawn in place ({@code  Downloading x 42% of 180 MB}). Nothing is drawn unless stderr
     * is a terminal and info messages are shown, so logs and CI output stay clean.
     */
    public static Progress progress(String verb, String label) {
        return new Progress(TERMINAL && enabled(Level.INFO), verb, label);
    }

    /**
     * See {@link #progress(String, String)}.
     */
    public static final class Progress implements AutoCloseable {
        private final boolean draw;
        private final String verb, label;
        private int last = -1;
        private boolean drawn;

        private Progress(boolean draw, String verb, String label) {
            this.draw = draw;
            this.verb = verb;
            this.label = label;
        }

        /**
         * Shows {@code done} of {@code total} bytes; does nothing when the total is unknown.
         */
        public void update(long done, long total) {
            if (!draw || total <= 0) return;
            int pct = (int) (done * 100 / total);
            if (pct == last) return;
            last = pct;
            drawn = true;
            err().print("\r" + c("1;32", String.format("%12s", verb)) + " " + label + " " + pct + "% of " + (total >> 20) + " MB");
        }

        /**
         * Clears the line.
         */
        @Override
        public void close() {
            if (drawn) err().print("\r\u001b[K");
        }
    }

    /**
     * {@code s} in bold, when output is colored.
     */
    public static String bold(String s) {
        return c("1", s);
    }

    /**
     * {@code s} dimmed, when output is colored.
     */
    public static String dim(String s) {
        return c("2", s);
    }

    private static String c(String code, String s) {
        return color ? "\u001b[" + code + "m" + s + "\u001b[0m" : s;
    }
}
