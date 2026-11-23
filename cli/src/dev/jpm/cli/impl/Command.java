package dev.jpm.cli.impl;

import dev.jpm.Project;
import dev.jpm.build.Builder;
import dev.jpm.build.Env;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Install;
import dev.jpm.util.JpmException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

abstract class Command {
    enum Group {
        PROJECT("Project"), BUILD("Build and run"), DEPENDENCIES("Dependencies"), TOOLCHAIN("Toolchain"), MAINTENANCE("Maintenance");

        final String title;

        Group(String title) {
            this.title = title;
        }
    }

    record Usage(Group group, String synopsis, String summary, String docs) {
        Usage(Group group, String synopsis, String summary) {
            this(group, synopsis, summary, summary);
        }
    }

    protected final Path cwd;

    Command(Path cwd) {
        this.cwd = cwd;
    }

    abstract String name();

    List<String> aliases() {
        return List.of();
    }

    abstract List<Usage> usages();

    List<String> flags() {
        return List.of();
    }

    abstract int run(List<String> args);


    static boolean flag(List<String> args, String... names) {
        boolean found = false;
        for (String n : names) found |= args.remove(n);
        return found;
    }

    static String option(List<String> args, String name) {
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals(name) && i + 1 < args.size()) {
                args.remove(i);
                return args.remove(i);
            }
            if (a.startsWith(name + "=")) {
                args.remove(i);
                return a.substring(name.length() + 1);
            }
        }
        return null;
    }

    static List<String> passthrough(List<String> args) {
        int i = args.indexOf("--");
        if (i < 0) return new ArrayList<>();
        List<String> rest = new ArrayList<>(args.subList(i + 1, args.size()));
        args.subList(i, args.size()).clear();
        return rest;
    }

    static void noMoreFlags(List<String> args) {
        for (String a : args) if (a.startsWith("-")) throw new JpmException("Unknown option " + a);
    }

    static List<String> options(List<String> args, String... names) {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            String v;
            while ((v = option(args, n)) != null) out.add(v);
        }
        return out;
    }

    record Ctx(Project project, MavenRepo repo, Graph graph) {
        Builder builder(Env.Mode mode) {
            return new Builder(project, graph, repo, mode);
        }
    }

    Ctx open(boolean frozen) {
        Project p = Project.find(cwd);
        MavenRepo repo = new MavenRepo(p.root.repositories);
        Graph g = Install.run(p, repo, new Install.Options(frozen, false, Set.of(), true));
        return new Ctx(p, repo, g);
    }

    List<Project.Module> targets(Project p, List<String> names, List<String> filters) {
        if (filters.isEmpty()) return targets(p, names);
        Set<String> seen = new LinkedHashSet<>();
        if (!names.isEmpty()) for (Project.Module m : targets(p, names)) seen.add(m.name());
        for (Project.Module m : WorkspaceFilter.select(p, cwd, filters)) seen.add(m.name());
        return p.buildOrder().stream().filter(m -> seen.contains(m.name())).toList();
    }

    List<Project.Module> targets(Project p, List<String> names) {
        if (!names.isEmpty()) {
            List<Project.Module> out = new ArrayList<>();
            for (String n : names) {
                Project.Module m = p.module(n);
                if (m == null) throw new JpmException("No module named \"" + n + "\"");
                out.add(m);
            }
            return out;
        }
        Project.Module here = p.moduleAt(cwd);
        if (here != null) return List.of(here);
        return p.buildOrder();
    }

    Project.Module single(Project p, String what) {
        Project.Module here = p.moduleAt(cwd);
        if (here != null) return here;
        List<Project.Module> withMain = p.modules.stream().filter(m -> !m.manifest.mains.isEmpty()).toList();
        if (withMain.size() == 1 && what.equals("run")) return withMain.get(0);
        throw new JpmException("This is a workspace; run `jpm " + what + "` inside a module (" + String.join(", ",
                p.modules.stream().map(Project.Module::relDir).toList()) + ")");
    }
}
