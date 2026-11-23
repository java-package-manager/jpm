package dev.jpm.resolve;

import dev.jpm.DepKey;
import dev.jpm.Manifest;
import dev.jpm.git.GitRepo;
import dev.jpm.log.Log;
import dev.jpm.maven.MavenRepo;
import dev.jpm.util.JpmException;
import dev.jpm.version.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The minimum release age of a project (ADR 0018): how old a version must be before jpm picks it, and when each
 * version was released.
 */
public final class ReleaseAge {
    private static final ReleaseAge NONE = new ReleaseAge(null, Map.of(), null, Instant.EPOCH);

    /**
     * The minimum age, or null for none.
     */
    public final Duration minimum;
    /**
     * Package id to the one version that may be younger.
     */
    public final Map<String, String> exceptions;

    private final MavenRepo repo;
    private final Instant now;
    private final Map<String, Map<String, Instant>> tagDates = new HashMap<>();
    private final Set<String> warned = new HashSet<>();

    private ReleaseAge(Duration minimum, Map<String, String> exceptions, MavenRepo repo, Instant now) {
        this.minimum = minimum;
        this.exceptions = exceptions;
        this.repo = repo;
        this.now = now;
    }

    /**
     * The policy of a workspace root's manifest, measured against now.
     */
    public static ReleaseAge of(Manifest root, MavenRepo repo) {
        if (root == null || root.minimumReleaseAge == null) return NONE;
        return new ReleaseAge(root.minimumReleaseAge, root.releaseAgeExceptions, repo, Instant.now());
    }

    /**
     * No minimum: every version is old enough.
     */
    public static ReleaseAge none() {
        return NONE;
    }

    /**
     * Whether there is a minimum at all.
     */
    public boolean active() {
        return minimum != null;
    }

    /**
     * Whether jpm may pick this version of a Maven package: there is no minimum, it is excepted, it is a snapshot,
     * it is old enough, or nobody can say when it was released.
     */
    public boolean allowsMaven(String id, Version v) {
        if (!active() || excepted(id, v.toString()) || isSnapshot(v.toString())) return true;
        String[] ga = id.split(":");
        Optional<Instant> released;
        try {
            released = repo.released(ga[0], ga[1], v.toString());
        } catch (JpmException e) {
            Log.debug(e.getMessage());
            return true;
        }
        return released.isEmpty() || oldEnough(released.get());
    }

    /**
     * Whether jpm may pick this tag of a Git package.
     */
    public boolean allowsTag(DepKey key, String tag, String version) {
        if (!active() || excepted(key.toString(), version)) return true;
        Instant made = tagDate(key, tag);
        return made == null || oldEnough(made);
    }

    /**
     * When a tag of a Git package was made, or null when the mirror does not say.
     */
    public Instant tagDate(DepKey key, String tag) {
        return tagDates.computeIfAbsent(key.repoKey().toString(), k -> GitRepo.of(key).tagDates()).get(tag);
    }

    /**
     * When a Maven version was released; empty when the repository does not say.
     */
    public Optional<Instant> mavenReleased(String id, String version) {
        String[] ga = id.split(":");
        return repo.released(ga[0], ga[1], version);
    }

    /**
     * Whether something released then is at least the minimum age now.
     */
    public boolean oldEnough(Instant released) {
        return !released.plus(minimum).isAfter(now);
    }

    /**
     * How long ago something was released, for messages: "3 days", "5 hours".
     */
    public String ago(Instant released) {
        return describe(Duration.between(released, now));
    }

    /**
     * A duration in its largest whole unit, such as "7 days" or "1 hour".
     */
    public static String describe(Duration d) {
        long minutes = Math.max(0, d.toMinutes());
        long day = 60L * 24, week = 7 * day;
        if (minutes >= 2 * week && minutes % week == 0) return plural(minutes / week, "week");
        if (minutes >= day) return plural(minutes / day, "day");
        if (minutes >= 60) return plural(minutes / 60, "hour");
        return plural(minutes, "minute");
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }

    /**
     * Whether {@code releaseAgeExceptions} lets this version of the package be younger.
     */
    public boolean excepted(String id, String version) {
        return version.equals(exceptions.get(id));
    }

    /**
     * Whether a version is a snapshot, which has no release to wait for.
     */
    public static boolean isSnapshot(String version) {
        return version.endsWith("-SNAPSHOT") || version.matches(".+-\\d{8}\\.\\d{6}-\\d+");
    }

    /**
     * The highest of {@code candidates} jpm may pick, by asking {@code best} again without each one that is too
     * young; the first answer when none is old enough, with a warning. Empty only when {@code best} gives nothing.
     */
    public Optional<Version> pick(String id, List<Version> candidates, Function<List<Version>, Optional<Version>> best) {
        Optional<Version> first = best.apply(candidates);
        if (!active() || first.isEmpty()) return first;
        List<Version> left = new ArrayList<>(candidates);
        for (Optional<Version> v = first; v.isPresent(); v = best.apply(left)) {
            if (allowsMaven(id, v.get())) {
                if (!v.equals(first)) Log.debug("minimumReleaseAge: " + id + " " + first.get() + " is too young, taking " + v.get());
                return v;
            }
            left.remove(v.get());
        }
        warnTooYoung(id, first.get());
        return first;
    }

    /**
     * Warns, once per package, that jpm takes a version younger than the minimum because none that fits is older.
     */
    public void warnTooYoung(String id, Version v) {
        if (!warned.add(id + "@" + v)) return;
        Log.warn(id + " " + v + " is younger than the minimum release age (" + describe(minimum)
                + "), and no version that fits is older; `jpm check` fails until it is old enough or excepted in releaseAgeExceptions");
    }
}
