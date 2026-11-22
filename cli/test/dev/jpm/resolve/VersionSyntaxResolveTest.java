package dev.jpm.resolve;

import dev.jpm.testing.TestRepo;
import dev.jpm.Project;
import dev.jpm.maven.MavenRepo;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Resolves a project that uses every version syntax against a local Maven repository. */
class VersionSyntaxResolveTest {
    private static void publish(Path repo, String artifact, List<String> versions) {
        for (String v : versions) new TestRepo(repo).artifact("org.example:" + artifact + ":" + v).publish();
    }

    @Test
    void everySyntaxResolves(@TempDir Path tmp) throws IOException {
        System.setProperty("jpm.home", tmp.resolve("home").toString());
        Path repo = tmp.resolve("repo");
        List<String> versions = List.of("1.0", "1.2.0", "1.10.0", "2.0.0", "2.1.0-beta", "2.2.0-SNAPSHOT");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("1.2.0", "1.2.0");
        expected.put("[1.0,2.0)", "1.10.0");
        expected.put("(,1.0],[1.2,)", "2.0.0");
        expected.put("RELEASE", "2.0.0");
        expected.put("LATEST", "2.2.0-SNAPSHOT");
        expected.put("1.+", "1.10.0");
        expected.put("1.2.+", "1.2.0");
        expected.put("+", "2.2.0-SNAPSHOT");
        expected.put("]1.0,2.0[", "1.10.0");
        expected.put("latest.release", "2.0.0");
        expected.put("latest.milestone", "2.1.0-beta");
        expected.put("latest.integration", "2.2.0-SNAPSHOT");
        expected.put("1.2.0!!", "1.2.0");
        expected.put("[1.0,2.0)!!1.2.0", "1.2.0");
        expected.put("^1.2", "1.10.0");
        expected.put("~1.2", "1.2.0");
        expected.put("~1", "1.10.0");
        expected.put(">=0.1", "2.0.0");
        expected.put(">=1.0 <1.5", "1.2.0");
        expected.put("1.x", "1.10.0");
        expected.put("1.0 - 1.5", "1.2.0");
        expected.put("^1 || ^2", "2.0.0");
        expected.put("=1.2.0", "1.2.0");

        StringBuilder deps = new StringBuilder();
        int i = 0;
        Map<String, String> artifactOf = new LinkedHashMap<>();
        for (String spec : expected.keySet()) {
            String a = "lib" + i++;
            publish(repo, a, versions);
            artifactOf.put(spec, a);
            if (deps.length() > 0) deps.append(",\n");
            deps.append("    \"org.example:").append(a).append("\": ").append(dev.jpm.api.json.Jsonc.quote(spec));
        }
        Path project = tmp.resolve("project");
        Fs.write(project.resolve("project.jsonc"), "{\n  \"name\": \"p\",\n  \"version\": \"1.0.0\",\n"
                + "  \"repositories\": [" + dev.jpm.api.json.Jsonc.quote(repo.toUri().toString()) + "],\n"
                + "  \"runtime\": {\n" + deps + "\n  }\n}\n");

        Project p = Project.load(project);
        Graph g = Install.run(p, new MavenRepo(p.root.repositories), new Install.Options(false, true, java.util.Set.of(), true));
        for (Map.Entry<String, String> e : expected.entrySet()) {
            assertEquals(e.getValue(), g.get("org.example:" + artifactOf.get(e.getKey())).version, e.getKey());
        }
    }
}
