package dev.jpm.cli;

import dev.jpm.Project;
import dev.jpm.api.json.Jsonc;
import dev.jpm.util.Exec;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ExtensionCommand extends Command {
    private final String cmd;

    ExtensionCommand(Path cwd, String cmd) {
        super(cwd);
        this.cmd = cmd;
    }

    @Override
    String name() {
        return cmd;
    }

    @Override
    List<Usage> usages() {
        return List.of();
    }

    @Override
    int run(List<String> args) {
        if (!cmd.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) throw new JpmException("Unknown command \"" + cmd + "\". Run `jpm help`.");
        Path exe = dev.jpm.resolve.ExtensionDeps.executable(cmd);
        if (exe == null) {
            throw new JpmException("Unknown command \"" + cmd + "\"").withHint("run `jpm help`, or install an extension that"
                    + " provides it with `jpm add -g <dependency>` (it runs as jpm-" + cmd + ")");
        }
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JPM_EXE", dev.jpm.util.Self.exe());
        env.put("JPM_VERSION_OF_JPM", Main.VERSION);
        env.putAll(Log.childEnv());
        try {
            Project p = Project.find(cwd);
            Project.Module m = p.moduleAt(cwd) != null ? p.moduleAt(cwd) : p.rootModule();
            env.put("JPM_PROJECT_DIR", p.dir.toString());
            env.put("JPM_MODULE_DIR", m.dir.toString());
            env.put("JPM_MANIFEST", m.manifest.file.toString());
            env.put("JPM_CONFIG", Jsonc.write(m.manifest.root.value).replaceAll("\n\\s*", " "));
            env.put("JPM_NAME", m.name());
            env.put("JPM_VERSION", m.version());
        } catch (JpmException e) {
            env.put("JPM_PROJECT_DIR", cwd.toString());
        }
        List<String> full = new ArrayList<>();
        full.add(exe.toString());
        full.addAll(args);
        return Exec.inherit(cwd, full, env);
    }
}
