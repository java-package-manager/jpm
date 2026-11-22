package dev.jpm.build;


import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModulesTest {
    @Test
    void parsesModuleInfo(@TempDir Path dir) {
        Fs.write(dir.resolve("module-info.java"), """
                /* comment: module fake { */
                open module com.example.app {
                    requires static org.jspecify; // nullness
                    requires transitive java.sql;
                    requires org.apache.commons.lang3;
                    exports com.example.app.api;
                }
                """);
        dev.jpm.toolchain.Jdk jdk = dev.jpm.toolchain.Toolchain.require(dev.jpm.toolchain.JavaSpec.any());
        Modules.Declaration d = Modules.declaration(jdk, Modules.moduleInfo(List.of(dir)));
        assertEquals("com.example.app", d.name());
        assertEquals(Set.of("org.jspecify", "java.sql", "org.apache.commons.lang3"), d.requires());
        Fs.write(dir.resolve("module-info.java"), """
                @Deprecated(forRemoval = false, since = "requires fake;")
                module com.example.app {
                    requires /* requires fake; */ org.jspecify;
                    requires
                        java.sql;
                }
                """);
        d = Modules.declaration(jdk, dir.resolve("module-info.java"));
        assertEquals(Set.of("org.jspecify", "java.sql"), d.requires(), "javac's parser, not a pattern, reads the declaration");
    }

    @Test
    void derivesAutomaticNames() {
        assertEquals("commons.lang3", Modules.automaticName(Path.of("commons-lang3-3.14.0.jar")));
        assertEquals("javax.inject", Modules.automaticName(Path.of("javax.inject-1.jar")));
        assertEquals("foo.bar", Modules.automaticName(Path.of("foo_bar-2.0-SNAPSHOT.jar")));
    }

    @Test
    void splitsModulePathFromClassPath(@TempDir Path dir) throws Exception {
        Path named = jar(dir.resolve("named-1.0.jar"), "org.named");
        Path plain = jar(dir.resolve("plain-lib-2.0.jar"), null);
        Path required = jar(dir.resolve("needed-3.0.jar"), null);
        assertEquals("org.named", Modules.declaredName(named));
        assertNull(Modules.declaredName(plain));
        Modules.Declaration d = new Modules.Declaration("app", Set.of("needed"));
        List<List<Path>> split = Modules.split(d, List.of(named, plain, required));
        assertEquals(List.of(named, required), split.get(0));
        assertEquals(List.of(plain), split.get(1));
    }

    /**
     * A module-info.class from a newer Java than the one jpm runs on still names its module: the native
     * binary is built with an older JDK than the code it compiles.
     */
    @Test
    void readsModuleDescriptorsOfAnyClassFileVersion(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src");
        Fs.write(src.resolve("module-info.java"), """
                module fr.fidorial.example {
                    requires java.sql;
                    exports fr.fidorial.example;
                }
                """);
        Fs.write(src.resolve("fr/fidorial/example/A.java"), "package fr.fidorial.example; public class A { long l = 1L; double d = 2.0; }");
        Path out = dir.resolve("classes");
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(),
                src.resolve("module-info.java").toString(), src.resolve("fr/fidorial/example/A.java").toString());
        assertEquals(0, rc);
        byte[] compiled = Files.readAllBytes(out.resolve("module-info.class"));
        assertEquals("fr.fidorial.example", Modules.moduleNameFromBytes(compiled));
        for (int major : new int[] {69, 100}) {
            byte[] newer = compiled.clone();
            newer[6] = (byte) (major >> 8);
            newer[7] = (byte) major;
            assertEquals("fr.fidorial.example", Modules.moduleNameFromBytes(newer), "class file " + major);
            Path classes = dir.resolve("classes-" + major);
            Files.createDirectories(classes);
            Files.write(classes.resolve("module-info.class"), newer);
            assertEquals("fr.fidorial.example", Modules.declaredName(classes));
            Path jar = dir.resolve("example-" + major + ".jar");
            Manifest mf = new Manifest();
            mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            try (JarOutputStream jo = new JarOutputStream(Files.newOutputStream(jar), mf)) {
                jo.putNextEntry(new java.util.zip.ZipEntry("module-info.class"));
                jo.write(newer);
                jo.closeEntry();
            }
            assertEquals("fr.fidorial.example", Modules.declaredName(jar));
        }
        assertNull(Modules.moduleNameFromBytes(new byte[] {(byte) 0xCA, (byte) 0xFE}));
        assertNull(Modules.moduleNameFromBytes(Files.readAllBytes(out.resolve("fr/fidorial/example/A.class"))));
    }

    private static Path jar(Path p, String automaticName) throws Exception {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (automaticName != null) mf.getMainAttributes().putValue("Automatic-Module-Name", automaticName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p), mf)) {
            out.putNextEntry(new java.util.zip.ZipEntry("x/Y.class"));
            out.closeEntry();
        }
        return p;
    }
}
