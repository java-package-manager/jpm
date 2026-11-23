package dev.jpm.api.client;

import dev.jpm.api.model.Problem;

/**
 * An error jpm answered a request with.
 */
public class JpmError extends RuntimeException {
    private final int code;
    private final transient Problem problem;

    /**
     * An error.
     *
     * @param code    the JSON-RPC error code (see {@link dev.jpm.api.protocol.Protocol})
     * @param message jpm's message
     * @param problem what is wrong, where and what to do next, or null
     */
    public JpmError(int code, String message, Problem problem) {
        super(message);
        this.code = code;
        this.problem = problem;
    }

    /**
     * The JSON-RPC error code.
     *
     * @return the code
     */
    public int code() {
        return code;
    }

    /**
     * What is wrong, for errors in the project, such as a bad manifest.
     *
     * @return the problem, or null
     */
    public Problem problem() {
        return problem;
    }
}
