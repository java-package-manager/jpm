package dev.jpm.cli.impl;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.api.json.Jsonc;
import dev.jpm.json.JsoncEdit;
import dev.jpm.log.Log;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Install;
import dev.jpm.resolve.ReleaseAge;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class UpdateCommand extends Command {

    UpdateCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "update";
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.PROJECT, "update [dep...]", "Re-resolve within ranges (--latest goes past them; -g updates the global extensions, -g --check only reports)",
                        "Re-resolve within ranges; `--latest` rewrites the manifest past them. `update -g [name…]` updates global extensions, `update -g --check` only reports what it would update"));
    }

    @Override
    List<String> flags() {
        return List.of("--latest", "-g", "--global", "--check");
    }

    @Override
    int run(List<String> args) {
        boolean latest = flag(args, "--latest");
        boolean global = flag(args, "-g", "--global");
        boolean check = flag(args, "--check");
        noMoreFlags(args);
        if (check && !global) throw new JpmException("`--check` works with global extensions only: `jpm update -g --check`. For a project, run `jpm outdated`.");
        if (check && latest) throw new JpmException("`--check` and `--latest` cannot be used together: `--check` reports newer releases outside the ranges too.");
        if (global) return check ? GlobalExtensions.check(args) : GlobalExtensions.update(args, latest);
        Project p = Project.find(cwd);
        MavenRepo repo = new MavenRepo(p.root.repositories);
        repo.setRefreshMetadata(true);
        Set<String> only = new LinkedHashSet<>();
        for (String a : args) only.add(DepArgs.parse(a).key().toString());
        if (latest) {
            for (Project.Module m : p.modules) {
                String src = Fs.read(m.manifest.file);
                for (Manifest.Dependency d : m.manifest.dependencies) {
                    if (d.key().isWorkspace() || (!only.isEmpty() && !only.contains(d.key().toString()))) continue;
                    if (d.key().isGit() && (d.spec().startsWith("#") || d.spec().matches("[0-9a-f]{7,40}"))) continue;
                    String spec = DepArgs.latestSpec(repo, d.key(), ReleaseAge.of(p.root, repo));
                    if (!spec.equals(d.spec())) {
                        Log.step("Updating", d.key() + " " + d.spec() + " -> " + spec);
                        src = JsoncEdit.set(src, List.of(d.scope().key), d.key().toString(), Jsonc.quote(spec));
                    }
                }
                Fs.write(m.manifest.file, src);
            }
            p = Project.find(cwd);
        }
        Install.run(p, repo, new Install.Options(false, only.isEmpty(), only, false));
        return 0;
    }
}
