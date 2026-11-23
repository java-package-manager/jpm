package dev.jpm.cli.impl;

import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.util.JpmException;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class WorkspaceFilter {
    private WorkspaceFilter() {}

    static final String USAGE = "`--filter <pattern>` (repeatable) picks workspace modules: a name or glob (`app`, `lib-*`), a folder"
            + " (`./libs/*`), `<pattern>...` with what it depends on, `...<pattern>` with what depends on it, `!<pattern>` leaves"
            + " modules out";

    static List<String> options(List<String> args) {
        List<String> out = new ArrayList<>();
        for (String f; (f = Command.option(args, "--filter")) != null; ) out.add(f);
        for (String f; (f = Command.option(args, "-F")) != null; ) out.add(f);
        return out;
    }

    static List<Project.Module> select(Project p, Path cwd, List<String> patterns) {
        Set<String> chosen = new LinkedHashSet<>();
        Set<String> excluded = new LinkedHashSet<>();
        boolean onlyExclusions = true;
        for (String raw : patterns) {
            boolean exclude = raw.startsWith("!");
            String pat = exclude ? raw.substring(1) : raw;
            boolean deps = pat.endsWith("..."), dependents = pat.startsWith("...");
            if (deps) pat = pat.substring(0, pat.length() - 3);
            if (dependents) pat = pat.substring(3);
            if (pat.isEmpty()) throw new JpmException("Empty --filter pattern \"" + raw + "\"", 2);
            Set<String> hit = new LinkedHashSet<>(match(p, cwd, pat));
            if (hit.isEmpty()) throw new JpmException("--filter " + raw + " matches no workspace module; the modules are "
                    + String.join(", ", p.modules.stream().map(Project.Module::name).toList()), 2);
            if (deps) hit.addAll(closure(p, hit, true));
            if (dependents) hit.addAll(closure(p, hit, false));
            if (exclude) excluded.addAll(hit);
            else {
                chosen.addAll(hit);
                onlyExclusions = false;
            }
        }
        if (onlyExclusions) for (Project.Module m : p.modules) chosen.add(m.name());
        chosen.removeAll(excluded);
        List<Project.Module> out = new ArrayList<>();
        for (Project.Module m : p.buildOrder()) if (chosen.contains(m.name())) out.add(m);
        return out;
    }

    private static List<String> match(Project p, Path cwd, String pat) {
        List<String> out = new ArrayList<>();
        if (pat.startsWith("./") || pat.startsWith("../") || pat.equals(".")) {
            Path base = cwd.toAbsolutePath().normalize();
            String glob = base.resolve(pat).normalize().toString().replace('\\', '/');
            PathMatcher pm = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            for (Project.Module m : p.modules) {
                Path dir = m.dir.toAbsolutePath().normalize();
                if (pm.matches(Path.of(dir.toString().replace('\\', '/'))) || dir.toString().replace('\\', '/').equals(glob)) out.add(m.name());
            }
            return out;
        }
        Pattern re = Pattern.compile(glob(pat));
        for (Project.Module m : p.modules) if (re.matcher(m.name()).matches()) out.add(m.name());
        return out;
    }

    private static String glob(String g) {
        StringBuilder sb = new StringBuilder();
        for (char ch : g.toCharArray()) {
            if (ch == '*') sb.append(".*");
            else if (ch == '?') sb.append('.');
            else sb.append(Pattern.quote(String.valueOf(ch)));
        }
        return sb.toString();
    }

    private static Set<String> closure(Project p, Set<String> from, boolean dependencies) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(from);
        while (!queue.isEmpty()) {
            String name = queue.poll();
            for (Project.Module m : p.modules) {
                for (Manifest.Dependency d : m.manifest.dependencies) {
                    if (!d.key().isWorkspace()) continue;
                    String next = dependencies ? (m.name().equals(name) ? d.key().artifact() : null)
                            : (d.key().artifact().equals(name) ? m.name() : null);
                    if (next != null && out.add(next)) queue.add(next);
                }
            }
        }
        return out;
    }
}
