package dev.jpm.cli.impl;

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
                new Usage(Group.PROJECT, "init [name]", "Create a project in the current directory (--license <id> also writes LICENSE)",
                        "Create a project; `--license <SPDX id>` sets its license and writes `LICENSE` (as `jpm license` does)"));
    }

    @Override
    List<String> flags() {
        return List.of("--license", "--author");
    }

    @Override
    int run(List<String> args) {
        String license = option(args, "--license");
        String author = option(args, "--author");
        noMoreFlags(args);
        if (license != null) LicenseCommand.validate(license);
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
                  %s%s"main": "%s.App",
                  "runtime": {},
                  "test": {}
                }
                """.formatted(Manifest.SCHEMA_URL, name, license == null ? "" : "\"license\": " + dev.jpm.api.json.Jsonc.quote(license) + ",\n  ",
                "\"java\": \"" + major + "\",\n  ", pkg);
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
        if (license != null) LicenseCommand.set(cwd, cwd.resolve(Manifest.FILE), license, author, false);
        ignoreJpmFolders(cwd);
        Log.step("Created", Manifest.FILE + ", src/" + pkg + "/App.java and test/" + pkg + "/AppTest.java");
        Log.info("Next: `jpm start` to run it, `jpm test` to test it.");
        return 0;
    }

    static final List<String> IGNORED = List.of(".cache/", "dist/", ".env.local");

    static List<String> ignoreJpmFolders(Path dir) {
        Path gi = dir.resolve(".gitignore");
        String text = Files.exists(gi) ? Fs.read(gi) : "";
        java.util.Set<String> have = new java.util.HashSet<>();
        for (String l : text.split("\\R")) have.add(l.strip());
        List<String> missing = new java.util.ArrayList<>();
        for (String e : IGNORED) {
            String bare = e.endsWith("/") ? e.substring(0, e.length() - 1) : e;
            if (!have.contains(e) && !have.contains(bare) && !have.contains("/" + e) && !have.contains("/" + bare)) missing.add(e);
        }
        if (missing.isEmpty()) return missing;
        StringBuilder sb = new StringBuilder(text);
        if (!text.isEmpty() && !text.endsWith("\n")) sb.append('\n');
        if (!text.isEmpty()) sb.append("\n# jpm\n");
        for (String e : missing) sb.append(e).append('\n');
        Fs.write(gi, sb.toString());
        return missing;
    }
}
