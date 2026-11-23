package dev.jpm.resolve;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.api.json.Jsonc;
import dev.jpm.util.Fs;

import java.nio.file.Path;

/**
 * The extensions a project declares in its root {@code "extensions"}. Each is resolved as a project of its own, with the
 * extension as its one dependency, so that its packages never mix with the project's; {@code project.lock} pins them in
 * its {@code "extensions"} section.
 */
public final class Extensions {
    private Extensions() {
    }

    /**
     * The folder of a declared extension's own project, in the project's cache.
     */
    public static Path dir(Project project, Manifest.ExtensionDecl d) {
        return project.dir.resolve(".cache").resolve("extensions").resolve(d.name());
    }

    /**
     * The one-dependency project an extension is resolved and built as; it uses the project's repositories.
     */
    public static Project project(Project project, Manifest.ExtensionDecl d) {
        return Project.load(write(dir(project, d), d.key().toString(), d.spec(), project.root.repositories));
    }

    /**
     * Writes the manifest of an extension's own project into {@code dir} (only when it changed) and returns {@code dir}.
     */
    public static Path write(Path dir, String key, String spec, java.util.List<String> repositories) {
        StringBuilder sb = new StringBuilder("{\n  \"name\": ").append(Jsonc.quote("jpm-extension-" + dir.getFileName())).append(",\n");
        sb.append("  \"version\": \"0.0.0\",\n");
        if (!repositories.isEmpty()) {
            sb.append("  \"repositories\": [");
            for (int i = 0; i < repositories.size(); i++) sb.append(i == 0 ? "" : ", ").append(Jsonc.quote(repositories.get(i)));
            sb.append("],\n");
        }
        sb.append("  \"runtime\": { ").append(Jsonc.quote(key)).append(": ").append(Jsonc.quote(spec)).append(" }\n}\n");
        Fs.mkdirs(dir);
        Fs.writeIfChanged(dir.resolve(Manifest.FILE), sb.toString());
        return dir;
    }
}
