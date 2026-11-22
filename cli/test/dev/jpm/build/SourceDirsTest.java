package dev.jpm.build;

import dev.jpm.Project;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Install;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "sources" and "resources": several folders per module, such as committed generated sources beside the rest. */
class SourceDirsTest {
    @Test
    void compilesEverySourceFolder(@TempDir Path dir) throws Exception {
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Path p = dir.resolve("p");
        Fs.write(p.resolve("project.jsonc"), """
                { "name": "p", "version": "1.0.0", "layout": "maven",
                  "sources": ["src/main/java", "src/generated/java"],
                  "resources": ["src/main/resources", "src/generated/resources"] }
                """);
        Fs.write(p.resolve("src/main/java/app/App.java"), "package app; public class App { gen.Registry r = new gen.Registry(); }");
        Fs.write(p.resolve("src/generated/java/gen/Registry.java"), "package gen; public class Registry { }");
        Fs.write(p.resolve("src/main/resources/a.txt"), "main");
        Fs.write(p.resolve("src/main/resources/both.txt"), "main wins");
        Fs.write(p.resolve("src/generated/resources/b.txt"), "generated");
        Fs.write(p.resolve("src/generated/resources/both.txt"), "generated");
        Project project = Project.load(p);
        assertEquals(List.of(p.resolve("src/main/java"), p.resolve("src/generated/java")), project.modules.get(0).srcDirs());
        MavenRepo r = new MavenRepo(List.of());
        Builder b = new Builder(project, Install.run(project, r, Install.Options.normal()), r, Env.Mode.PRODUCTION);
        Path jar = b.jar(project.modules.get(0));
        try (JarFile jf = new JarFile(jar.toFile())) {
            Set<String> names = jf.stream().map(java.util.zip.ZipEntry::getName).collect(Collectors.toSet());
            assertTrue(names.containsAll(Set.of("app/App.class", "gen/Registry.class", "a.txt", "b.txt", "both.txt")), names.toString());
            assertEquals("main wins", new String(jf.getInputStream(jf.getEntry("both.txt")).readAllBytes()));
        }
    }

    @Test
    void foldersStayInsideTheModule(@TempDir Path dir) {
        Fs.write(dir.resolve("project.jsonc"), "{ \"name\": \"p\", \"sources\": [\"../elsewhere\"] }");
        assertThrows(JpmException.class, () -> Project.load(dir));
    }
}
