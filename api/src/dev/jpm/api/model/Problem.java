package dev.jpm.api.model;

import java.nio.file.Path;

/**
 * Something wrong: a check's finding, or what an error is about.
 *
 * @param severity {@code error} or {@code warning}
 * @param message  what is wrong
 * @param file     the file it is in, or null
 * @param line     the 1-based line, or null
 * @param column   the 1-based column, or null
 * @param hint     what to do next, or null
 * @param check    the check that found it, or null
 */
public record Problem(String severity, String message, Path file, Integer line, Integer column, String hint, String check) {}
