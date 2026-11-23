package dev.jpm.build;

import dev.jpm.BuildConfig;
import dev.jpm.Project;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Install;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Scripts and hooks can call back into the jpm that runs them.
 */
class ScriptEnvTest {
    @Test
    void scriptsGetJpmExe(@TempDir Path dir) {
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Path ws = dir.resolve("p");
        Fs.write(ws.resolve("project.jsonc"), "{ \"name\": \"p\", \"version\": \"1.0.0\", \"scripts\": { \"env\": \"Env.java\" } }");
        Fs.write(ws.resolve("Env.java"), """
                public class Env {
                    public static void main(String[] a) throws Exception {
                        java.nio.file.Files.writeString(java.nio.file.Path.of("out.txt"),
                                System.getenv("JPM_EXE") + "\\n" + System.getenv("JPM_VERSION_OF_JPM"));
                    }
                }
                """);
        Project p = Project.load(ws);
        MavenRepo r = new MavenRepo(List.of());
        Builder b = new Builder(p, Install.run(p, r, Install.Options.normal()), r, Env.Mode.DEVELOPMENT);
        assertEquals(0, Scripts.run(b, p.modules.get(0), "env", List.of(), Env.Mode.DEVELOPMENT));
        String[] out = Fs.read(ws.resolve("out.txt")).split("\n");
        assertFalse(out[0].isBlank() || out[0].equals("null"), "JPM_EXE: " + out[0]);
        assertEquals(dev.jpm.util.Self.exe(), out[0]);
        assertEquals(BuildConfig.VERSION, out[1]);
    }
}
