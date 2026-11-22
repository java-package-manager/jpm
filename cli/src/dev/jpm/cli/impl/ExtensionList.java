package dev.jpm.cli.impl;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.GitModule;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Install;
import dev.jpm.resolve.Lockfile;
import dev.jpm.util.Fs;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class ExtensionList {
    private ExtensionList() {}

    static List<Map<String, Object>> list(Project project) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> names = new TreeSet<>();
        if (project != null && !project.root.declaredExtensions.isEmpty()) {
            Graph g = Install.run(project, new MavenRepo(project.root.repositories), Install.Options.normal());
            for (Manifest.ExtensionDecl d : project.root.declaredExtensions) {
                Graph eg = g.extensions.get(d.name());
                out.add(describe(d.name(), d.key(), eg == null ? null : eg.get(d.key().toString()), "project"));
                names.add(d.name());
            }
        }
        Path global = Fs.home().resolve("global");
        if (Files.isDirectory(global)) {
            List<Path> dirs;
            try (var s = Files.list(global)) {
                dirs = s.filter(x -> Files.isRegularFile(x.resolve(Manifest.FILE))).sorted().toList();
            } catch (IOException e) {
                throw new JpmException("Could not read " + global + ": " + e.getMessage());
            }
            for (Path dir : dirs) {
                String name = dir.getFileName().toString();
                if (names.contains(name)) continue;
                try {
                    Manifest m = Manifest.load(dir);
                    if (m.dependencies.size() != 1) continue;
                    DepKey key = m.dependencies.get(0).key();
                    Lockfile lock = Lockfile.read(dir.resolve(Manifest.LOCK));
                    out.add(describe(name, key, lock == null ? null : lock.graph.get(key.toString()), "global"));
                } catch (JpmException e) {
                    Log.debug("skipping the global extension " + name + ": " + e.getMessage());
                }
            }
        }
        return out;
    }

    private static Map<String, Object> describe(String name, DepKey key, Graph.Pkg pkg, String source) {
        Manifest.ExtensionDescriptor d = null;
        if (pkg != null && pkg.kind == Graph.Kind.GIT && pkg.commit != null) d = GitModule.locate(key, pkg.commit).manifest().extension;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("key", key.toString());
        if (pkg != null) out.put("version", pkg.version);
        out.put("source", source);
        if (d != null && d.description() != null) out.put("description", d.description());
        List<Object> commands = new ArrayList<>();
        if (d == null || d.commands().isEmpty()) {
            commands.add(Map.of("name", name, "options", List.of()));
        } else {
            for (Manifest.ExtensionCommand c : d.commands()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("name", c.name());
                if (c.description() != null) cm.put("description", c.description());
                List<Object> options = new ArrayList<>();
                for (Manifest.ExtensionOption o : c.options()) {
                    Map<String, Object> om = new LinkedHashMap<>();
                    om.put("name", o.name());
                    if (o.argument() != null) om.put("argument", o.argument());
                    if (o.description() != null) om.put("description", o.description());
                    options.add(om);
                }
                cm.put("options", options);
                commands.add(cm);
            }
        }
        out.put("commands", commands);
        List<Object> checks = new ArrayList<>();
        if (d != null) {
            for (Manifest.ExtensionCheck c : d.checks()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("id", c.id());
                if (c.description() != null) cm.put("description", c.description());
                checks.add(cm);
            }
        }
        out.put("checks", checks);
        return out;
    }
}
