package dev.jpm.cli.impl;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.util.Fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class Watch {
    private Watch() {}

    static String snapshot(Project p, boolean tests) {
        return snapshot(p, tests, f -> false);
    }

    static boolean code(Path f) {
        String n = f.getFileName().toString();
        return n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".groovy") || n.endsWith(".scala");
    }

    static String code(Project.Module m) {
        StringBuilder sb = new StringBuilder();
        for (Path r : m.srcDirs()) {
            if (!Files.isDirectory(r)) continue;
            for (Path f : Fs.files(r, null)) {
                if (!code(f)) continue;
                try {
                    sb.append(f).append(' ').append(Files.size(f)).append(' ').append(Files.getLastModifiedTime(f).toMillis()).append('\n');
                } catch (IOException ignored) {
                }
            }
        }
        return Integer.toHexString(Objects.hash(sb.toString())) + sb.length();
    }

    static String snapshot(Project p, boolean tests, java.util.function.Predicate<Path> skip) {
        List<Path> roots = new ArrayList<>();
        roots.add(p.dir.resolve(Manifest.FILE));
        for (String f : List.of(".env", ".env.development", ".env.local")) roots.add(p.dir.resolve(f));
        for (Project.Module m : p.modules) {
            roots.add(m.dir.resolve(Manifest.FILE));
            roots.addAll(m.srcDirs());
            for (Path a : m.assetsDirs()) if (m.srcDirs().stream().noneMatch(a::startsWith)) roots.add(a);
            roots.add(m.dir.resolve("patches"));
            if (tests) {
                roots.addAll(m.testDirs());
                roots.add(m.testResourcesDir());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Path r : roots) {
            List<Path> files = Files.isDirectory(r) ? Fs.files(r, null) : Files.exists(r) ? List.of(r) : List.of();
            for (Path f : files) {
                if (skip.test(f)) continue;
                try {
                    sb.append(f).append(' ').append(Files.size(f)).append(' ').append(Files.getLastModifiedTime(f).toMillis()).append('\n');
                } catch (IOException ignored) {
                }
            }
        }
        return Integer.toHexString(Objects.hash(sb.toString())) + sb.length();
    }
}
