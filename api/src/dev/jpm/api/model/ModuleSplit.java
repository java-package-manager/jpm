package dev.jpm.api.model;

import java.nio.file.Path;
import java.util.List;

/**
 * How a named module is compiled: its dependencies split into module path and class path.
 *
 * @param name       the module's name, from {@code module-info.java}
 * @param modulePath the module path
 * @param classPath  the class path
 * @param options    the javac options for both, as jpm compiles the module with them
 */
public record ModuleSplit(String name, List<Path> modulePath, List<Path> classPath, List<String> options) {}
