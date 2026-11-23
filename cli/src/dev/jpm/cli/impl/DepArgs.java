package dev.jpm.cli.impl;

import dev.jpm.DepKey;
import dev.jpm.git.GitRepo;
import dev.jpm.maven.MavenRepo;
import dev.jpm.version.Tags;
import dev.jpm.version.Version;

import java.util.List;
import java.util.Map;

final class DepArgs {
    private DepArgs() {
    }

    record DepArg(DepKey key, String spec) {}

    static DepArg parse(String s) {
        String keyPart = s, spec = null;
        int hash = s.indexOf('#');
        int at = s.indexOf('@');
        if (hash > 0 && (at < 0 || hash < at)) {
            keyPart = s.substring(0, hash);
            spec = s.substring(hash);
        } else if (at > 0) {
            keyPart = s.substring(0, at);
            spec = s.substring(at + 1);
        } else if (!s.startsWith("github:") && !s.startsWith("gitlab:") && !s.startsWith("codeberg:")
                && !s.startsWith("workspace:")) {
            String[] parts = s.split(":");
            if ((parts.length == 3 && !parts[2].isEmpty() && Character.isDigit(parts[2].charAt(0))) || parts.length == 4) {
                int last = s.lastIndexOf(':');
                keyPart = s.substring(0, last);
                spec = s.substring(last + 1);
            }
        }
        return new DepArg(DepKey.parse(keyPart), spec == null || spec.isBlank() ? null : spec);
    }

    static String latestSpec(MavenRepo repo, DepKey key) {
        if (key.isGradle()) return dev.jpm.resolve.GradleDist.latest();
        if (key.isExtension()) return dev.jpm.resolve.ExtensionDeps.resolve(key, "latest").version();
        if (key.isMaven()) {
            List<Version> all = repo.versions(key.group(), key.artifact());
            Version best = null;
            for (Version v : all) if (!v.isPreRelease() && !v.isSnapshot() && (best == null || v.compareTo(best) > 0)) best = v;
            if (best == null) best = all.get(all.size() - 1);
            return best.toString();
        }
        if (key.isGit()) {
            GitRepo git = GitRepo.of(key);
            Map<String, String> tags = git.tags();
            Version best = null;
            for (String t : tags.keySet()) {
                String v = Tags.version(key.module(), t, tags.keySet());
                if (v == null) continue;
                Version vv = Version.of(v);
                if (!vv.isPreRelease() && (best == null || vv.compareTo(best) > 0)) best = vv;
            }
            if (best != null) return "^" + best;
            return "#" + git.defaultBranch();
        }
        return "*";
    }

}
