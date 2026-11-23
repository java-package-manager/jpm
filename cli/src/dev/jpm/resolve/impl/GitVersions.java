package dev.jpm.resolve.impl;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.git.GitRepo;
import dev.jpm.log.Log;
import dev.jpm.resolve.GitModule;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Resolver;
import dev.jpm.resolve.Resolver.Request;
import dev.jpm.resolve.Resolver.Selected;
import dev.jpm.util.JpmException;
import dev.jpm.version.Tags;
import dev.jpm.version.Version;
import dev.jpm.version.VersionSpec;
import dev.jpm.version.VersionSpecs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public final class GitVersions {
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{7,40}");

    private final Graph previous;
    private final Predicate<String> locked;
    private final Map<String, Map<String, String>> tagMemo = new HashMap<>();

    public GitVersions(Graph previous, Predicate<String> locked) {
        this.previous = previous;
        this.locked = locked;
    }

    String branchCommit(String id, GitRepo git, String branch, Request r) {
        Graph.Pkg prev = previous == null ? null : previous.get(id);
        return locked.test(id) && prev.tag == null && prev.commit != null && r.direct() ? prev.commit : git.branchHead(branch);
    }

    public Selected choose(String id, List<Request> reqs) {
        DepKey key = DepKey.parse(id);
        GitRepo git = GitRepo.of(key);
        List<Selected> pins = new ArrayList<>();
        List<Request> pinReqs = new ArrayList<>();
        List<VersionSpec> ranges = new ArrayList<>();
        boolean onlySiblingPins = reqs.stream().allMatch(r -> r.spec().trim().startsWith("@"));
        for (Request r : reqs) {
            String s = r.spec().trim();
            if (s.startsWith("@") && !onlySiblingPins) continue;
            if (s.startsWith("@")) {
                pins.add(commitSelection(key, s.substring(1), null));
                pinReqs.add(r);
            } else if (s.startsWith("#")) {
                pins.add(commitSelection(key, branchCommit(id, git, s.substring(1), r), null));
                pinReqs.add(r);
            } else if (HEX.matcher(s).matches() && !VersionSpecs.looksLikeSpec(s)) {
                String full = git.resolveCommit(s);
                if (full == null) throw new JpmException(id + ": commit " + s + " not found");
                pins.add(commitSelection(key, full, null));
                pinReqs.add(r);
            } else if (VersionSpecs.looksLikeSpec(s)) {
                try {
                    ranges.add(VersionSpecs.parse(s, true));
                } catch (IllegalArgumentException e) {
                    throw new JpmException(id + " (required by " + r.from() + "): " + e.getMessage());
                }
            } else {
                Map<String, String> tags = tags(key);
                String commit = tags.get(s);
                if (commit != null) {
                    pins.add(tagSelection(key, s, commit));
                } else if (git.hasBranch(s)) {
                    pins.add(commitSelection(key, branchCommit(id, git, s, r), null));
                } else {
                    throw new JpmException(id + ": no tag or branch \"" + s + "\" in " + key.repoKey()
                            + " (a version range, tag, branch or commit; #name is always a branch)");
                }
                pinReqs.add(r);
            }
        }
        if (!pins.isEmpty()) {
            Selected chosen = null;
            for (int i = 0; i < pins.size(); i++) {
                if (pinReqs.get(i).direct()) {
                    chosen = pins.get(i);
                    break;
                }
            }
            if (chosen == null) {
                for (Selected s : pins) {
                    if (chosen == null || Version.of(s.version()).compareTo(Version.of(chosen.version())) > 0) chosen = s;
                }
            }
            for (Selected s : pins) {
                if (!s.commit().equals(chosen.commit())) {
                    Log.warn(id + " is pinned to several commits; using " + chosen.commit().substring(0, 7) + " (" + chosen.version() + ")");
                    break;
                }
            }
            for (VersionSpec r : ranges) {
                if (!r.matches(Version.of(stripBuild(chosen.version())))) {
                    throw Resolver.conflict(id, reqs);
                }
            }
            return chosen;
        }
        Map<String, String> tags = tags(key);
        Map<Version, String> byVersion = new LinkedHashMap<>();
        for (String tag : tags.keySet()) {
            String v = Tags.version(key.module(), tag, tags.keySet());
            if (v != null) byVersion.putIfAbsent(Version.of(v), tag);
        }
        if (locked.test(id)) {
            Graph.Pkg p = previous.get(id);
            if (p.tag != null && tags.containsKey(p.tag) && tags.get(p.tag).equals(p.commit)) {
                Version v = Version.of(p.version);
                boolean ok = true;
                for (VersionSpec r : ranges) ok &= r.matches(v);
                if (ok) return new Selected(p.version, p.commit, p.tag);
            }
        }
        Version best = null;
        for (Version v : byVersion.keySet()) {
            boolean ok = true;
            for (VersionSpec r : ranges) {
                if (!r.matches(v) || (v.isPreRelease() && !r.text().contains("-"))) {
                    ok = false;
                    break;
                }
            }
            if (ok && (best == null || v.compareTo(best) > 0)) best = v;
        }
        if (best == null) {
            StringBuilder sb = new StringBuilder("No tag of " + key.repoKey() + " matches every requirement for " + id + ":");
            for (Request r : reqs) sb.append("\n  ").append(r.spec()).append("  required by ").append(r.from());
            List<String> avail = byVersion.keySet().stream().sorted().map(Version::toString).toList();
            sb.append("\nAvailable versions: ").append(avail.isEmpty() ? "(no version tags)" : String.join(", ", avail));
            throw new JpmException(sb.toString());
        }
        String tag = byVersion.get(best);
        return tagSelection(key, tag, tags.get(tag));
    }

    static String stripBuild(String v) {
        int p = v.indexOf('+');
        return p < 0 ? v : v.substring(0, p);
    }

    Map<String, String> tags(DepKey key) {
        return tagMemo.computeIfAbsent(key.repoKey().toString(), k -> GitRepo.of(key).tags());
    }

    Selected tagSelection(DepKey key, String tag, String commit) {
        GitModule mod = GitModule.locate(key, commit);
        String declared = mod.version();
        String fromTag = Tags.version(key.module(), tag, tags(key).keySet());
        if (fromTag != null && !Version.of(fromTag).equals(Version.of(declared))) {
            throw new JpmException(key + ": tag " + tag + " points to a commit whose " + Manifest.FILE
                    + " says version " + declared + ". Tags must match the manifest version.");
        }
        return new Selected(fromTag != null ? fromTag : declared, commit, tag);
    }

    Selected commitSelection(DepKey key, String commit, String tag) {
        Map<String, String> tags = tags(key);
        GitModule mod = GitModule.locate(key, commit);
        for (Map.Entry<String, String> t : tags.entrySet()) {
            String fromTag = t.getValue().equals(commit) ? Tags.version(key.module(), t.getKey(), tags.keySet()) : null;
            if (fromTag != null && Version.of(fromTag).equals(Version.of(mod.version()))) return new Selected(fromTag, commit, t.getKey());
        }
        return new Selected(mod.version() + "+" + commit.substring(0, 7), commit, tag);
    }
}
