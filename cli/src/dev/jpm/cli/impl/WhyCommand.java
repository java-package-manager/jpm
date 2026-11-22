package dev.jpm.cli.impl;

import dev.jpm.resolve.ResolveInputs;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.resolve.Graph;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WhyCommand extends Command {

    WhyCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "why";
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.PROJECT, "why <dep>", "Show why a dependency is in the graph"));
    }

    @Override
    int run(List<String> args) {
        noMoreFlags(args);
        if (args.size() != 1) throw new JpmException("Usage: jpm why <dependency>");
        Ctx c = open(false);
        String id = DepArgs.parse(args.get(0)).key().toString();
        Graph.Pkg target = c.graph().get(id);
        if (target == null) {
            Log.info(id + " is not in the dependency graph.");
            return 1;
        }
        Map<String, Collection<Graph.Edge>> roots = new LinkedHashMap<>();
        for (Project.Module m : c.project().modules) {
            for (Manifest.Scope s : Manifest.Scope.values()) {
                List<Graph.Edge> e = ResolveInputs.rootEdges(m, s);
                if (!e.isEmpty()) roots.put(m.name() + " (" + s.key + ")", e);
            }
        }
        List<List<String>> paths = c.graph().paths(roots, id);
        Log.out(Log.bold(id + " " + target.version) + (target.commit != null ? " (" + target.commit.substring(0, 7) + ")" : ""));
        for (List<String> path : paths) {
            StringBuilder sb = new StringBuilder("  ").append(path.get(0));
            for (int i = 1; i < path.size(); i++) {
                Graph.Pkg p = c.graph().get(path.get(i));
                sb.append(" > ").append(path.get(i));
                if (p != null && p.kind != Graph.Kind.LOCAL) sb.append(' ').append(p.version);
            }
            Log.out(sb.toString());
        }
        return 0;
    }
}
