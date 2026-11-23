package dev.jpm.maven;

import dev.jpm.api.json.Jsonc;
import dev.jpm.util.Fs;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a library's Gradle Module Metadata (the .module file next to its POM) says about its dependencies
 * that a POM cannot: which ones it needs to compile against (the java-api variants) and which ones at run
 * time (the java-runtime variants). Gradle writes compileOnlyApi dependencies into the POM as compile
 * scope, though they are compile time only, provided by the platform at run time.
 */
public record GradleMetadata(Set<String> api, Set<String> runtime) {
    /**
     * Reads a .module file; null if it is unusable or its variants live in another component (available-at).
     */
    @SuppressWarnings("unchecked")
    public static GradleMetadata read(Path file) {
        Object parsed;
        try {
            parsed = Jsonc.parse(Fs.read(file));
        } catch (RuntimeException e) {
            return null;
        }
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("variants") instanceof List<?> variants)) return null;
        Set<String> api = new TreeSet<>(), runtime = new TreeSet<>();
        boolean any = false;
        for (Object v : variants) {
            if (!(v instanceof Map<?, ?> variant)) continue;
            Map<String, Object> attrs = variant.get("attributes") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
            Object category = attrs.get("org.gradle.category");
            if (category != null && !"library".equals(category)) continue;
            Object usage = attrs.get("org.gradle.usage");
            Set<String> into = "java-api".equals(usage) ? api : "java-runtime".equals(usage) ? runtime : null;
            if (into == null) continue;
            if (variant.get("available-at") != null) return null;
            any = true;
            if (variant.get("dependencies") instanceof List<?> deps) {
                for (Object d : deps) {
                    if (d instanceof Map<?, ?> dep && dep.get("group") != null && dep.get("module") != null) {
                        into.add(dep.get("group") + ":" + dep.get("module"));
                    }
                }
            }
        }
        return any ? new GradleMetadata(api, runtime) : null;
    }

    /**
     * Where the JVM variant of a Kotlin Multiplatform library lives: the {@code group:module:version} its java-runtime
     * variant names with {@code available-at} (kotlinx-coroutines-core is in kotlinx-coroutines-core-jvm), as Gradle
     * follows it. Null when the library itself is the JVM artifact.
     */
    @SuppressWarnings("unchecked")
    public static String jvmVariantAt(Path file) {
        Object parsed;
        try {
            parsed = Jsonc.parse(Fs.read(file));
        } catch (RuntimeException e) {
            return null;
        }
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("variants") instanceof List<?> variants)) return null;
        String jvm = null, fallback = null;
        for (Object v : variants) {
            if (!(v instanceof Map<?, ?> variant)) continue;
            Map<String, Object> attrs = variant.get("attributes") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
            if (!"java-runtime".equals(attrs.get("org.gradle.usage"))) continue;
            Object category = attrs.get("org.gradle.category");
            if (category != null && !"library".equals(category)) continue;
            if (!(variant.get("available-at") instanceof Map<?, ?> at)) return null;
            if (variant.get("capabilities") instanceof List<?> caps && !caps.isEmpty()) continue;
            if (at.get("group") == null || at.get("module") == null || at.get("version") == null) continue;
            String target = at.get("group") + ":" + at.get("module") + ":" + at.get("version");
            Object platform = attrs.get("org.jetbrains.kotlin.platform.type");
            if ("jvm".equals(platform) && jvm == null) jvm = target;
            if (platform == null && fallback == null) fallback = target;
        }
        return jvm != null ? jvm : fallback;
    }

    /**
     * True for a dependency the library compiles against but does not need at run time.
     */
    public boolean compileOnly(String key) {
        return api.contains(key) && !runtime.contains(key);
    }

    /**
     * True for a dependency the library needs at run time but not to compile against it.
     */
    public boolean runtimeOnly(String key) {
        return runtime.contains(key) && !api.contains(key);
    }
}
