package dev.jpm.cli.impl;

import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.build.Env;
import dev.jpm.build.Scripts;
import dev.jpm.log.Log;
import dev.jpm.util.JpmException;

import java.nio.file.Path;
import java.util.ArrayList;
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
                        "Run a script from `scripts` (no shorthand); `<module>:<script>` runs a workspace module's script from anywhere in the workspace"),
                new Usage(Group.BUILD, "run -r <script> [args]", "Run a script in every workspace module that has it (--filter <pattern>)",
                        "Run a script in every workspace module that has it, in build order, stopping at the first that fails; "
                                + WorkspaceFilter.USAGE + " (and implies `-r`)"));
    }

    @Override
    List<String> flags() {
        return List.of("-r", "--recursive", "--filter");
    }

    @Override
    int run(List<String> args) {
        int dashes = args.indexOf("--");
        List<String> own = new ArrayList<>(dashes < 0 ? args : args.subList(0, dashes));
        List<String> rest = dashes < 0 ? List.of() : new ArrayList<>(args.subList(dashes, args.size()));
        int scriptAt = 0;
        while (scriptAt < own.size() && own.get(scriptAt).startsWith("-")) scriptAt += own.get(scriptAt).equals("--filter") || own.get(scriptAt).equals("-F") ? 2 : 1;
        List<String> before = new ArrayList<>(own.subList(0, Math.min(scriptAt, own.size())));
        boolean recursive = flag(before, "-r", "--recursive");
        List<String> filters = WorkspaceFilter.options(before);
        noMoreFlags(before);
        if (!before.isEmpty()) throw new JpmException("Usage: jpm run [-r] [--filter <pattern>] <script> [args]", 2);
        args = new ArrayList<>(own.subList(Math.min(scriptAt, own.size()), own.size()));
        args.addAll(rest);
        if (recursive || !filters.isEmpty()) return recursive(args, filters);
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

    private int recursive(List<String> args, List<String> filters) {
        if (args.isEmpty()) throw new JpmException("Usage: jpm run -r [--filter <pattern>] <script> [args]", 2);
        String name = args.remove(0);
        if (!args.isEmpty() && args.get(0).equals("--")) args.remove(0);
        Ctx c = open(false);
        Project p = c.project();
        List<Project.Module> mods = filters.isEmpty() ? p.buildOrder() : WorkspaceFilter.select(p, cwd, filters);
        List<Project.Module> with = mods.stream().filter(m -> m.manifest.scripts.containsKey(name)).toList();
        if (with.isEmpty()) {
            throw new JpmException("No " + (filters.isEmpty() ? "workspace module" : "module the filter picks") + " has a script \"" + name + "\"");
        }
        Builder b = c.builder(Env.Mode.DEVELOPMENT);
        for (Project.Module m : with) {
            Log.step("Running", name + " in " + m.name());
            int code = Scripts.run(b.forModule(m), m, name, new ArrayList<>(args), Env.Mode.DEVELOPMENT);
            if (code != 0) {
                Log.error(name + " failed in " + m.name() + " (exit " + code + ")"
                        + (with.indexOf(m) + 1 < with.size() ? "; not run in " + String.join(", ", with.subList(with.indexOf(m) + 1, with.size())
                                .stream().map(Project.Module::name).toList()) : ""));
                return code;
            }
        }
        return 0;
    }
}
