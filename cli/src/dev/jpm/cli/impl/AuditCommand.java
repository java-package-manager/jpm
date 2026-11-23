package dev.jpm.cli.impl;

import dev.jpm.api.json.Jsonc;
import dev.jpm.audit.Audit;
import dev.jpm.log.Log;
import dev.jpm.resolve.Graph;
import dev.jpm.util.JpmException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AuditCommand extends Command {
    AuditCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "audit";
    }

    @Override
    List<Usage> usages() {
        return List.of(new Usage(Group.DEPENDENCIES, "audit", "Report locked dependencies with known vulnerabilities (OSV)",
                "Report the locked dependencies with known vulnerabilities, from the OSV database: id, severity, the version"
                        + " that fixes it and how the project gets it; exits 1 when there are any. `--ignore <id>` (repeatable)"
                        + " accepts one, `--json` prints them as JSON, `--frozen`"));
    }

    @Override
    List<String> flags() {
        return List.of("--ignore", "--json", "--frozen");
    }

    @Override
    int run(List<String> args) {
        boolean json = flag(args, "--json");
        boolean frozen = flag(args, "--frozen");
        Set<String> ignore = new LinkedHashSet<>();
        for (String id; (id = option(args, "--ignore")) != null; ) ignore.add(id);
        noMoreFlags(args);
        if (!args.isEmpty()) throw new JpmException("Usage: jpm audit [--ignore <id>]... [--json] [--frozen]", 2);
        Ctx c = open(frozen);
        Graph g = c.graph();
        List<Audit.Finding> findings = Audit.run(c.project(), g, Audit.osvUrl(), ignore);
        if (json) {
            List<Object> out = new ArrayList<>();
            for (Audit.Finding f : findings) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("package", f.pkg());
                m.put("version", f.version());
                m.put("id", f.id());
                m.put("aliases", f.aliases());
                if (f.severity() != null) m.put("severity", f.severity().toLowerCase(java.util.Locale.ROOT));
                m.put("summary", f.summary());
                if (f.fixed() != null) m.put("fixed", f.fixed());
                m.put("path", f.path());
                out.add(m);
            }
            Log.out(Jsonc.write(out));
        } else {
            for (Audit.Finding f : findings) {
                String alias = f.aliases().stream().filter(a -> a.startsWith("CVE-")).findFirst().orElse(null);
                Log.out((f.severity() == null ? "UNKNOWN" : f.severity()) + "  " + f.pkg() + " " + f.version() + "  " + f.id()
                        + (alias == null ? "" : " (" + alias + ")"));
                Log.out("  " + f.summary());
                Log.out("  " + (f.fixed() == null ? "no fixed version known" : "fixed in " + f.fixed()) + "; via " + String.join(" > ", f.path()));
            }
            long packages = findings.stream().map(Audit.Finding::pkg).distinct().count();
            if (findings.isEmpty()) {
                long n = g.pkgs.values().stream().filter(p -> p.kind != Graph.Kind.LOCAL).count();
                Log.step("Audited", n + (n == 1 ? " package" : " packages") + ": no known vulnerabilities"
                        + (ignore.isEmpty() ? "" : " (" + ignore.size() + " ignored)"));
            } else {
                Log.error(findings.size() + (findings.size() == 1 ? " vulnerability" : " vulnerabilities") + " in " + packages
                        + (packages == 1 ? " package" : " packages") + "; update them, or `--ignore <id>` what does not apply");
            }
        }
        return findings.isEmpty() ? 0 : 1;
    }
}
