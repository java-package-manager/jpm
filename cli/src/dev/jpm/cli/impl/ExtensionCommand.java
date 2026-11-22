package dev.jpm.cli.impl;

import dev.jpm.cli.Main;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Extensions;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Install;
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
        Project project = null;
        try {
            project = Project.find(cwd);
        } catch (JpmException e) {
            Log.debug("no project for jpm " + cmd + ": " + e.getMessage());
        }
        Manifest.ExtensionDecl declared = project == null ? null
                : project.root.declaredExtensions.stream().filter(d -> d.name().equals(cmd)).findFirst().orElse(null);
        if (declared != null) return launch(cwd, project, declared, args, Map.of());
        List<String> full = new ArrayList<>();
        Path exe = dev.jpm.resolve.ExtensionDeps.executable(cmd);
        if (exe == null && cmd.equals("schema")) {
            throw new JpmException("jpm schema was removed in jpm 0.12", 2).withHint("editors use the schema at "
                    + Manifest.versionedSchemaUrl(Main.VERSION) + " (or ask jpm over its protocol, schema/manifest)");
        }
        if (exe == null) {
            throw new JpmException("Unknown command \"" + cmd + "\"").withHint("run `jpm help`, or add an extension that provides it"
                    + " with `jpm add <dependency>` (for this project) or `jpm add -g <dependency>` (it runs as jpm-" + cmd + ")");
        }
        full.add(exe.toString());
        full.addAll(args);
        return Exec.inherit(cwd, full, env(project));
    }

    static int launch(Path cwd, Project project, Manifest.ExtensionDecl declared, List<String> args, Map<String, String> extraEnv) {
        MavenRepo repo = new MavenRepo(project.root.repositories);
        Graph g = Install.run(project, repo, Install.Options.normal());
        GlobalExtensions.Launch l = GlobalExtensions.launch(Extensions.project(project, declared), g.extensions.get(declared.name()), declared.key(), repo);
        List<String> full = new ArrayList<>();
        full.add(l.java());
        full.add("-cp");
        full.add(dev.jpm.build.Compiler.join(l.classpath()));
        full.add(l.main());
        full.addAll(args);
        Map<String, String> env = new LinkedHashMap<>(env(cwd, project));
        env.putAll(extraEnv);
        return Exec.inherit(cwd, full, env);
    }

    private Map<String, String> env(Project p) {
        return env(cwd, p);
    }

    private static Map<String, String> env(Path cwd, Project p) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JPM_EXE", dev.jpm.util.Self.exe());
        env.put("JPM_VERSION_OF_JPM", Main.VERSION);
        env.putAll(Log.childEnv());
        if (p == null) {
            env.put("JPM_PROJECT_DIR", cwd.toString());
            return env;
        }
        Project.Module m = p.moduleAt(cwd) != null ? p.moduleAt(cwd) : p.rootModule();
        env.put("JPM_PROJECT_DIR", p.dir.toString());
        env.put("JPM_MODULE_DIR", m.dir.toString());
        env.put("JPM_MANIFEST", m.manifest.file.toString());
        env.put("JPM_CONFIG", Jsonc.write(m.manifest.root.value).replaceAll("\n\\s*", " "));
        env.put("JPM_NAME", m.name());
        env.put("JPM_VERSION", m.version());
        return env;
    }
}
