package dev.jpm.api.model;

import java.nio.file.Path;
import java.util.List;

/**
 * A resolved package on a module's classpaths.
 *
 * @param id           its id in the dependency graph, such as {@code org.example:lib} or {@code workspace:core}
 * @param kind         {@code maven}, {@code git}, {@code workspace}, {@code gradle}, {@code extension} or {@code patched}
 * @param version      its version, or null
 * @param commit       its commit, for Git packages, or null
 * @param scope        the narrowest scope it is needed in ({@code compile}, {@code provided}, {@code runtime},
 *                     {@code test}); null in {@link Classpaths}
 * @param module       the workspace module, or null
 * @param jars         its jars; empty for a workspace module
 * @param classes      a workspace module's classes folder, or null
 * @param sources      its sources jar or folder, or null
 * @param dependencies the ids of the packages it depends on
 */
public record Package(String id, String kind, String version, String commit, String scope, String module, List<Path> jars,
                      Path classes, Path sources, List<String> dependencies) {}
