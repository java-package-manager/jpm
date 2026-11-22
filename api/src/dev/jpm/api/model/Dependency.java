package dev.jpm.api.model;

import java.util.List;

/**
 * A dependency as {@code project.jsonc} declares it.
 *
 * @param key         its key, such as {@code com.google.guava:guava} or {@code github:me/lib}
 * @param kind        {@code maven}, {@code git}, {@code workspace}, {@code gradle} or {@code extension}
 * @param scope       {@code runtime}, {@code dev} or {@code test}
 * @param spec        the version or range as written
 * @param resolved    the locked version, or null
 * @param commit      the locked commit of a Git dependency, or null
 * @param exclude     transitive dependencies left out
 * @param runtimeOnly true when it is not on the compile classpath
 * @param api         true for a dev dependency that consumers compile against
 * @param tests       true when another module's tests come with it
 * @param group       the Maven group or Git owner, or null
 * @param artifact    the Maven artifact or Git repository, or null
 * @param classifier  the Maven classifier, or null
 * @param module      the workspace module or Git module, or null
 * @param host        the Git host ({@code github}, {@code gitlab}, {@code codeberg}), or null
 */
public record Dependency(String key, String kind, String scope, String spec, String resolved, String commit, List<String> exclude,
                         boolean runtimeOnly, boolean api, boolean tests, String group, String artifact, String classifier, String module,
                         String host) {}
