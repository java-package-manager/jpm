package dev.jpm.api.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {
    @Test
    void theSameMajorIsCompatible() {
        assertTrue(Protocol.compatible("1", "1.0"));
        assertTrue(Protocol.compatible("1.0", "1.9"));
        assertFalse(Protocol.compatible("1.9", "2.0"));
        assertEquals(12, Protocol.major("12.3"));
        assertThrows(IllegalArgumentException.class, () -> Protocol.major("1.0.0"));
        assertThrows(IllegalArgumentException.class, () -> Protocol.major(null));
    }

    @Test
    void framesRoundTrip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Frames.write(out, "{\"a\":\"ü\"}");
        Frames.write(out, "[]");
        assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("Content-Length: 10\r\n\r\n"), "the length is in bytes");
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        assertEquals("{\"a\":\"ü\"}", Frames.read(in));
        assertEquals("[]", Frames.read(in));
        assertNull(Frames.read(in));
        assertThrows(IOException.class, () -> Frames.read(new ByteArrayInputStream("Content-Type: x\r\n\r\n{}".getBytes())));
        assertThrows(IOException.class, () -> Frames.read(new ByteArrayInputStream("Content-Length: 9\r\n\r\n{}".getBytes())));
    }
}
