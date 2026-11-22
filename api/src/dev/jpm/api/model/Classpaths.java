package dev.jpm.api.model;

import java.util.List;

/**
 * Every classpath of a module, its packages in classpath order.
 *
 * @param compile     what the main sources compile against
 * @param processor   annotation processors
 * @param runtime     what the module runs with
 * @param testCompile what the tests compile against
 * @param testRuntime what the tests run with
 * @param modulePath  for a named module, its module path and class path; otherwise null
 */
public record Classpaths(List<Package> compile, List<Package> processor, List<Package> runtime, List<Package> testCompile,
                         List<Package> testRuntime, ModuleSplit modulePath) {}
