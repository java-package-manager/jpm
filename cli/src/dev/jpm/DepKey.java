package dev.jpm;

import dev.jpm.util.JpmException;

import java.util.Locale;

/**
 * A dependency name as written in the manifest.
 *
 * <ul>
 *   <li>{@code group:artifact} — Maven; {@code group:artifact:classifier} for a classified jar
 *       such as {@code natives-linux}</li>
 *   <li>{@code github:user/repo} or {@code github:user/repo/module} — Git (also {@code gitlab:}, {@code codeberg:})</li>
 *   <li>{@code workspace:module} — a sibling module in the same workspace</li>
 *   <li>{@code gradle:api} — the Gradle API, from a Gradle distribution, for Gradle plugins</li>
 *   <li>{@code ext:extension/name} — provided by the extension {@code jpm-extension}, such as {@code ext:paperweight/server}</li>
 * </ul>
 *
 * @param module the Git module, or the Maven classifier
 */
public record DepKey(Kind kind, String host, String group, String artifact, String module) implements Comparable<DepKey> {
    public enum Kind {
        MAVEN, GIT, WORKSPACE, GRADLE, EXTENSION
    }

    /**
     * Parses a dependency key as written in {@code project.jsonc}; see the class description for the forms.
     */
    public static DepKey parse(String s) {
        s = s.trim();
        int colon = s.indexOf(':');
        if (colon <= 0) throw new JpmException("Invalid dependency \"" + s + "\": expected group:artifact or github:user/repo");
        String prefix = s.substring(0, colon).toLowerCase(Locale.ROOT);
        String rest = s.substring(colon + 1);
        switch (prefix) {
            case "github", "gitlab", "codeberg" -> {
                String[] parts = rest.split("/");
                if (parts.length < 2 || parts.length > 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
                    throw new JpmException("Invalid Git dependency \"" + s + "\": expected " + prefix + ":user/repo or " + prefix + ":user/repo/module");
                }
                String repo = parts[1].endsWith(".git") ? parts[1].substring(0, parts[1].length() - 4) : parts[1];
                return new DepKey(Kind.GIT, prefix, parts[0], repo, parts.length == 3 ? parts[2] : null);
            }
            case "gradle" -> {
                if (!rest.equals("api")) {
                    throw new JpmException("Invalid Gradle dependency \"" + s + "\": the only one is gradle:api, the Gradle API of a"
                            + " Gradle distribution (the version is the Gradle version)");
                }
                return new DepKey(Kind.GRADLE, null, null, "api", null);
            }
            case "ext" -> {
                if (!rest.contains("/")) return maven(s);
                String[] parts = rest.split("/", -1);
                if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9][A-Za-z0-9_-]*") || !parts[1].matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                    throw new JpmException("Invalid extension dependency \"" + s + "\": expected ext:<extension>/<name>, e.g. ext:paperweight/server");
                }
                return new DepKey(Kind.EXTENSION, null, parts[0], parts[1], null);
            }
            case "workspace" -> {
                if (rest.isEmpty() || rest.contains(":")) throw new JpmException("Invalid workspace dependency \"" + s + "\"");
                return new DepKey(Kind.WORKSPACE, null, null, rest, null);
            }
            default -> {
                return maven(s);
            }
        }
    }

    private static DepKey maven(String s) {
        String[] parts = s.split(":", -1);
        boolean ok = parts.length >= 2 && parts.length <= 3;
        for (String p : parts) ok &= !p.isEmpty();
        if (!ok || (parts.length == 3 && Character.isDigit(parts[2].charAt(0)))) {
            throw new JpmException("Invalid Maven dependency \"" + s + "\": expected group:artifact or group:artifact:classifier"
                    + " (put the version on the right-hand side)");
        }
        return new DepKey(Kind.MAVEN, null, parts[0], parts[1], parts.length == 3 ? parts[2] : null);
    }

    /**
     * The key of a Maven package {@code group:artifact}.
     */
    public static DepKey maven(String group, String artifact) {
        return new DepKey(Kind.MAVEN, null, group, artifact, null);
    }

    /**
     * The key of a Maven package with a classifier, {@code group:artifact:classifier}; a null classifier means none.
     */
    public static DepKey maven(String group, String artifact, String classifier) {
        return new DepKey(Kind.MAVEN, null, group, artifact, classifier == null || classifier.isEmpty() ? null : classifier);
    }

    /**
     * Maven classifier, or null.
     */
    public String classifier() {
        return kind == Kind.MAVEN ? module : null;
    }

    /**
     * True for {@code github:}, {@code gitlab:} and {@code codeberg:} keys.
     */
    public boolean isGit() {
        return kind == Kind.GIT;
    }

    /**
     * True for Maven packages.
     */
    public boolean isMaven() {
        return kind == Kind.MAVEN;
    }

    /**
     * True for {@code workspace:<module>}.
     */
    public boolean isWorkspace() {
        return kind == Kind.WORKSPACE;
    }

    /**
     * True for {@code gradle:api}.
     */
    public boolean isGradle() {
        return kind == Kind.GRADLE;
    }

    /**
     * True for {@code ext:<extension>/<name>}.
     */
    public boolean isExtension() {
        return kind == Kind.EXTENSION;
    }

    /**
     * The repository part of a Git key without the module, e.g. {@code github:user/repo}.
     */
    public DepKey repoKey() {
        return new DepKey(kind, host, group, artifact, null);
    }

    /**
     * The same key with another Git module (or Maven classifier).
     */
    public DepKey withModule(String m) {
        return new DepKey(kind, host, group, artifact, m);
    }

    /**
     * The URL to clone a Git dependency from; {@code JPM_GIT_BASE_<HOST>} replaces the host (ADR 0007).
     */
    public String gitUrl() {
        String base = switch (host) {
            case "github" -> "https://github.com/";
            case "gitlab" -> "https://gitlab.com/";
            case "codeberg" -> "https://codeberg.org/";
            default -> throw new IllegalStateException(host);
        };
        String override = System.getenv("JPM_GIT_BASE_" + host.toUpperCase(Locale.ROOT));
        if (override != null && !override.isBlank()) base = override.endsWith("/") ? override : override + "/";
        return base + group + "/" + artifact + (base.startsWith("http") ? ".git" : "");
    }

    /**
     * Maven-style coordinates under which a Git dependency is cached and resolved.
     */
    public String mavenGroup() {
        return switch (kind) {
            case MAVEN -> group;
            case GIT -> "git." + host + "." + group.toLowerCase(Locale.ROOT) + "." + artifact.toLowerCase(Locale.ROOT);
            case WORKSPACE -> "workspace";
            case GRADLE -> "gradle";
            case EXTENSION -> "ext." + group;
        };
    }

    /**
     * The artifact id under which the package is cached and published: a Git dependency's module, or its repository.
     */
    public String mavenArtifact() {
        return switch (kind) {
            case MAVEN -> artifact;
            case GIT -> module != null ? module : artifact;
            case WORKSPACE, GRADLE, EXTENSION -> artifact;
        };
    }

    @Override
    public String toString() {
        return switch (kind) {
            case MAVEN -> group + ":" + artifact + (module != null ? ":" + module : "");
            case GIT -> host + ":" + group + "/" + artifact + (module != null ? "/" + module : "");
            case WORKSPACE -> "workspace:" + artifact;
            case GRADLE -> "gradle:" + artifact;
            case EXTENSION -> "ext:" + group + "/" + artifact;
        };
    }

    @Override
    public int compareTo(DepKey o) {
        return toString().compareTo(o.toString());
    }
}
