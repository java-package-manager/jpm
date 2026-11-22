package dev.jpm.cli.impl;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.json.JsoncEdit;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Install;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

final class RemoveCommand extends Command {

    RemoveCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "remove";
    }

    @Override
    List<String> aliases() {
        return List.of("rm");
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.PROJECT, "remove <dep>", "Remove dependencies (-g removes an extension)",
                        "Remove dependencies (`-g` removes an extension)"));
    }

    @Override
    List<String> flags() {
        return List.of("-g", "--global");
    }

    @Override
    int run(List<String> args) {
        boolean global = flag(args, "-g", "--global");
        noMoreFlags(args);
        if (args.isEmpty()) throw new JpmException("Usage: jpm remove <dependency>");
        if (global) return GlobalExtensions.remove(args);
        Project p = Project.find(cwd);
        Project.Module m = single(p, "remove");
        String src = Fs.read(m.manifest.file);
        for (String a : args) {
            DepKey key = DepArgs.parse(a).key();
            boolean found = false;
            for (Manifest.Scope s : Manifest.Scope.values()) {
                String next = JsoncEdit.remove(src, List.of(s.key), key.toString());
                if (!next.equals(src)) { found = true; src = next; }
            }
            if (!found) throw new JpmException(key + " is not a dependency in " + m.manifest.file);
            Log.step("Removing", key.toString());
        }
        Fs.write(m.manifest.file, src);
        Project fresh = Project.find(cwd);
        Install.run(fresh, new MavenRepo(fresh.root.repositories), new Install.Options(false, false, Set.of(), false));
        return 0;
    }
}
