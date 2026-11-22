package dev.jpm.version;

import java.util.Collection;
import java.util.Optional;

/** A constraint on versions: a Maven range, an npm-style range, or an exact version. */
public interface VersionSpec {
    boolean matches(Version v);

    /** The spec as written. */
    String text();

    /** True if only one version can match. */
    boolean isExact();

    /** The one version an exact spec allows, or null. */
    default Version exactVersion() {
        return null;
    }

    /** Highest matching version, preferring releases over pre-releases. */
    default Optional<Version> best(Collection<Version> candidates) {
        Version best = null;
        for (Version v : candidates) {
            if (!matches(v)) continue;
            if (v.isSnapshot() && !isExact()) continue;
            if (v.isPreRelease() && !isExact() && !text().contains("-")) continue;
            if (best == null || v.compareTo(best) > 0) best = v;
        }
        return Optional.ofNullable(best);
    }
}
