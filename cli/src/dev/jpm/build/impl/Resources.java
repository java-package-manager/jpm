package dev.jpm.build.impl;

import dev.jpm.api.json.Jsonc;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Resources {
    private Resources() {}

    public static TreeMap<String, Path> find(List<Path> roots) {
        TreeMap<String, Path> out = new TreeMap<>();
        for (Path root : roots) {
            for (Path f : Fs.files(root, null)) {
                out.putIfAbsent(Fs.slash(root.relativize(f)), f);
            }
        }
        return out;
    }

    public static boolean sync(List<Path> roots, Path out, Path stateFile) {
        return sync(roots, out, stateFile, List.of(), Map.of());
    }

    public static boolean sync(List<Path> roots, Path out, Path stateFile, List<String> filter, Map<String, String> values) {
        List<PathMatcher> matchers = filter.stream().map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g)).toList();
        TreeMap<String, Path> now = find(roots);
        TreeSet<String> before = new TreeSet<>();
        if (Files.isRegularFile(stateFile)) {
            for (String line : Fs.read(stateFile).split("\n")) if (!line.isBlank()) before.add(line);
        }
        boolean changed = false;
        try {
            for (String rel : before) {
                if (!now.containsKey(rel) && Files.deleteIfExists(out.resolve(rel))) changed = true;
            }
            for (var e : now.entrySet()) {
                Path target = out.resolve(e.getKey());
                Path src = e.getValue();
                Path rel = Path.of(e.getKey());
                if (matchers.stream().anyMatch(mt -> mt.matches(rel))) {
                    byte[] filtered = substitute(Files.readString(src), values).getBytes(StandardCharsets.UTF_8);
                    if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), filtered)) continue;
                    Fs.mkdirs(target.getParent());
                    Files.write(target, filtered);
                    changed = true;
                    continue;
                }
                if (same(src, target)) continue;
                Fs.mkdirs(target.getParent());
                Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                changed = true;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String list = String.join("\n", new ArrayList<>(now.keySet()));
        if (!Files.isRegularFile(stateFile) || !Fs.read(stateFile).equals(list)) Fs.write(stateFile, list);
        return changed;
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)}");

    public static String substitute(String text, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static void flatten(Jsonc.Node node, String prefix, Map<String, String> out) {
        if (node == null) return;
        if (node.isObject()) {
            for (Jsonc.Member mb : node.members.values()) {
                if (prefix.isEmpty() && mb.key.equals("$schema")) continue;
                flatten(mb.value, prefix.isEmpty() ? mb.key : prefix + "." + mb.key, out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.items.size(); i++) flatten(node.items.get(i), prefix + "." + i, out);
        } else if (node.value != null && !prefix.isEmpty()) {
            out.put(prefix, node.value instanceof java.math.BigDecimal bd ? bd.toPlainString() : node.value.toString());
        }
    }

    public static Map<String, String> properties(Path dir) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return out;
        List<Path> files;
        try (var s = Files.list(dir)) {
            files = s.filter(f -> f.getFileName().toString().endsWith(".properties") && Files.isRegularFile(f)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path f : files) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException | IllegalArgumentException e) {
                throw new JpmException(f + ": cannot read properties: " + e.getMessage());
            }
            for (String k : new TreeSet<>(p.stringPropertyNames())) out.put(k, p.getProperty(k));
        }
        return out;
    }

    private static boolean same(Path src, Path target) throws IOException {
        if (!Files.isRegularFile(target) || Files.size(target) != Files.size(src)) return false;
        if (Files.getLastModifiedTime(target).toMillis() == Files.getLastModifiedTime(src).toMillis()) return true;
        if (Files.mismatch(src, target) != -1) return false;
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(src));
        return true;
    }

}
