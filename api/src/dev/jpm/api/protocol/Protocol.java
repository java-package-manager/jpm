package dev.jpm.api.protocol;

/**
 * Protocol versions: {@code <major>} or {@code <major>.<minor>}. The same major is compatible; a new minor only
 * adds methods, optional params, result fields or notifications, or fixes bugs.
 */
public final class Protocol {
    private Protocol() {}

    /** The protocol version of this jpm and of this client jar. */
    public static final String VERSION = "1.1";

    /** JSON-RPC error code: {@code initialize} with another major version. */
    public static final int PROTOCOL_MISMATCH = -32001;
    /** JSON-RPC error code: a request before {@code initialize}. */
    public static final int NOT_INITIALIZED = -32002;
    /** JSON-RPC error code: what jpm reports as an error on the command line; {@code data} is a problem. */
    public static final int PROJECT_ERROR = -32003;
    /** JSON-RPC error code: the client cancelled the request. */
    public static final int CANCELLED = -32800;

    /**
     * The major number of a version.
     *
     * @param version {@code "1"} or {@code "1.3"}
     * @return its major number
     * @throws IllegalArgumentException when it is not a version
     */
    public static int major(String version) {
        if (version == null || !version.matches("\\d+(\\.\\d+)?")) {
            throw new IllegalArgumentException("not a protocol version: \"" + version + "\" (expected <major> or <major>.<minor>)");
        }
        int dot = version.indexOf('.');
        return Integer.parseInt(dot < 0 ? version : version.substring(0, dot));
    }

    /**
     * Whether two versions can talk to each other.
     *
     * @param a a version
     * @param b another version
     * @return true when they have the same major number
     */
    public static boolean compatible(String a, String b) {
        return major(a) == major(b);
    }
}
