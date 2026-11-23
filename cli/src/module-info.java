/**
 * jpm itself. It exports nothing: other programs use {@code dev.jpm.api}.
 */
module dev.jpm {
    requires dev.jpm.api;
    requires java.net.http;
    requires java.xml;
    requires jdk.unsupported;
}
