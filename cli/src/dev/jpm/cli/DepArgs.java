package dev.jpm.cli;

import dev.jpm.DepKey;
import dev.jpm.git.GitRepo;
import dev.jpm.maven.MavenRepo;
import dev.jpm.version.Version;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Dependency arguments on the command line: {@code group:artifact@1.2}, {@code github:me/lib#main} and the like. */
final class DepArgs {
    private DepArgs() {}


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
                String v = tagVersion(key, t, tags.keySet());
                if (v == null) continue;
                Version vv = Version.of(v);
                if (!vv.isPreRelease() && (best == null || vv.compareTo(best) > 0)) best = vv;
            }
            if (best != null) return "^" + best;
            return "#" + git.defaultBranch();
        }
        return "*";
    }

    static String tagVersion(DepKey key, String tag, Collection<String> all) {
        String module = key.module();
        boolean prefixed = module != null && all.stream().anyMatch(t -> t.startsWith(module + "/"));
        String v = tag;
        if (prefixed) {
            if (!tag.startsWith(module + "/")) return null;
            v = tag.substring(module.length() + 1);
        } else if (tag.contains("/")) return null;
        if (v.startsWith("v")) v = v.substring(1);
        return v.matches("\\d+(\\.\\d+){0,2}(-[0-9A-Za-z.-]+)?") ? v : null;
    }
}
