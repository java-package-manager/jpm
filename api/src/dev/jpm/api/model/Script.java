package dev.jpm.api.model;

import java.util.Map;

/**
 * A script from {@code "scripts"}, run with {@code jpm run <name>}.
 *
 * @param name         its name
 * @param run          the command, or the {@code .java} or {@code .sh} file
 * @param kind         {@code java}, {@code sh} or {@code command}
 * @param dependencies a {@code .java} script's own dependencies, key to version
 */
public record Script(String name, String run, String kind, Map<String, String> dependencies) {}
