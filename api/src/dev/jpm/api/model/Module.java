package dev.jpm.api.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One module of a project.
 *
 * @param name         its name
 * @param group        its Maven group, or null
 * @param version      its version
 * @param description  its description, or null
 * @param license      its SPDX license, or null
 * @param url          its home page, or null
 * @param dir          its folder
 * @param relativeDir  its folder relative to the project's ({@code ""} for a single-module project)
 * @param visibility   {@code public} or {@code internal}
 * @param packaging    {@code jar} or {@code bundle} (the manifest's {@code "package"})
 * @param layout       {@code jpm} or {@code maven}
 * @param main         its entry points, by name ({@code main} for a single one)
 * @param folders      its folders
 * @param jar          where {@code jpm build} puts its jar
 * @param jdk          the JDK it builds with
 * @param dependencies its dependencies, as declared
 * @param packages     the packages on its classpaths, with the narrowest scope each is needed in
 * @param defines      its resolved defines
 * @param scripts      its scripts
 * @param extensions   its settings for extensions, as written
 * @param modulePath   for a named module, its module path and class path; otherwise null
 */
public record Module(String name, String group, String version, String description, String license, String url, Path dir,
                     String relativeDir, String visibility, String packaging, String layout, Map<String, String> main, Folders folders,
                     Path jar, Jdk jdk, List<Dependency> dependencies, List<Package> packages, List<Define> defines, List<Script> scripts,
                     Map<String, Object> extensions, ModuleSplit modulePath) {}
