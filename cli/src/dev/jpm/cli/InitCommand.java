package dev.jpm.cli;

import dev.jpm.Manifest;
import dev.jpm.toolchain.JavaSpec;
import dev.jpm.toolchain.Jdk;
import dev.jpm.toolchain.Toolchain;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class InitCommand extends Command {

    InitCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "init";
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.PROJECT, "init [name]", "Create a project in the current directory",
                        "Create a project"));
    }

    @Override
    int run(List<String> args) {
        noMoreFlags(args);
        if (Files.exists(cwd.resolve(Manifest.FILE))) throw new JpmException(Manifest.FILE + " already exists here");
        String name = args.isEmpty() ? cwd.getFileName().toString() : args.get(0);
        name = name.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("^[^A-Za-z0-9]+", "");
        if (name.isEmpty()) name = "app";
        String pkg = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (pkg.isEmpty() || !Character.isLetter(pkg.charAt(0))) pkg = "app" + pkg;
        int major = 21;
        try {
            Jdk found = Toolchain.find(JavaSpec.any());
            major = found == null ? 21 : found.major();
        } catch (JpmException ignored) {
        }
        String manifest = """
                {
                  "$schema": "%s",
                  "name": "%s",
                  "version": "0.1.0",
                  %s"main": "%s.App",
                  "runtime": {},
                  "test": {}
                }
                """.formatted(Manifest.SCHEMA_URL, name, "\"java\": \"" + major + "\",\n  ", pkg);
        Fs.write(cwd.resolve(Manifest.FILE), manifest);
        Path app = cwd.resolve("src").resolve(pkg).resolve("App.java");
        if (!Files.exists(app)) {
            Fs.write(app, """
                    package %s;

                    public class App {
                        public static String greeting() {
                            return "Hello from %s!";
                        }

                        public static void main(String[] args) {
                            System.out.println(greeting());
                        }
                    }
                    """.formatted(pkg, name));
        }
        Path test = cwd.resolve("test").resolve(pkg).resolve("AppTest.java");
        if (!Files.exists(test)) {
            Fs.write(test, """
                    package %s;

                    import static org.junit.jupiter.api.Assertions.assertEquals;

                    import org.junit.jupiter.api.Test;

                    class AppTest {
                        @Test
                        void greets() {
                            assertEquals("Hello from %s!", App.greeting());
                        }
                    }
                    """.formatted(pkg, name));
        }
        Path gi = cwd.resolve(".gitignore");
        String ignore = ".cache/\ndist/\n.env.local\n";
        if (!Files.exists(gi)) Fs.write(gi, ignore);
        Log.step("Created", Manifest.FILE + ", src/" + pkg + "/App.java and test/" + pkg + "/AppTest.java");
        Log.info("Next: `jpm start` to run it, `jpm test` to test it.");
        return 0;
    }
}
