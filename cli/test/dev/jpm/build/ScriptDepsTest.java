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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A .java script declares its own dependencies in //DEPS lines, resolved apart from the project. */
class ScriptDepsTest {
    @Test
    void readsDepsLines() {
        assertEquals(List.of("a:b:1.0", "c:d@2.0", "e:f:3"), Scripts.declaredDeps("""
                ///usr/bin/env jbang
                //DEPS a:b:1.0 c:d@2.0
                //DEPS e:f:3
                import java.util.List;
                //DEPS ignored:after:1
                """));
    }

    @Test
    void scriptsGetTheirDeps(@TempDir Path dir) throws Exception {
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Path repo = dir.resolve("repo"), classes = dir.resolve("classes");
        Fs.write(dir.resolve("src/lib/Greeter.java"), "package lib; public class Greeter { public static String hi() { return \"hi from lib\"; } }");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                dir.resolve("src/lib/Greeter.java").toString()));
        Path v = repo.resolve("g/lib/1.0");
        Fs.write(v.resolve("lib-1.0.pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>lib</artifactId><version>1.0</version></project>");
        Jars.write(v.resolve("lib-1.0.jar"), List.of(classes), Map.of());

        Path ws = dir.resolve("p");
        Fs.write(ws.resolve("project.jsonc"), "{ \"name\": \"p\", \"version\": \"1.0.0\", \"repositories\": [\"" + repo.toUri() + "\"],"
                + " \"scripts\": { \"hi\": \"Hi.java\", \"bad\": \"Bad.java\" } }");
        Fs.write(ws.resolve("Hi.java"), """
                //DEPS g:lib:1.0
                public class Hi {
                    public static void main(String[] a) throws Exception {
                        java.nio.file.Files.writeString(java.nio.file.Path.of("out.txt"), lib.Greeter.hi());
                    }
                }
                """);
        Fs.write(ws.resolve("Bad.java"), "//DEPS g:lib\npublic class Bad { public static void main(String[] a) { } }\n");
        Project p = Project.load(ws);
        MavenRepo r = new MavenRepo(p.root.repositories);
        Builder b = new Builder(p, Install.run(p, r, Install.Options.normal()), r, Env.Mode.DEVELOPMENT);
        assertEquals(0, Scripts.run(b, p.modules.get(0), "hi", List.of(), Env.Mode.DEVELOPMENT));
        assertEquals("hi from lib", Fs.read(ws.resolve("out.txt")));
        assertTrue(Fs.read(ws.resolve("project.lock")).indexOf("g:lib") < 0, "script dependencies are not the project's");
        JpmException e = assertThrows(JpmException.class, () -> Scripts.run(b, p.modules.get(0), "bad", List.of(), Env.Mode.DEVELOPMENT));
        assertTrue(e.getMessage().contains("needs a version"), e.getMessage());
    }
}
