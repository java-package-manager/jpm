package dev.jpm.build;

import dev.jpm.Project;
import dev.jpm.build.impl.Relocator;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Install;
import dev.jpm.toolchain.JavaSpec;
import dev.jpm.toolchain.Jdk;
import dev.jpm.toolchain.Toolchain;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "relocate": a flat bundle with a dependency moved to another package, which still runs.
 */
class RelocateTest {
    @Test
    void rewritesNames() {
        Relocator r = new Relocator(Map.of("org.yaml.snakeyaml", "x.shaded.snakeyaml"));
        assertEquals("x/shaded/snakeyaml/Yaml.class", r.entryName("org/yaml/snakeyaml/Yaml.class"));
        assertEquals("META-INF/versions/9/x/shaded/snakeyaml/internal/Logger.class",
                r.entryName("META-INF/versions/9/org/yaml/snakeyaml/internal/Logger.class"));
        assertEquals("org/yaml/snakeyamlx/A.class", r.entryName("org/yaml/snakeyamlx/A.class"), "only whole package names");
        assertEquals("META-INF/services/x.shaded.snakeyaml.Spi", r.entryName("META-INF/services/org.yaml.snakeyaml.Spi"));
        assertEquals("(Lx/shaded/snakeyaml/Yaml;[Lx/shaded/snakeyaml/A;)Ljava/util/List<Lx/shaded/snakeyaml/B;>;",
                r.symbol("(Lorg/yaml/snakeyaml/Yaml;[Lorg/yaml/snakeyaml/A;)Ljava/util/List<Lorg/yaml/snakeyaml/B;>;"));
        assertEquals("x.shaded.snakeyaml.Yaml", r.string("org.yaml.snakeyaml.Yaml"));
        assertEquals("/x/shaded/snakeyaml/res.txt", r.string("/org/yaml/snakeyaml/res.txt"));
        assertEquals("hello org.yaml", r.string("hello org.yaml"));
    }

    @Test
    void bundleRunsWithTheDependencyRelocated(@TempDir Path dir) throws Exception {
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Jdk jdk = Toolchain.require(JavaSpec.any());
        Path libSrc = dir.resolve("lib-src"), libClasses = dir.resolve("lib-classes");
        Fs.write(libSrc.resolve("org/dep/Spi.java"), "package org.dep; public interface Spi { String hi(); }");
        Fs.write(libSrc.resolve("org/dep/SpiImpl.java"), "package org.dep; public class SpiImpl implements Spi { public String hi() { return \"spi\"; } }");
        Fs.write(libSrc.resolve("org/dep/Dep.java"), """
                package org.dep;
                public class Dep {
                    public static String all() throws Exception {
                        Object h = Class.forName("org.dep.Helper").getDeclaredConstructor().newInstance();
                        String res = new String(Dep.class.getResourceAsStream("/org/dep/data.txt").readAllBytes()).strip();
                        String spi = java.util.ServiceLoader.load(Spi.class).findFirst().orElseThrow().hi();
                        return h + " " + res + " " + spi + " " + Dep.class.getName();
                    }
                }
                """);
        Fs.write(libSrc.resolve("org/dep/Helper.java"), "package org.dep; public class Helper { public String toString() { return \"helper\"; } }");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", libClasses.toString(),
                libSrc.resolve("org/dep/Spi.java").toString(), libSrc.resolve("org/dep/SpiImpl.java").toString(),
                libSrc.resolve("org/dep/Dep.java").toString(), libSrc.resolve("org/dep/Helper.java").toString()));
        Fs.write(libClasses.resolve("org/dep/data.txt"), "data");
        Fs.write(libClasses.resolve("META-INF/services/org.dep.Spi"), "# the implementation\norg.dep.SpiImpl\n");
        Path repo = dir.resolve("repo"), v = repo.resolve("g/dep/1.0");
        Fs.write(v.resolve("dep-1.0.pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>dep</artifactId><version>1.0</version></project>");
        Jars.write(v.resolve("dep-1.0.jar"), List.of(libClasses), Map.of());

        Path app = dir.resolve("app");
        Fs.write(app.resolve("project.jsonc"), "{ \"name\": \"app\", \"version\": \"1.0.0\", \"main\": \"app.Main\","
                + " \"repositories\": [\"" + repo.toUri() + "\"], \"runtime\": { \"g:dep\": \"1.0\" },"
                + " \"relocate\": { \"org.dep\": \"app.shaded.dep\" } }");
        Fs.write(app.resolve("src/app/Main.java"), "package app; public class Main { public static void main(String[] a) throws Exception { System.out.println(org.dep.Dep.all()); } }");
        Project p = Project.load(app);
        MavenRepo r = new MavenRepo(p.root.repositories);
        Builder b = new Builder(p, Install.run(p, r, Install.Options.normal()), r, Env.Mode.PRODUCTION);
        Path bundle = b.flatBundle(p.modules.get(0));
        try (JarFile jf = new JarFile(bundle.toFile())) {
            Set<String> names = jf.stream().map(java.util.zip.ZipEntry::getName).collect(Collectors.toSet());
            assertTrue(names.containsAll(Set.of("app/shaded/dep/Dep.class", "app/shaded/dep/data.txt", "META-INF/services/app.shaded.dep.Spi")), names.toString());
            assertFalse(names.stream().anyMatch(n -> n.startsWith("org/dep/")), names.toString());
        }
        Exec.Result run = Exec.capture(dir, List.of(jdk.java(), "-jar", bundle.toString()));
        assertEquals("helper data spi app.shaded.dep.Dep", run.out().strip(), run.err());
    }
}
