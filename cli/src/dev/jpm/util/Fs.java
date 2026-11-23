package dev.jpm.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** File helpers that throw unchecked exceptions. */
public final class Fs {
    private Fs() {}

    /** Global JPM home: {@code $JPM_HOME} or {@code ~/.jpm}. */
    public static Path home() {
        String prop = System.getProperty("jpm.home");
        if (prop != null && !prop.isBlank()) return Path.of(prop).toAbsolutePath().normalize();
        String env = System.getenv("JPM_HOME");
        if (env != null && !env.isBlank()) return Path.of(env).toAbsolutePath().normalize();
        String h = System.getenv("HOME");
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        String base = h != null && !h.isBlank() && !win ? h : System.getProperty("user.home");
        return Path.of(base, ".jpm").toAbsolutePath().normalize();
    }

    /** True with --offline or JPM_OFFLINE: use only what is cached. */
    public static boolean offline() {
        return System.getenv("JPM_OFFLINE") != null || Boolean.getBoolean("jpm.offline");
    }

    /** A file's text, as UTF-8. */
    public static String read(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        if (name.endsWith(".java") || name.endsWith(".kt")) {
            throw new IllegalStateException("jpm does not read Java or Kotlin sources (ADR 0001): " + p);
        }
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JpmException("Could not read " + p + ": " + e.getMessage());
        }
    }

    /** Writes text as UTF-8, creating folders, through a temporary file so readers never see half a file. */
    public static void write(Path p, String content) {
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new JpmException("Could not write " + p + ": " + e.getMessage());
        }
    }

    /** Writes only if the content differs, keeping timestamps stable. */
    public static boolean writeIfChanged(Path p, String content) {
        try {
            if (Files.exists(p) && java.util.Arrays.equals(Files.readAllBytes(p), content.getBytes(StandardCharsets.UTF_8))) return false;
        } catch (IOException e) {
            throw new JpmException("Could not read " + p + ": " + e.getMessage());
        }
        write(p, content);
        return true;
    }

    /** Creates a folder and its parents. */
    public static void mkdirs(Path p) {
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new JpmException("Could not create " + p + ": " + e.getMessage());
        }
    }

    /**
     * Deletes a file or a folder with everything in it; nothing when it does not exist. Read-only files go too, as
     * on Windows Git makes its object files read-only and they cannot be deleted until that is cleared.
     */
    public static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    try {
                        Files.delete(file);
                    } catch (java.nio.file.AccessDeniedException e) {
                        java.nio.file.attribute.DosFileAttributeView dos = Files.getFileAttributeView(file,
                                java.nio.file.attribute.DosFileAttributeView.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                        if (dos == null || !dos.readAttributes().isReadOnly()) throw e;
                        dos.setReadOnly(false);
                        Files.delete(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new JpmException("Could not delete " + root + ": " + e.getMessage());
        }
    }

    /** Copies a folder with everything in it. */
    public static void copyTree(Path from, Path to) {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path rel = from.relativize(p);
                if (rel.toString().equals(".git") || rel.startsWith(".git")) continue;
                Path target = to.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new JpmException("Could not copy " + from + ": " + e.getMessage());
        }
    }

    /** All regular files under root with the given suffix, sorted. Missing root gives an empty list. */
    public static List<Path> files(Path root, String suffix) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> suffix == null || p.toString().endsWith(suffix))
                    .sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Path with forward slashes, for stable keys across platforms. */
    public static String slash(Path p) {
        return p.toString().replace('\\', '/');
    }
}
