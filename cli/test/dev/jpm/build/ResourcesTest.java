package dev.jpm.build;

import dev.jpm.Project;
import dev.jpm.build.impl.Resources;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Install;
import dev.jpm.toolchain.JavaSpec;
import dev.jpm.toolchain.Jdk;
import dev.jpm.toolchain.Toolchain;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

    @Test
    void substitutesPlaceholders() {
        assertEquals("v=1.2 ${UNKNOWN} $x", Resources.substitute("v=${VERSION} ${UNKNOWN} $x", Map.of("VERSION", "1.2")));
    }

    @Test
    void filtersListedResources(@TempDir Path dir) {
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Path p = dir.resolve("p");
        Fs.write(p.resolve("project.jsonc"), """
                { "name": "p", "version": "2.0.0", "filter": ["*.properties"],
                  "define": { "SPARK_VERSION": { "type": "string", "value": "1.10.182" } } }
                """);
        Fs.write(p.resolve("src/A.java"), "public class A { }");
        Fs.write(p.resolve("src/assets/spark-internal.properties"), "version=${SPARK_VERSION}\\nby=${NAME} ${VERSION}\\n");
        Fs.write(p.resolve("src/assets/raw.txt"), "${SPARK_VERSION}");
        Project project = Project.load(p);
        MavenRepo r = new MavenRepo(List.of());
        Builder b = new Builder(project, Install.run(project, r, Install.Options.normal()), r, Env.Mode.PRODUCTION);
        b.compileMain(project.modules.get(0));
        Path classes = Builder.classesDir(project.modules.get(0));
        assertEquals("version=1.10.182\\nby=p 2.0.0\\n", Fs.read(classes.resolve("spark-internal.properties")));
        assertEquals("${SPARK_VERSION}", Fs.read(classes.resolve("raw.txt")), "only listed resources are filtered");
    }

    @Test
    void filtersWithManifestAndPropertiesValues(@TempDir Path dir) {
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Path w = dir.resolve("w");
        Fs.write(w.resolve("project.jsonc"), """
                { "name": "w", "version": "3.1.0", "description": "root", "url": "https://root",
                  "extensions": { "x": { "tag": "r", "n": 2 } }, "workspace": ["app"] }
                """);
        Fs.write(w.resolve("gradle.properties"), "a=root\nb=root\nc=root\nurl=props\n");
        Fs.write(w.resolve("app/project.jsonc"), """
                { "name": "app", "description": "module", "filter": ["info.txt"] }
                """);
        Fs.write(w.resolve("app/gradle.properties"), "b=module\n");
        Fs.write(w.resolve("app/src/A.java"), "public class A { }");
        Fs.write(w.resolve("app/src/assets/info.txt"),
                "${name} ${version} ${description} ${url} ${extensions.x.tag} ${extensions.x.n} ${workspace.0} ${a} ${b} ${NAME}");
        Project project = Project.load(w);
        Project.Module app = project.modules.stream().filter(m -> m.name().equals("app")).findFirst().orElseThrow();
        MavenRepo r = new MavenRepo(List.of());
        Builder b = new Builder(project, Install.run(project, r, Install.Options.normal()), r, Env.Mode.PRODUCTION);
        b.compileMain(app);
        assertEquals("app 3.1.0 module props r 2 app root module app", Fs.read(Builder.classesDir(app).resolve("info.txt")));
    }
}
