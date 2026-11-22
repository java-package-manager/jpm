package dev.jpm.importer;

import dev.jpm.api.json.Jsonc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The result of importing a Maven or Gradle build: one manifest per module, plus what did not carry over. */
public final class Imported {
    /** Module directory relative to the import root ("" for the root) to manifest content. */
    public final Map<String, Map<String, Object>> manifests = new LinkedHashMap<>();
    public final List<String> warnings = new ArrayList<>();
    public final String tool;

    public Imported(String tool) {
        this.tool = tool;
    }

    public void warn(String module, String message) {
        String w = (module == null || module.isEmpty() ? "" : module + ": ") + message;
        if (!warnings.contains(w)) warnings.add(w);
    }

    /** Manifest text for a module, with a header saying where it came from. */
    public String render(String dir) {
        return "// Imported from " + tool + " by `jpm import`. Edit freely; jpm does not read the "
                + (tool.equals("Maven") ? "pom.xml" : "Gradle build") + " anymore.\n" + Jsonc.write(manifests.get(dir)) + "\n";
    }

    /** Smallest LTS JDK that can compile for the given release (at least 17, so modern tools run). */
    static String jdkFor(int release) {
        for (int lts : new int[]{17, 21, 25}) if (release <= lts) return Integer.toString(lts);
        return Integer.toString(release);
    }

    /** Parses "1.8", "8", "17", "VERSION_17", "JavaVersion.VERSION_1_8" into a major version, or null. */
    static Integer major(String v) {
        if (v == null) return null;
        String s = v.trim().replace("JavaVersion.", "").replace("VERSION_", "").replace('_', '.');
        if (s.startsWith("1.")) s = s.substring(2);
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        try {
            int n = Integer.parseInt(s);
            return n >= 5 && n < 100 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
