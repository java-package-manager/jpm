package dev.jpm.api.model;

/**
 * A define, as the generated class holds it.
 *
 * @param name      the constant's name
 * @param type      {@code string}, {@code int} or {@code boolean}
 * @param value     the value: a {@link String}, a {@link Number} or a {@link Boolean}
 * @param className the generated class's fully qualified name
 */
public record Define(String name, String type, Object value, String className) {}
