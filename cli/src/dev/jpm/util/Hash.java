package dev.jpm.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 helpers.
 */
public final class Hash {
    private final MessageDigest md;

    /**
     * An empty SHA-256 hash.
     */
    public Hash() {
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Adds text, with a separator, so that {@code add("ab")} differs from {@code add("a").add("b")}.
     */
    public Hash add(String s) {
        md.update(s.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        return this;
    }

    /**
     * Adds bytes, with a separator.
     */
    public Hash add(byte[] b) {
        md.update(b);
        md.update((byte) 0);
        return this;
    }

    /**
     * Adds a file's content, with a separator.
     */
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

    /**
     * The hash as lowercase hex.
     */
    public String hex() {
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * The SHA-256 of a file, as lowercase hex.
     */
    public static String sha256(Path file) {
        return new Hash().addFileRaw(file).hex();
    }

    /**
     * The SHA-256 of bytes, as lowercase hex.
     */
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

    /**
     * A file's hash with another algorithm ({@code sha1}, {@code sha256}, {@code sha512}), as lowercase hex.
     */
    public static String of(String algorithm, Path file) {
        String name = switch (algorithm) {
            case "sha1" -> "SHA-1";
            case "sha256" -> "SHA-256";
            case "sha512" -> "SHA-512";
            default -> throw new IllegalArgumentException("no hash named " + algorithm);
        };
        try (InputStream in = Files.newInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance(name);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw new JpmException("Could not read " + file + ": " + e.getMessage());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The SHA-256 of UTF-8 text, as lowercase hex.
     */
    public static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }
}
