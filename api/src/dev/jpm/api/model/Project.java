package dev.jpm.api.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A jpm project: one module, or a workspace of several.
 *
 * @param dir             the folder of the root {@code project.jsonc}
 * @param name            the root's name
 * @param version         the root's version, or null
 * @param workspace       true when the root lists workspace modules
 * @param repositories    the Maven repositories besides Maven Central
 * @param extensions      the root's settings for extensions, by extension name, as written
 * @param scripts         the root's scripts
 * @param modules         the modules, in build order
 * @param lockfileChanged true when loading wrote {@code project.lock}
 */
public record Project(Path dir, String name, String version, boolean workspace, List<String> repositories,
                      Map<String, Object> extensions, List<Script> scripts, List<Module> modules, boolean lockfileChanged) {
    /**
     * The module with this name.
     *
     * @param name a module's name
     * @return the module, or null when there is none
     */
    public Module module(String name) {
        for (Module m : modules) if (m.name().equals(name)) return m;
        return null;
    }
}
