package dev.jpm.build;

import dev.jpm.toolchain.Jdk;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Java Platform Module System support. A source root with {@code module-info.java} is a named module;
 * javac then resolves its {@code requires} from the module path, so dependencies are split:
 *
 * <ul>
 *   <li>module path: jars that are modules (a {@code module-info.class}, also in
 *       {@code META-INF/versions/N/}, or an {@code Automatic-Module-Name}), and any jar whose
 *       filename-derived automatic module name the module {@code requires};</li>
 *   <li>class path: the rest, which the module reads through {@code --add-reads <name>=ALL-UNNAMED}.</li>
 * </ul>
 */
public final class Modules {
    private Modules() {}

    /** A module declaration: its name and the modules it requires. */
    public record Declaration(String name, Set<String> requires) {}

    /** The module-info.java directly in one of the source roots, or null. */
    public static Path moduleInfo(List<Path> sourceRoots) {
        for (Path root : sourceRoots) {
            Path f = root.resolve("module-info.java");
            if (Files.isRegularFile(f)) return f;
        }
        return null;
    }

    /**
     * The name and requires of a {@code module-info.java}, as javac's parser reads it: a small program on
     * {@code jdk}'s source launcher (JDK 11 or newer) parses it with {@code com.sun.source}. jpm does not read
     * Java sources itself. The result is cached by the file's content, so the parser runs only when it changes.
     */
    public static Declaration declaration(Jdk jdk, Path moduleInfo) {
        String key = new dev.jpm.util.Hash().add(READER).addFile(moduleInfo).hex();
        Path cached = Fs.home().resolve("cache").resolve("module-info").resolve(key + ".txt");
        if (!Files.exists(cached)) {
            if (jdk.major() < 11) {
                throw JpmException.at(moduleInfo, 0, "jpm reads module-info.java with JDK 11 or newer; " + jdk.id() + " is older")
                        .withHint("set \"java\" to 11 or newer and \"target\" to the release you build for");
            }
            Path reader = Fs.home().resolve("cache").resolve("module-info").resolve("reader-" + dev.jpm.util.Hash.sha256(READER).substring(0, 12))
                    .resolve("ModuleInfoReader.java");
            if (!Files.exists(reader)) Fs.write(reader, READER);
            Exec.Result r = Exec.capture(moduleInfo.getParent(), List.of(jdk.java(), reader.toString(), moduleInfo.toString()));
            if (!r.ok()) throw JpmException.at(moduleInfo, 0, "javac could not read the module declaration:\n" + r.err().strip());
            Fs.write(cached, r.out());
        }
        String name = null;
        Set<String> requires = new LinkedHashSet<>();
        for (String line : Fs.read(cached).split("\n")) {
            if (line.startsWith("module ")) name = line.substring(7).strip();
            else if (line.startsWith("requires ")) requires.add(line.substring(9).strip());
        }
        return new Declaration(name, requires);
    }

    /** The name and requires of a compiled module, from its {@code module-info.class}; null when there is none. */
    public static Declaration compiled(Path classes) {
        Path f = classes.resolve("module-info.class");
        if (!Files.isRegularFile(f)) return null;
        try (var in = Files.newInputStream(f)) {
            java.lang.module.ModuleDescriptor d = java.lang.module.ModuleDescriptor.read(in);
            Set<String> requires = new LinkedHashSet<>();
            for (java.lang.module.ModuleDescriptor.Requires r : d.requires()) {
                if (!r.name().equals("java.base")) requires.add(r.name());
            }
            return new Declaration(d.name(), requires);
        } catch (java.io.IOException e) {
            throw new JpmException("Could not read " + f + ": " + e.getMessage());
        }
    }

    static final String READER = """
            import com.sun.source.tree.ModuleTree;
            import com.sun.source.tree.RequiresTree;
            import com.sun.source.util.JavacTask;
            import com.sun.source.util.TreeScanner;
            import javax.tools.JavaCompiler;
            import javax.tools.StandardJavaFileManager;
            import javax.tools.ToolProvider;

            public class ModuleInfoReader {
                public static void main(String[] args) throws Exception {
                    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
                    StandardJavaFileManager files = javac.getStandardFileManager(null, null, null);
                    JavacTask task = (JavacTask) javac.getTask(null, files, null, null, null, files.getJavaFileObjects(args[0]));
                    StringBuilder out = new StringBuilder();
                    for (var unit : task.parse()) {
                        new TreeScanner<Void, Void>() {
                            @Override
                            public Void visitModule(ModuleTree m, Void v) {
                                out.append("module ").append(m.getName()).append('\\n');
                                return super.visitModule(m, v);
                            }

                            @Override
                            public Void visitRequires(RequiresTree r, Void v) {
                                out.append("requires ").append(r.getModuleName()).append('\\n');
                                return null;
                            }
                        }.scan(unit, null);
                    }
                    System.out.print(out);
                }
            }
            """;

    /** The module name a jar declares (module-info or Automatic-Module-Name), or null if it is not a module. */
    public static String declaredName(Path jar) {
        if (Files.isDirectory(jar)) {
            Path mi = jar.resolve("module-info.class");
            return Files.exists(mi) ? nameFromClass(mi) : null;
        }
        if (!Files.isRegularFile(jar)) return null;
        try (JarFile jf = new JarFile(jar.toFile())) {
            ZipEntry mi = jf.getEntry("module-info.class");
            if (mi == null) {
                for (var en = jf.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (e.getName().matches("META-INF/versions/\\d+/module-info\\.class")) { mi = e; break; }
                }
            }
            if (mi != null) {
                try (var in = jf.getInputStream(mi)) {
                    String n = moduleNameFromBytes(in.readAllBytes());
                    if (n != null) return n;
                }
            }
            java.util.jar.Manifest mf = jf.getManifest();
            if (mf != null) {
                String auto = mf.getMainAttributes().getValue("Automatic-Module-Name");
                if (auto != null && !auto.isBlank()) return auto.trim();
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /** The name in a jar's module-info.class (also under META-INF/versions/N/), or null: not an automatic name. */
    public static String descriptorName(Path jar) {
        if (!Files.isRegularFile(jar)) return null;
        try (JarFile jf = new JarFile(jar.toFile())) {
            ZipEntry mi = jf.getEntry("module-info.class");
            if (mi == null) {
                for (var en = jf.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (e.getName().matches("META-INF/versions/\\d+/module-info\\.class")) { mi = e; break; }
                }
            }
            if (mi == null) return null;
            try (var in = jf.getInputStream(mi)) {
                return moduleNameFromBytes(in.readAllBytes());
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static String nameFromClass(Path moduleInfoClass) {
        try {
            return moduleNameFromBytes(Files.readAllBytes(moduleInfoClass));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Reads the module name from a module-info.class: the {@code Module} attribute's {@code module_name_index}.
     *
     * <p>Parsed by hand rather than with {@link java.lang.module.ModuleDescriptor#read}, which rejects class
     * files newer than the JDK jpm runs on: the native binary is built with one JDK, and a module compiled
     * for a newer Java would read as no module at all. The constant pool and the {@code Module} attribute
     * have kept their format since class file 53 (Java 9), and only the name is needed.
     */
    static String moduleNameFromBytes(byte[] b) {
        try {
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(b));
            if (in.readInt() != 0xCAFEBABE) return null;
            in.readUnsignedShort();
            if (in.readUnsignedShort() < 53) return null;
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] moduleNameIndex = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7, 8, 16, 20 -> in.skipNBytes(2);
                    case 19 -> moduleNameIndex[i] = in.readUnsignedShort();
                    case 15 -> in.skipNBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                    case 5, 6 -> {
                        in.skipNBytes(8);
                        i++;
                    }
                    default -> {
                        return null;
                    }
                }
            }
            in.skipNBytes(6);
            in.skipNBytes(2L * in.readUnsignedShort());
            for (int members = 0; members < 2; members++) {
                int n = in.readUnsignedShort();
                for (int i = 0; i < n; i++) {
                    in.skipNBytes(6);
                    int attrs = in.readUnsignedShort();
                    for (int a = 0; a < attrs; a++) {
                        in.skipNBytes(2);
                        in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
                    }
                }
            }
            int attrs = in.readUnsignedShort();
            for (int a = 0; a < attrs; a++) {
                int nameIndex = in.readUnsignedShort();
                long length = Integer.toUnsignedLong(in.readInt());
                if (nameIndex < count && "Module".equals(utf8[nameIndex])) {
                    int module = in.readUnsignedShort();
                    if (module <= 0 || module >= count) return null;
                    int name = moduleNameIndex[module];
                    if (name <= 0 || name >= count || utf8[name] == null) return null;
                    return utf8[name];
                }
                in.skipNBytes(length);
            }
            return null;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The automatic module name the JDK derives from a jar file name: drop ".jar" and a trailing version,
     * turn other characters into dots.
     */
    public static String automaticName(Path jar) {
        String n = jar.getFileName().toString();
        if (n.toLowerCase(Locale.ROOT).endsWith(".jar")) n = n.substring(0, n.length() - 4);
        Matcher v = Pattern.compile("-(\\d+(\\.|$))").matcher(n);
        if (v.find()) n = n.substring(0, v.start());
        n = n.replaceAll("[^A-Za-z0-9]", ".").replaceAll("\\.{2,}", ".").replaceAll("^\\.|\\.$", "");
        return n;
    }

    /** Splits dependencies into module path and class path for compiling the declared module. */
    public static List<List<Path>> split(Declaration module, List<Path> deps) {
        List<Path> modulePath = new ArrayList<>(), classPath = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        for (Path p : deps) {
            String name = declaredName(p);
            if (name == null && module.requires().contains(automaticName(p))) name = automaticName(p);
            if (name != null && placed.add(name)) modulePath.add(p);
            else classPath.add(p);
        }
        return List.of(modulePath, classPath);
    }
}
