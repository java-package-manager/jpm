package dev.jpm.api.model;

import java.nio.file.Path;

/**
 * The JDK a module builds with.
 *
 * @param spec    the manifest's {@code "java"}, or null
 * @param home    the JDK's home folder, or null when none was found
 * @param release the bytecode target, or null when no JDK was found
 * @param error   why no JDK was found, or null
 */
public record Jdk(String spec, Path home, Integer release, String error) {}
