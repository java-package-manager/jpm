package dev.jpm.api.client;

/** No jpm was found to start. An IDE can offer to install it (jpm's install scripts install it globally). */
public final class JpmNotInstalled extends RuntimeException {
    /**
     * The error.
     *
     * @param message where jpm was looked for
     */
    public JpmNotInstalled(String message) {
        super(message);
    }
}
