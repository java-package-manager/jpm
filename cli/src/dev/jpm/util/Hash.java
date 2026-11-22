package dev.jpm.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers. */
public final class Hash {
    private final MessageDigest md;

    public Hash() {
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Hash add(String s) {
        md.update(s.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        return this;
    }

    public Hash add(byte[] b) {
        md.update(b);
        md.update((byte) 0);
        return this;
    }

    public Hash addFile(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            md.update((byte) 0);
            return this;
        } catch (IOException e) {
            throw new JpmException("Could not read " + p + ": " + e.getMessage());
        }
    }

    public String hex() {
        return HexFormat.of().formatHex(md.digest());
    }

    public static String sha256(Path file) {
        return new Hash().addFileRaw(file).hex();
    }

    public static String sha256(byte[] data) {
        Hash h = new Hash();
        h.md.update(data);
        return h.hex();
    }

    private Hash addFileRaw(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return this;
        } catch (IOException e) {
            throw new JpmException("Could not read " + p + ": " + e.getMessage());
        }
    }

    public static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }
}
