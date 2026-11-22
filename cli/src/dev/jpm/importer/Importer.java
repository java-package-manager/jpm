package dev.jpm.importer;

import dev.jpm.maven.MavenRepo;
import dev.jpm.util.JpmException;

import java.nio.file.Files;
import java.nio.file.Path;

/** Detects the build tool of a directory and imports it. */
public final class Importer {
    private Importer() {}

    /** "maven", "gradle", or null. */
    public static String detect(Path dir) {
        if (Files.isRegularFile(dir.resolve("pom.xml"))) return "maven";
        for (String f : new String[]{"settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts"}) {
            if (Files.isRegularFile(dir.resolve(f))) return "gradle";
        }
        return null;
    }

    /**
     * Imports the build in {@code dir}. With {@code noExecution}, nothing from the build is executed:
     * Gradle builds are read as text instead of being evaluated by Gradle.
     */
    public static Imported run(Path dir, MavenRepo repo, boolean noExecution) {
        String tool = detect(dir);
        if (tool == null) throw new JpmException("No pom.xml or Gradle build found in " + dir);
        return switch (tool) {
            case "maven" -> MavenImport.run(dir, repo);
            default -> noExecution ? GradleImport.runStatic(dir, repo) : GradleImport.run(dir, repo);
        };
    }
}
