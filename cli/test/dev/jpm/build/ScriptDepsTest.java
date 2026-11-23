package dev.jpm.build;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Install;
import dev.jpm.testing.TestRepo;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A .java script's own dependencies, listed with the script in the manifest and resolved apart from the project.
 */
class ScriptDepsTest {
    @Test
    void scriptsGetTheirDeps(@TempDir Path dir) throws Exception {
        Path repo = dir.resolve("repo"), classes = dir.resolve("classes");
        Fs.write(dir.resolve("src/lib/Greeter.java"), "package lib; public class Greeter { public static String hi() { return \"hi from lib\"; } }");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                dir.resolve("src/lib/Greeter.java").toString()));
        new TestRepo(repo).artifact("g:lib:1.0").entries(classes).publish();

        Path ws = dir.resolve("p");
        Fs.write(ws.resolve("project.jsonc"), "{ \"name\": \"p\", \"version\": \"1.0.0\", \"repositories\": [\"" + repo.toUri() + "\"],"
                + " \"scripts\": { \"hi\": { \"run\": \"Hi.java\", \"dependencies\": { \"g:lib\": \"1.0\" } }, \"plain\": \"Plain.java\" } }");
        Fs.write(ws.resolve("Hi.java"), """
                public class Hi {
                    public static void main(String[] a) throws Exception {
                        java.nio.file.Files.writeString(java.nio.file.Path.of("out.txt"), lib.Greeter.hi());
                    }
                }
                """);
        Fs.write(ws.resolve("Plain.java"), "public class Plain { public static void main(String[] a) { } }\n");
        Project p = Project.load(ws);
        MavenRepo r = new MavenRepo(p.root.repositories);
        Builder b = new Builder(p, Install.run(p, r, Install.Options.normal()), r, Env.Mode.DEVELOPMENT);
        assertEquals(0, Scripts.run(b, p.modules.get(0), "hi", List.of(), Env.Mode.DEVELOPMENT));
        assertEquals("hi from lib", Fs.read(ws.resolve("out.txt")));
        assertEquals(0, Scripts.run(b, p.modules.get(0), "plain", List.of(), Env.Mode.DEVELOPMENT));
        assertTrue(Fs.read(ws.resolve("project.lock")).indexOf("g:lib") < 0, "script dependencies are not the project's");
    }

    @Test
    void wrongScriptEntriesSayWhatIsExpected() {
        JpmException unknown = assertThrows(JpmException.class, () -> Manifest.parse(Path.of("project.jsonc"),
                "{ \"name\": \"p\", \"scripts\": { \"a\": { \"run\": \"A.java\", \"deps\": {} } } }"));
        assertEquals("unknown script option \"deps\"", unknown.getMessage());
        JpmException git = assertThrows(JpmException.class, () -> Manifest.parse(Path.of("project.jsonc"),
                "{ \"name\": \"p\", \"scripts\": { \"a\": { \"run\": \"A.java\", \"dependencies\": { \"github:me/x\": \"1.0\" } } } }"));
        assertEquals("script dependencies are Maven packages, not github:me/x", git.getMessage());
        assertThrows(JpmException.class, () -> Manifest.parse(Path.of("project.jsonc"), "{ \"name\": \"p\", \"scripts\": { \"a\": {} } }"));
    }
}
