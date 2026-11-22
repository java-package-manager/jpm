package dev.jpm.impl;

import dev.jpm.DepKey;
import dev.jpm.Manifest;

import dev.jpm.log.Log;
import dev.jpm.util.JpmException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public final class ScopeChecks {
    private ScopeChecks() {}

    public static List<Manifest.Dependency> dedupe(Path file, List<Manifest.Dependency> deps) {
        Map<DepKey, Map<Manifest.Scope, Manifest.Dependency>> byKey = new TreeMap<>();
        for (Manifest.Dependency d : deps) byKey.computeIfAbsent(d.key(), k -> new EnumMap<>(Manifest.Scope.class)).put(d.scope(), d);
        Set<Manifest.Dependency> drop = new HashSet<>();
        for (Map<Manifest.Scope, Manifest.Dependency> scopes : byKey.values()) {
            if (scopes.size() < 2) continue;
            Manifest.Dependency runtime = scopes.get(Manifest.Scope.RUNTIME), dev = scopes.get(Manifest.Scope.DEV), test = scopes.get(Manifest.Scope.TEST);
            if (runtime != null && dev != null) {
                if (!sameVersion(runtime, dev)) {
                    throw JpmException.at(file, dev.line(), dev.key() + " is declared in \"runtime\" (" + runtime.spec()
                            + ") and in \"dev\" (" + dev.spec() + ") with different versions; runtime dependencies are on the"
                            + " compile classpath already, so remove the \"dev\" entry");
                }
                Log.warn(file + ":" + dev.line() + ": " + dev.key() + " is in \"runtime\" and \"dev\"; runtime dependencies are on"
                        + " the compile classpath already, so the \"dev\" entry is ignored. Remove it");
                drop.add(dev);
                dev = null;
            }
            if (runtime != null && test != null && test.tests() && sameVersion(runtime, test)) {
            } else if (runtime != null && test != null) {
                Log.warn(file + ":" + test.line() + ": " + test.key() + " is in \"runtime\" and \"test\"; tests have every runtime"
                        + " dependency already, so the \"test\" entry is ignored"
                        + (sameVersion(runtime, test) ? "" : " and " + runtime.spec() + " is used, not " + test.spec()) + ". Remove it");
                drop.add(test);
            } else if (dev != null && test != null && !sameVersion(dev, test)) {
                throw JpmException.at(file, test.line(), test.key() + " is declared in \"dev\" (" + dev.spec()
                        + ") and in \"test\" (" + test.spec() + ") with different versions; a project uses one version of a"
                        + " package, so give both the same");
            }
        }
        if (drop.isEmpty()) return deps;
        List<Manifest.Dependency> kept = new ArrayList<>(deps);
        kept.removeIf(drop::contains);
        return kept;
    }

    static boolean sameVersion(Manifest.Dependency a, Manifest.Dependency b) {
        return Objects.equals(a.spec() == null ? null : a.spec().strip(), b.spec() == null ? null : b.spec().strip());
    }
}
