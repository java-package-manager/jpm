package dev.jpm.importer;

import dev.jpm.importer.impl.GradleImport;
import dev.jpm.importer.impl.GradleScripts;
import dev.jpm.importer.impl.MavenImport;
import dev.jpm.maven.MavenRepo;
import dev.jpm.util.JpmException;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects the build tool of a directory and imports it.
 */
public final class Importer {
    private Importer() {
    }

    /**
     * "maven", "gradle", or null.
     */
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
        Imported imported = switch (tool) {
            case "maven" -> MavenImport.run(dir, repo);
            default -> noExecution ? GradleScripts.runStatic(dir, repo) : GradleImport.run(dir, repo);
        };
        licenseFromFile(dir, imported);
        return imported;
    }

    /**
     * A build that names no license gets the one its license file is, when jpm recognises the file.
     */
    static void licenseFromFile(Path dir, Imported imported) {
        java.util.Map<String, Object> root = imported.manifests.get("");
        if (root == null || root.containsKey("license")) return;
        if (imported.manifests.values().stream().anyMatch(m -> m.containsKey("license"))) return;
        Path file = dev.jpm.license.Licenses.file(dir);
        if (file == null) return;
        java.util.List<String> detected = dev.jpm.license.Licenses.detect(dev.jpm.util.Fs.read(file));
        if (detected.isEmpty()) {
            imported.warn("", file.getFileName() + " is a license jpm does not recognise; set \"license\" by hand");
            return;
        }
        java.util.Map<String, Object> withLicense = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Object> e : root.entrySet()) {
            withLicense.put(e.getKey(), e.getValue());
            if (e.getKey().equals("version") || (e.getKey().equals("name") && !root.containsKey("version"))) withLicense.put("license", detected.get(0));
        }
        if (!withLicense.containsKey("license")) withLicense.put("license", detected.get(0));
        root.clear();
        root.putAll(withLicense);
    }
}
