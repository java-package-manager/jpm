package dev.jpm.cli.impl;

import dev.jpm.Project;
import dev.jpm.api.json.Jsonc;
import dev.jpm.json.JsoncEdit;
import dev.jpm.license.Licenses;
import dev.jpm.log.Log;
import dev.jpm.util.Exec;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.nio.file.Path;
import java.time.Year;
import java.util.List;

final class LicenseCommand extends Command {
    LicenseCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "license";
    }

    @Override
    List<Usage> usages() {
        return List.of(new Usage(Group.PROJECT, "license [id]", "Show the license, or set it (an SPDX id) and write LICENSE",
                "Show the project's license and license file; `license <SPDX id>` sets `\"license\"` and writes `LICENSE` from SPDX's"
                        + " text with the year and the author (`--author`, else git's `user.name`) filled in; `--force` replaces a license file"));
    }

    @Override
    List<String> flags() {
        return List.of("--author", "--force");
    }

    @Override
    int run(List<String> args) {
        String author = option(args, "--author");
        boolean force = flag(args, "--force");
        noMoreFlags(args);
        if (args.size() > 1) throw new JpmException("Usage: jpm license [<SPDX id or expression>] [--author <name>] [--force]", 2);
        Project p = Project.find(cwd);
        if (args.isEmpty()) {
            show(p);
            return 0;
        }
        set(p.dir, p.root.file, args.get(0), author, force);
        return 0;
    }

    private static void show(Project p) {
        String license = p.root.license != null ? p.root.license : p.modules.size() == 1 ? p.modules.get(0).manifest.license : null;
        Path file = Licenses.file(p.dir);
        Log.out("license: " + (license == null ? "(none)" : license));
        if (file == null) {
            Log.out("file:    (none)");
        } else {
            List<String> detected = Licenses.detect(Fs.read(file));
            Log.out("file:    " + p.dir.relativize(file) + (detected.isEmpty() ? "" : " (" + detected.get(0) + ")"));
        }
    }

    static void validate(String expression) {
        List<String> unknown = Licenses.unknown(expression);
        if (!unknown.isEmpty()) {
            String id = unknown.get(0);
            String suggestion = Licenses.suggest(id);
            throw new JpmException("\"" + id + "\" is not an SPDX license id", 2).withHint(suggestion != null
                    ? "did you mean " + suggestion + "?" : "see https://spdx.org/licenses/ (SPDX license list " + Licenses.listVersion() + ")");
        }
        if (Licenses.ids(expression).isEmpty()) throw new JpmException("\"" + expression + "\" names no license", 2);
    }

    static void set(Path dir, Path manifest, String expression, String author, boolean force) {
        validate(expression);
        List<String> ids = Licenses.ids(expression);
        String value = ids.size() == 1 && ids.get(0).equals(expression.strip()) ? Licenses.find(ids.get(0)).id() : expression.strip();
        String src = Fs.read(manifest);
        String next = JsoncEdit.set(src, List.of(), "license", Jsonc.quote(value));
        if (!next.equals(src)) {
            Fs.write(manifest, next);
            Log.step("License", value + Log.dim(" (" + dir.relativize(manifest) + ")"));
        }
        if (ids.size() != 1) {
            Log.info("A license expression has no single text: add the license files yourself.");
            return;
        }
        Path existing = Licenses.file(dir);
        if (existing != null && !force) {
            List<String> detected = Licenses.detect(Fs.read(existing));
            if (!detected.isEmpty() && !Licenses.names(value, detected)) {
                Log.warn(dir.relativize(existing) + " is " + detected.get(0) + ", not " + value + "; `jpm license " + value
                        + " --force` replaces it");
            }
            return;
        }
        String holder = author != null ? author : gitUser(dir);
        Path target = existing != null ? existing : dir.resolve("LICENSE");
        Fs.write(target, Licenses.text(value, Year.now().getValue(), holder));
        Log.step("Wrote", dir.relativize(target) + Log.dim(" (" + value + (holder == null ? "" : ", " + holder) + ")"));
        if (holder == null) Log.warn("no author to name in " + dir.relativize(target) + "; pass --author, or set git's user.name");
    }

    private static String gitUser(Path dir) {
        try {
            Exec.Result r = Exec.capture(dir, List.of("git", "config", "user.name"));
            String name = r.out().strip();
            return r.ok() && !name.isEmpty() ? name : null;
        } catch (JpmException e) {
            return null;
        }
    }
}
