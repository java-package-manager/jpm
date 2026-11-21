package dev.jpm.cli;

import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jpm schema}: the JSON Schema of project.jsonc that this jpm reads, built into the binary, so editors
 * describe exactly the manifests the installed jpm understands, offline. {@code --path} writes it to
 * {@code ~/.jpm/schemas/<version>/project.schema.json} (once per version) and prints that path instead, for
 * editors that want a file (the IntelliJ plugin).
 */
final class Schema {
    static final String RESOURCE = "/dev/jpm/project.schema.json";

    private Schema() {}

    static int run(List<String> args) {
        boolean path = false;
        for (String a : args) {
            if (a.equals("--path")) path = true;
            else throw new JpmException("Usage: jpm schema [--path]", 2);
        }
        if (path) System.out.println(install(Fs.home(), Main.VERSION));
        else System.out.print(text());
        return 0;
    }

    /** The schema's text, from the binary. */
    static String text() {
        try (InputStream in = Schema.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new JpmException("this jpm build has no " + RESOURCE.substring(1));
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JpmException("Could not read the schema: " + e.getMessage());
        }
    }

    /** Writes the schema under {@code home} for {@code version} if it is not there yet (or differs), and returns its path. */
    static Path install(Path home, String version) {
        Path file = home.resolve("schemas").resolve(version).resolve("project.schema.json");
        Fs.writeIfChanged(file, text());
        return file;
    }
}
