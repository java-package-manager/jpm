package dev.jpm.cli.impl;

import dev.jpm.version.Tags;
import dev.jpm.Manifest;
import dev.jpm.Project;
import dev.jpm.git.GitRepo;
import dev.jpm.resolve.Graph;
import dev.jpm.log.Log;
import dev.jpm.version.Version;
import dev.jpm.version.VersionSpec;
import dev.jpm.version.VersionSpecs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class OutdatedCommand extends Command {

    OutdatedCommand(Path cwd) {
        super(cwd);
    }

    @Override
    String name() {
        return "outdated";
    }

    @Override
    List<Usage> usages() {
        return List.of(
                new Usage(Group.PROJECT, "outdated", "Show dependencies with newer versions"));
    }

    @Override
    int run(List<String> args) {
        noMoreFlags(args);
        Ctx c = open(false);
        c.repo().setRefreshMetadata(true);
        List<String[]> rows = new ArrayList<>();
        for (Project.Module m : c.project().modules) {
            for (Manifest.Dependency d : m.manifest.dependencies) {
                if (d.key().isWorkspace()) continue;
                Graph.Pkg pkg = c.graph().get(d.key().toString());
                if (pkg == null) continue;
                String latest, wanted;
                if (d.key().isGradle() || d.key().isExtension()) {
                    latest = DepArgs.latestSpec(c.repo(), d.key());
                    wanted = d.spec();
                } else if (d.key().isMaven()) {
                    List<Version> all = c.repo().versions(d.key().group(), d.key().artifact());
                    latest = DepArgs.latestSpec(c.repo(), d.key());
                    Optional<Version> w = VersionSpecs.parse(d.spec()).best(all);
                    wanted = w.map(Version::toString).orElse("-");
                } else {
                    String l = DepArgs.latestSpec(c.repo(), d.key());
                    latest = l.startsWith("^") ? l.substring(1) : l;
                    if (VersionSpecs.looksLikeSpec(d.spec())) {
                        List<Version> vs = new ArrayList<>();
                        Map<String, String> tags = GitRepo.of(d.key()).tags();
                        for (String t : tags.keySet()) {
                            String v = Tags.version(d.key().module(), t, tags.keySet());
                            if (v != null) vs.add(Version.of(v));
                        }
                        VersionSpec spec = VersionSpecs.parse(d.spec(), true);
                        wanted = spec.best(vs).map(Version::toString).orElse("-");
                    } else {
                        wanted = pkg.version;
                    }
                }
                if (!Version.of(pkg.version).equals(Version.of(latest)) || !Version.of(pkg.version).equals(Version.of(wanted))) {
                    rows.add(new String[]{d.key().toString(), pkg.version, wanted, latest, m.name() + " (" + d.scope().key + ")"});
                }
            }
        }
        if (rows.isEmpty()) {
            Log.info("All dependencies are up to date.");
            return 0;
        }
        rows.add(0, new String[]{"Package", "Current", "Wanted", "Latest", "Used by"});
        int[] w = new int[5];
        for (String[] r : rows) for (int i = 0; i < 5; i++) w[i] = Math.max(w[i], r[i].length());
        for (int i = 0; i < rows.size(); i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 5; j++) sb.append(String.format("%-" + (w[j] + 2) + "s", rows.get(i)[j]));
            Log.out(i == 0 ? Log.bold(sb.toString().strip()) : sb.toString().strip());
        }
        return 0;
    }
}
