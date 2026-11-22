package dev.jpm.cli.impl;

import dev.jpm.cli.Main;

import dev.jpm.log.Log;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

final class SchemaCommand extends Command {
    static final String RESOURCE = "/dev/jpm/project.schema.json";


    SchemaCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "schema";
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.PROJECT, "schema [--path]", "Print the JSON Schema of project.jsonc (--path writes it to ~/.jpm and prints where, for editors)",
                        "Print the JSON Schema of `project.jsonc` built into this jpm; `--path` writes it to `~/.jpm/schemas/<version>/project.schema.json` and prints the path instead (used by the IntelliJ plugin), see [Editor support](#editor-support)"));
    }

    @Override
    List<String> flags() {
        return List.of("--path");
    }

    @Override
    int run(List<String> args) {
        boolean path = false;
        for (String a : args) {
            if (a.equals("--path")) path = true;
            else throw new JpmException("Usage: jpm schema [--path]", 2);
        }
        if (path) Log.out(install(Fs.home(), Main.VERSION).toString());
        else Log.print(text());
        return 0;
    }

    static String text() {
        try (InputStream in = SchemaCommand.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new JpmException("this jpm build has no " + RESOURCE.substring(1));
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JpmException("Could not read the schema: " + e.getMessage());
        }
    }

    static Path install(Path home, String version) {
        Path file = home.resolve("schemas").resolve(version).resolve("project.schema.json");
        Fs.writeIfChanged(file, text());
        return file;
    }
}
