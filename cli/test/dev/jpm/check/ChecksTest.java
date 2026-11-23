package dev.jpm.check;

import dev.jpm.api.json.Jsonc;
import dev.jpm.check.impl.ClassFiles;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChecksTest {
    @Test
    void readsEveryClassAClassReferTo(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src");
        Fs.write(src.resolve("lib/Marker.java"), "package lib; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)"
                + " public @interface Marker {}");
        Fs.write(src.resolve("lib/Box.java"), "package lib; public class Box<T> {}");
        Fs.write(src.resolve("lib/Item.java"), "package lib; public class Item {}");
        Fs.write(src.resolve("app/App.java"), "package app; @lib.Marker public class App { lib.Box<lib.Item> box; lib.Item[] items; }");
        Path out = dir.resolve("classes");
        int code = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(),
                src.resolve("lib/Marker.java").toString(), src.resolve("lib/Box.java").toString(), src.resolve("lib/Item.java").toString(),
                src.resolve("app/App.java").toString());
        assertEquals(0, code);
        Fs.deleteTree(out.resolve("lib"));
        Set<String> refs = ClassFiles.referencedClasses(out);
        assertTrue(refs.containsAll(List.of("lib/Marker", "lib/Box", "lib/Item", "java/lang/Object")), refs.toString());
        assertEquals(Set.of("app/App"), ClassFiles.classesIn(out).keySet());
    }

    @Test
    void aFilterThatMatchesNoResourceIsAWarning(@TempDir Path dir) {
        System.setProperty("jpm.home", dir.resolve("home").toString());
        Path p = dir.resolve("p");
        Fs.write(p.resolve("project.jsonc"), "{ \"name\": \"p\",\n  \"filter\": [\"*.properties\", \"*.yml\", \"conf/[a\"] }");
        Fs.write(p.resolve("src/assets/app.properties"), "v=${VERSION}");
        List<Problem> problems = ProjectChecks.paths(dev.jpm.Project.load(p));
        assertEquals(List.of("the filter *.yml matches no resource", "the filter conf/[a is not a glob"),
                problems.stream().map(x -> x.message().replaceFirst(": .*", "")).toList(), problems.toString());
        assertEquals(List.of(Problem.Severity.WARNING, Problem.Severity.ERROR), problems.stream().map(Problem::severity).toList());
        assertEquals(2, problems.get(0).line());
    }

    @Test
    void writesSarifAndJunit() {
        Path root = Path.of("/p").toAbsolutePath();
        List<CheckResult> results = List.of(
                new CheckResult("paths", "files exist", List.of(Problem.error("paths", "the <gen> folder does not exist", root.resolve("project.jsonc"), 4)), null),
                new CheckResult("license", "a license", List.of(Problem.warning("license", "no license", null, null).withHint("add one")), null),
                new CheckResult("tests", "tests pass", List.of(), "an earlier check failed"));
        @SuppressWarnings("unchecked") Map<String, Object> sarif = (Map<String, Object>) Jsonc.parse(Reports.sarif(results, root, "1.0"));
        assertEquals("2.1.0", sarif.get("version"));
        String text = Reports.sarif(results, root, "1.0");
        assertTrue(text.contains("\"uri\": \"project.jsonc\"") && text.contains("\"startLine\": 4") && text.contains("\"level\": \"warning\""), text);
        assertTrue(text.contains("no license (add one)"), text);
        String junit = Reports.junit(results, root);
        assertTrue(junit.contains("tests=\"3\" failures=\"1\" errors=\"0\" skipped=\"1\""), junit);
        assertTrue(junit.contains("project.jsonc:4: error: the &lt;gen&gt; folder does not exist"), junit);
        assertTrue(junit.contains("<skipped message=\"an earlier check failed\"/>"), junit);
        assertFalse(results.get(1).failed());
        assertTrue(results.get(0).failed());
    }
}
