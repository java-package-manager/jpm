package dev.jpm.cli.impl;

import dev.jpm.log.Log;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CacheCommand extends Command {
    static final Map<String, String> FOLDERS = folders();

    private static Map<String, String> folders() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("repository", "Maven packages");
        m.put("git", "Git repositories");
        m.put("git-src", "Git checkouts");
        m.put("builds", "built Git dependencies");
        m.put("imported", "Git dependencies built with Maven or Gradle");
        m.put("patched", "patched jars");
        m.put("git-patched", "patched Git dependencies");
        m.put("gradle", "Gradle distributions");
        m.put("downloads", "JDK downloads");
        m.put("cache", "other cached data");
        return m;
    }

    CacheCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "cache";
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.MAINTENANCE, "cache size", "Show how much space jpm's caches use",
                        "Show how much space each of jpm's caches in `~/.jpm` uses (Maven packages, Git dependencies and their builds,"
                                + " patched jars, Gradle distributions, JDK downloads)"),
                new Usage(Group.MAINTENANCE, "cache clean [cache...]", "Delete jpm's caches; they are downloaded again when needed",
                        "Delete jpm's caches, or only the ones named (`repository`, `git`, `builds`, ...: see `jpm cache size`); what a"
                                + " project needs is downloaded or built again. Installed JDKs, global extensions and shims are kept"));
    }

    @Override
    List<String> flags() {
        List<String> out = new ArrayList<>(List.of("size", "clean"));
        out.addAll(FOLDERS.keySet());
        return out;
    }

    @Override
    int run(List<String> args) {
        noMoreFlags(args);
        if (args.isEmpty()) throw new JpmException("Usage: jpm cache size | jpm cache clean [cache...]", 2);
        String sub = args.remove(0);
        Path home = Fs.home();
        switch (sub) {
            case "size" -> {
                if (!args.isEmpty()) throw new JpmException("Usage: jpm cache size", 2);
                long total = 0;
                int width = FOLDERS.keySet().stream().mapToInt(String::length).max().orElse(0);
                for (Map.Entry<String, String> e : FOLDERS.entrySet()) {
                    Path dir = home.resolve(e.getKey());
                    if (!Files.isDirectory(dir)) continue;
                    long size = size(dir);
                    total += size;
                    Log.out(String.format(Locale.ROOT, "%9s  %-" + width + "s  %s", human(size), e.getKey(), Log.dim(e.getValue())));
                }
                Log.out(String.format(Locale.ROOT, "%9s  %s", human(total), Log.bold("total") + Log.dim(" in " + home)));
                return 0;
            }
            case "clean" -> {
                List<String> names = args.isEmpty() ? new ArrayList<>(FOLDERS.keySet()) : new ArrayList<>(args);
                for (String n : names) {
                    if (!FOLDERS.containsKey(n)) {
                        throw new JpmException("No cache named \"" + n + "\"; the caches are " + String.join(", ", FOLDERS.keySet()), 2);
                    }
                }
                long freed = 0;
                for (String n : names) {
                    Path dir = home.resolve(n);
                    if (!Files.isDirectory(dir)) continue;
                    freed += size(dir);
                    Fs.deleteTree(dir);
                }
                Log.step("Cleaned", (args.isEmpty() ? "jpm's caches" : String.join(", ", names)) + ": " + human(freed) + " freed");
                return 0;
            }
            default -> throw new JpmException("Unknown subcommand \"" + sub + "\". Usage: jpm cache size | jpm cache clean [cache...]", 2);
        }
    }

    static long size(Path dir) {
        long[] total = {0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return total[0];
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[u]);
    }
}
