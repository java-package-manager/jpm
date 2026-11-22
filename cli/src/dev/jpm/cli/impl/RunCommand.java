package dev.jpm.cli.impl;

import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.build.Env;
import dev.jpm.build.Scripts;
import dev.jpm.log.Log;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class RunCommand extends Command {

    RunCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "run";
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.BUILD, "run <script> [args]", "Run a script from the manifest; <module>:<script> for another module's",
                        "Run a script from `scripts` (no shorthand); `<module>:<script>` runs a workspace module's script from anywhere in the workspace"));
    }

    @Override
    int run(List<String> args) {
        if (args.isEmpty()) {
            Project p = Project.find(cwd);
            Project.Module m = p.moduleAt(cwd) != null ? p.moduleAt(cwd) : p.rootModule();
            if (m.manifest.scripts.isEmpty()) {
                Log.info("No scripts in " + m.manifest.file);
            } else {
                for (Map.Entry<String, String> e : m.manifest.scripts.entrySet()) {
                    Log.out(Log.bold(e.getKey()) + "  " + Log.dim(e.getValue()));
                }
            }
            return 0;
        }
        String name = args.remove(0);
        if (!args.isEmpty() && args.get(0).equals("--")) args.remove(0);
        Ctx c = open(false);
        Project.Module m = c.project().moduleAt(cwd);
        int colon = name.indexOf(':');
        if (colon > 0 && !name.endsWith(":") && c.project().module(name.substring(0, colon)) != null) {
            m = c.project().module(name.substring(0, colon));
            name = name.substring(colon + 1);
        } else if (m == null || (!m.manifest.scripts.containsKey(name) && c.project().root.scripts.containsKey(name))) {
            m = c.project().rootModule();
        }
        Builder b = c.builder(Env.Mode.DEVELOPMENT).forModule(m);
        return Scripts.run(b, m, name, args, Env.Mode.DEVELOPMENT);
    }
}
