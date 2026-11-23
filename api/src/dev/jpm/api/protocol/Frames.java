package dev.jpm.api.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Protocol messages on a byte stream, framed as in LSP: {@code Content-Length: <bytes>\r\n\r\n} and then that
 * many bytes of UTF-8 JSON.
 */
public final class Frames {
    private Frames() {
    }

    /**
     * Reads one message.
     *
     * @param in the stream, positioned at a header
     * @return the message's JSON text, or null when the stream ends before a header
     * @throws IOException when reading fails, or the header has no valid {@code Content-Length}
     */
    public static String read(InputStream in) throws IOException {
        int length = -1;
        while (true) {
            String line = headerLine(in);
            if (line == null) return null;
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    length = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IOException("bad Content-Length header: " + line);
                }
            }
        }
        if (length < 0) throw new IOException("message without a Content-Length header");
        byte[] body = in.readNBytes(length);
        if (body.length < length) throw new IOException("the stream ended inside a message");
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String headerLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                byte[] bytes = b.toByteArray();
                int n = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, n, StandardCharsets.US_ASCII);
            }
            b.write(c);
        }
        if (b.size() > 0) throw new IOException("the stream ended inside a header");
        return null;
    }

    /**
     * Writes one message and flushes the stream.
     *
     * @param out  the stream
     * @param json the message's JSON text
     * @throws IOException when writing fails
     */
    public static synchronized void write(OutputStream out, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
