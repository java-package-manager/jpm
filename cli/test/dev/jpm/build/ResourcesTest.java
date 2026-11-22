package dev.jpm.build;

import dev.jpm.toolchain.JavaSpec;
import dev.jpm.toolchain.Jdk;
import dev.jpm.toolchain.Toolchain;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcesTest {
    @Test
    void assetsAreCopiedNextToClassesAndRemovedAgain(@TempDir Path dir) {
        Path assets = dir.resolve("src/assets");
        Fs.write(assets.resolve("demo/icon.png"), "png");
        Fs.write(assets.resolve("META-INF/services/p.Api"), "p.Impl");
        Path out = dir.resolve("classes");
        Fs.write(out.resolve("p/App.class"), "class");
        Path state = dir.resolve("resources.list");

        assertTrue(Resources.sync(List.of(assets), out, state));
        assertEquals("png", Fs.read(out.resolve("demo/icon.png")));
        assertEquals("p.Impl", Fs.read(out.resolve("META-INF/services/p.Api")));
        assertFalse(Resources.sync(List.of(assets), out, state), "nothing changed");

        try {
            Files.delete(assets.resolve("demo/icon.png"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertTrue(Resources.sync(List.of(assets), out, state));
        assertFalse(Files.exists(out.resolve("demo/icon.png")));
        assertTrue(Files.exists(out.resolve("p/App.class")), "classes are left alone");
    }

    @Test
    void javaFilesInAssetsAreNotCompiled(@TempDir Path dir) {
        Jdk jdk = Toolchain.require(JavaSpec.any());
        Path src = dir.resolve("src");
        Fs.write(src.resolve("p/A.java"), "package p; public class A {}");
        Fs.write(src.resolve("assets/templates/Gen.java"), "this is a template, not Java");
        assertTrue(Compiler.compile(new Compiler.Request("t", jdk, jdk.major(), List.of(src), List.of(), List.of(),
                dir.resolve("classes"), dir.resolve("state.json"), List.of(), "k", List.of(src.resolve("assets")))));
        assertTrue(Files.exists(dir.resolve("classes/p/A.class")));
    }
}
