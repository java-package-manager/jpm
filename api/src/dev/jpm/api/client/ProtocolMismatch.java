package dev.jpm.api.client;

/** The installed jpm speaks another major version of the protocol; the message names both versions. */
public final class ProtocolMismatch extends JpmError {
    /**
     * A mismatch.
     *
     * @param message jpm's message, naming both versions and what to upgrade
     */
    public ProtocolMismatch(String message) {
        super(dev.jpm.api.protocol.Protocol.PROTOCOL_MISMATCH, message, null);
    }
}
