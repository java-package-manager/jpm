package dev.jpm;

import java.nio.file.Path;

/**
 * Where a module keeps its files. The jpm layout is {@code src/} with resources in {@code src/assets/}, and
 * {@code test/} with {@code test/assets/}; {@code "layout": "maven"} uses {@code src/main/java}, {@code src/main/resources},
 * {@code src/test/java} and {@code src/test/resources}, so imported Maven and Gradle projects build in place.
 */
public record Layout(String name, String sources, String resources, String tests, String testResources) {
    public static final Layout JPM = new Layout("jpm", "src", "src/assets", "test", "test/assets");
    public static final Layout MAVEN = new Layout("maven", "src/main/java", "src/main/resources", "src/test/java", "src/test/resources");

    public static Layout named(String name) {
        if (name == null || name.equals("jpm")) return JPM;
        if (name.equals("maven") || name.equals("gradle")) return MAVEN;
        return null;
    }

    public Path sources(Path dir) { return dir.resolve(sources); }
    public Path resources(Path dir) { return dir.resolve(resources); }
    public Path tests(Path dir) { return dir.resolve(tests); }
    public Path testResources(Path dir) { return dir.resolve(testResources); }
}
