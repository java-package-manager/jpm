package dev.jpm.build;

import dev.jpm.build.impl.Relocator;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Writes reproducible jars.
 */
public final class Jars {
    private Jars() {
    }

    /**
     * 1980-02-01, the conventional reproducible-build timestamp.
     */
    private static final long EPOCH = 315532800000L + 31L * 24 * 3600 * 1000;

    /**
     * Writes a jar from directories (merged in order, earlier wins) with the given manifest attributes.
     */
    public static void write(Path jar, List<Path> dirs, Map<String, String> attrs) {
        Map<String, Path> entries = new TreeMap<>();
        for (Path d : dirs) {
            for (Path f : Fs.files(d, null)) {
                String name = Fs.slash(d.relativize(f));
                if (name.equals("META-INF/MANIFEST.MF")) continue;
                entries.putIfAbsent(name, f);
            }
        }
        Fs.mkdirs(jar.getParent());
        Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
        try (OutputStream os = Files.newOutputStream(tmp); JarOutputStream out = new JarOutputStream(os)) {
            java.util.jar.Manifest mf = new java.util.jar.Manifest();
            mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            mf.getMainAttributes().putValue("Created-By", "jpm");
            for (Map.Entry<String, String> e : attrs.entrySet()) mf.getMainAttributes().putValue(e.getKey(), e.getValue());
            JarEntry me = new JarEntry("META-INF/MANIFEST.MF");
            me.setTime(EPOCH);
            out.putNextEntry(me);
            mf.write(out);
            out.closeEntry();
            Set<String> dirsWritten = new java.util.HashSet<>();
            dirsWritten.add("META-INF/");
            for (Map.Entry<String, Path> e : entries.entrySet()) {
                String name = e.getKey();
                for (int i = name.indexOf('/'); i >= 0; i = name.indexOf('/', i + 1)) {
                    String dir = name.substring(0, i + 1);
                    if (dirsWritten.add(dir)) {
                        JarEntry de = new JarEntry(dir);
                        de.setTime(EPOCH);
                        out.putNextEntry(de);
                        out.closeEntry();
                    }
                }
                JarEntry je = new JarEntry(name);
                je.setTime(EPOCH);
                out.putNextEntry(je);
                Files.copy(e.getValue(), out);
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new JpmException("Could not write " + jar + ": " + e.getMessage());
        }
        try {
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new JpmException("Could not write " + jar + ": " + e.getMessage());
        }
    }

    /**
     * Merges jars and class directories into one flat jar, the way shadow jars are made: the first copy of
     * a file wins, except service files (META-INF/services/*), whose lines are joined. Their manifests,
     * signatures (which no longer match) and module descriptors (the merged jar is not those modules) are
     * left out. Entries are written in name order with fixed times, so the same inputs give the same jar.
     * Returns how many entries were written.
     */
    public static int merge(Path jar, List<Path> inputs, Map<String, String> attrs) {
        return merge(jar, inputs, attrs, new Relocator(Map.of()));
    }

    /**
     * Like {@link #merge(Path, List, Map)}, with every entry (the app's own included) relocated.
     */
    public static int merge(Path jar, List<Path> inputs, Map<String, String> attrs, Relocator relocator) {
        Map<String, byte[]> entries = new TreeMap<>();
        Map<String, java.util.LinkedHashSet<String>> services = new TreeMap<>();
        boolean first = true;
        for (Path in : inputs) {
            boolean own = first;
            first = false;
            forEachEntry(in, (original, raw) -> {
                if (original.endsWith("/") || original.equals("META-INF/MANIFEST.MF")) return;
                String name = relocator.isEmpty() ? original : relocator.entryName(original);
                byte[] bytes = relocator.entry(original, raw);
                String upper = name.toUpperCase(java.util.Locale.ROOT);
                if (upper.startsWith("META-INF/") && !upper.substring(9).contains("/")
                        && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))) return;
                if (!own && (name.equals("module-info.class") || name.endsWith("/module-info.class"))) return;
                if (name.startsWith("META-INF/services/") && name.indexOf('/', 18) < 0) {
                    java.util.LinkedHashSet<String> lines = services.computeIfAbsent(name, k -> new java.util.LinkedHashSet<>());
                    for (String line : new String(bytes, java.nio.charset.StandardCharsets.UTF_8).split("\r?\n")) {
                        String t = line.strip();
                        if (!t.isEmpty() && !t.startsWith("#")) lines.add(t);
                    }
                    return;
                }
                entries.putIfAbsent(name, bytes);
            });
        }
        for (Map.Entry<String, java.util.LinkedHashSet<String>> e : services.entrySet()) {
            entries.put(e.getKey(), (String.join("\n", e.getValue()) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Fs.mkdirs(jar.getParent());
        Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
        try (OutputStream os = Files.newOutputStream(tmp); JarOutputStream out = new JarOutputStream(os)) {
            java.util.jar.Manifest mf = new java.util.jar.Manifest();
            mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            mf.getMainAttributes().putValue("Created-By", "jpm");
            for (Map.Entry<String, String> e : attrs.entrySet()) mf.getMainAttributes().putValue(e.getKey(), e.getValue());
            JarEntry me = new JarEntry("META-INF/MANIFEST.MF");
            me.setTime(EPOCH);
            out.putNextEntry(me);
            mf.write(out);
            out.closeEntry();
            Set<String> dirsWritten = new java.util.HashSet<>();
            dirsWritten.add("META-INF/");
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                String name = e.getKey();
                for (int i = name.indexOf('/'); i >= 0; i = name.indexOf('/', i + 1)) {
                    String dir = name.substring(0, i + 1);
                    if (dirsWritten.add(dir)) {
                        JarEntry de = new JarEntry(dir);
                        de.setTime(EPOCH);
                        out.putNextEntry(de);
                        out.closeEntry();
                    }
                }
                JarEntry je = new JarEntry(name);
                je.setTime(EPOCH);
                out.putNextEntry(je);
                out.write(e.getValue());
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new JpmException("Could not write " + jar + ": " + e.getMessage());
        }
        try {
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new JpmException("Could not write " + jar + ": " + e.getMessage());
        }
        return entries.size();
    }

    private static void forEachEntry(Path in, java.util.function.BiConsumer<String, byte[]> each) {
        try {
            if (Files.isDirectory(in)) {
                for (Path f : Fs.files(in, null)) each.accept(Fs.slash(in.relativize(f)), Files.readAllBytes(f));
                return;
            }
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(in.toFile())) {
                var en = zf.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry ze = en.nextElement();
                    if (ze.isDirectory()) continue;
                    try (java.io.InputStream is = zf.getInputStream(ze)) {
                        each.accept(ze.getName(), is.readAllBytes());
                    }
                }
            }
        } catch (IOException e) {
            throw new JpmException("Could not read " + in + ": " + e.getMessage());
        }
    }

    /**
     * Copies a jar, dropping entries that match {@code drop} and adding files from {@code overlay}.
     */
    public static void rewrite(Path in, Path outJar, Predicate<String> drop, Path overlay) {
        Fs.mkdirs(outJar.getParent());
        Map<String, Path> add = new LinkedHashMap<>();
        for (Path f : Fs.files(overlay, null)) add.put(Fs.slash(overlay.relativize(f)), f);
        Path tmp = outJar.resolveSibling(outJar.getFileName() + ".tmp");
        try (ZipFile zf = new ZipFile(in.toFile());
             OutputStream os = Files.newOutputStream(tmp);
             JarOutputStream out = new JarOutputStream(os)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (add.containsKey(n) || drop.test(n)) continue;
                if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))) continue;
                ZipEntry ne = new ZipEntry(n);
                ne.setTime(EPOCH);
                out.putNextEntry(ne);
                if (!e.isDirectory()) try (InputStream is = zf.getInputStream(e)) {
                    is.transferTo(out);
                }
                out.closeEntry();
            }
            for (Map.Entry<String, Path> e : add.entrySet()) {
                ZipEntry ne = new ZipEntry(e.getKey());
                ne.setTime(EPOCH);
                out.putNextEntry(ne);
                Files.copy(e.getValue(), out);
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new JpmException("Could not rewrite " + in + ": " + e.getMessage());
        }
        try {
            Files.move(tmp, outJar, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new JpmException("Could not write " + outJar + ": " + e.getMessage());
        }
    }

    /**
     * Unpacks a jar into a folder, skipping entries that would land outside it.
     */
    public static void extract(Path jar, Path dir) {
        dir = dir.toAbsolutePath().normalize();
        Fs.mkdirs(dir);
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                Path target = dir.resolve(e.getName()).normalize();
                if (!target.startsWith(dir)) continue;
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream is = zf.getInputStream(e)) {
                        Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (IOException e) {
            throw new JpmException("Could not extract " + jar + ": " + e.getMessage());
        }
    }

    /**
     * Main-Class from a jar's manifest, or null.
     */
    public static String mainClass(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            java.util.jar.Manifest mf = jf.getManifest();
            return mf == null ? null : mf.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
        } catch (IOException e) {
            return null;
        }
    }
}
