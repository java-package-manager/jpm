package dev.jpm.cli.impl;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.log.Log;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Install;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

final class ImportCommand extends Command {

    ImportCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "import";
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.PROJECT, "import [dir]", "Create project.jsonc from a Maven or Gradle build (--dry-run, --static, --force)",
                        "Create `project.jsonc` from a Maven or Gradle build (`--dry-run`, `--static`, `--force`)"));
    }

    @Override
    List<String> flags() {
        return List.of("--dry-run", "-n", "--force", "-f", "--no-install", "--static");
    }

    @Override
    int run(List<String> args) {
        boolean dryRun = flag(args, "--dry-run", "-n");
        boolean force = flag(args, "--force", "-f");
        boolean noInstall = flag(args, "--no-install");
        boolean staticRead = flag(args, "--static");
        noMoreFlags(args);
        Path dir = args.isEmpty() ? cwd : cwd.resolve(args.get(0)).normalize();
        dev.jpm.importer.Imported imp = dev.jpm.importer.Importer.run(dir, new MavenRepo(List.of()), staticRead);
        if (!dryRun && !force) {
            for (String rel : imp.manifests.keySet()) {
                Path f = dir.resolve(rel).resolve(Manifest.FILE);
                if (Files.exists(f)) throw new JpmException(dir.relativize(f) + " already exists; use --force to overwrite it");
            }
        }
        for (String rel : imp.manifests.keySet()) {
            String text = imp.render(rel);
            Path f = dir.resolve(rel).resolve(Manifest.FILE);
            if (dryRun) {
                Log.out(Log.bold("// " + (rel.isEmpty() ? "" : rel + "/") + Manifest.FILE));
                Log.out(text);
            } else {
                Fs.write(f, text);
                Log.step("Imported", (rel.isEmpty() ? "" : rel + "/") + Manifest.FILE + " from " + imp.tool);
            }
        }
        for (String w : imp.warnings) Log.warn(w);
        if (!dryRun) {
            List<String> added = InitCommand.ignoreJpmFolders(dir);
            if (!added.isEmpty()) Log.step("Ignored", String.join(", ", added) + " in .gitignore");
        }
        if (dryRun || noInstall) return 0;
        Project p = Project.load(dir);
        Install.run(p, new MavenRepo(p.root.repositories), new Install.Options(false, false, Set.of(), false));
        Log.info("Next: `jpm build` and `jpm test`. Sources stay where they are (\"layout\": \"maven\").");
        return 0;
    }
}
