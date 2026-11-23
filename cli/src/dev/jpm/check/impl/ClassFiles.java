package dev.jpm.check.impl;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ClassFiles {
    private ClassFiles() {
    }

    private static final Pattern DESCRIPTOR_TYPE = Pattern.compile("L([A-Za-z_$][\\w$]*(?:/[A-Za-z_$][\\w$]*)+)[;<]");

    public static Set<String> referencedClasses(Path classesDir) throws IOException {
        Set<String> out = new HashSet<>();
        if (!Files.isDirectory(classesDir)) return out;
        try (Stream<Path> s = Files.walk(classesDir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!p.toString().endsWith(".class")) continue;
                try (InputStream in = Files.newInputStream(p)) {
                    references(in, out);
                }
            }
        }
        return out;
    }

    public static Map<String, String> classesIn(Path jarOrDir) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (Files.isDirectory(jarOrDir)) {
            try (Stream<Path> s = Files.walk(jarOrDir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String rel = jarOrDir.relativize(p).toString().replace('\\', '/');
                    if (isClass(rel)) out.put(className(rel), rel);
                }
            }
        } else if (Files.isRegularFile(jarOrDir)) {
            try (ZipFile z = new ZipFile(jarOrDir.toFile())) {
                for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                    String name = e.nextElement().getName();
                    if (isClass(name)) out.put(className(name), name);
                }
            }
        }
        return out;
    }

    public static boolean hasEntry(Path jarOrDir, String entry) throws IOException {
        if (Files.isDirectory(jarOrDir)) return Files.exists(jarOrDir.resolve(entry));
        if (!Files.isRegularFile(jarOrDir)) return false;
        try (ZipFile z = new ZipFile(jarOrDir.toFile())) {
            return z.getEntry(entry) != null;
        }
    }

    private static boolean isClass(String entry) {
        return entry.endsWith(".class") && !entry.startsWith("META-INF/") && !entry.endsWith("module-info.class")
                && !entry.endsWith("package-info.class");
    }

    private static String className(String entry) {
        return entry.substring(0, entry.length() - ".class".length());
    }

    static void references(InputStream raw, Set<String> out) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw));
        if (in.readInt() != 0xCAFEBABE) return;
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classIndex = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 7 -> classIndex[i] = in.readUnsignedShort();
                case 8, 16, 19, 20 -> in.readUnsignedShort();
                case 3, 4 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                case 9, 10, 11, 12, 17, 18 -> in.readInt();
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        for (int i = 1; i < count; i++) {
            if (classIndex[i] != 0 && utf8[classIndex[i]] != null) {
                String n = utf8[classIndex[i]];
                if (n.startsWith("[")) descriptorTypes(n, out);
                else out.add(n);
            }
            if (utf8[i] != null && (utf8[i].indexOf(';') >= 0)) descriptorTypes(utf8[i], out);
        }
    }

    private static void descriptorTypes(String s, Set<String> out) {
        Matcher m = DESCRIPTOR_TYPE.matcher(s);
        while (m.find()) out.add(m.group(1));
    }
}
