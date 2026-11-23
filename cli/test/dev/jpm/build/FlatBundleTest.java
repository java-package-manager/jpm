package dev.jpm.build;

import dev.jpm.Project;
import dev.jpm.maven.MavenRepo;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Install;
import dev.jpm.testing.TestRepo;
import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jpm build --bundle for modules without a main: one flat jar with the runtime dependencies merged in.
 */
class FlatBundleTest {
    private static void zip(Path jar, Map<String, String> entries) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void publish(Path repo, String artifact, String version, Map<String, String> entries) {
        TestRepo.Artifact a = new TestRepo(repo).artifact("org.example:" + artifact + ":" + version);
        entries.forEach(a::entry);
        a.publish();
    }

    private static Map<String, String> read(Path jar) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory()) out.put(e.getName(), new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    @Test
    void mergeRules(@TempDir Path tmp) throws IOException {
        Path own = tmp.resolve("own.jar"), a = tmp.resolve("a.jar"), b = tmp.resolve("b.jar");
        zip(own, Map.of("p/Plugin.class", "own", "plugin.yml", "main: p.Plugin", "module-info.class", "mine"));
        zip(a, Map.of("a/A.class", "a1", "META-INF/services/x.Api", "a.Impl\n", "META-INF/A.SF", "sig", "module-info.class", "a",
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n"));
        zip(b, Map.of("a/A.class", "a2", "META-INF/services/x.Api", "# comment\nb.Impl\na.Impl\n", "META-INF/B.RSA", "sig"));
        Path out = tmp.resolve("out.jar");
        Jars.merge(out, List.of(own, a, b), Map.of("Implementation-Title", "p"));
        Map<String, String> e = read(out);
        assertEquals("own", e.get("p/Plugin.class"));
        assertEquals("a1", e.get("a/A.class"), "the first copy wins");
        assertEquals("a.Impl\nb.Impl\n", e.get("META-INF/services/x.Api"), "service files are joined");
        assertEquals("mine", e.get("module-info.class"), "only the module's own descriptor stays");
        assertFalse(e.containsKey("META-INF/A.SF") || e.containsKey("META-INF/B.RSA"), "signatures are dropped");
        assertTrue(e.get("META-INF/MANIFEST.MF").contains("Implementation-Title: p"));
    }

    @Test
    void pluginWithoutMainBundlesItsRuntimeDependencies(@TempDir Path tmp) throws IOException {
        System.setProperty("jpm.home", tmp.resolve("home").toString());
        Path repo = tmp.resolve("repo");
        publish(repo, "files", "4.0.0", Map.of("org/files/Files.class", "files"));
        publish(repo, "i18n", "1.2.0", Map.of("org/i18n/I18n.class", "i18n", "META-INF/services/x.Api", "org.i18n.Impl\n"));
        publish(repo, "server-api", "26.3", Map.of("org/server/Api.class", "provided by the server"));
        Path project = tmp.resolve("bedwars");
        Fs.write(project.resolve("project.jsonc"), "{ \"name\": \"bedwars\", \"version\": \"0.1.0\", \"java\": \"21\","
                + " \"repositories\": [" + dev.jpm.api.json.Jsonc.quote(repo.toUri().toString()) + "],"
                + " \"runtime\": { \"org.example:files\": \"4.0.0\", \"org.example:i18n\": \"1.2.0\" },"
                + " \"dev\": { \"org.example:server-api\": \"26.3\" } }");
        Fs.write(project.resolve("src/bw/BedWars.java"), "package bw; public class BedWars {}");
        Fs.write(project.resolve("src/assets/plugin.yml"), "name: BedWars\nmain: bw.BedWars\n");

        Project p = Project.load(project);
        MavenRepo mr = new MavenRepo(p.root.repositories);
        Graph g = Install.run(p, mr, new Install.Options(false, true, Set.of(), true));
        Builder b = new Builder(p, g, mr, dev.jpm.build.Env.Mode.PRODUCTION);
        Path jar = b.flatBundle(p.modules.get(0));

        assertEquals(project.resolve("dist/bedwars-0.1.0-bundle.jar"), jar);
        Set<String> names = new TreeSet<>(read(jar).keySet());
        assertTrue(names.containsAll(List.of("bw/BedWars.class", "plugin.yml", "org/files/Files.class", "org/i18n/I18n.class",
                "META-INF/services/x.Api")), names.toString());
        assertFalse(names.contains("org/server/Api.class"), "dev dependencies are provided by the platform");

        long before = Files.getLastModifiedTime(jar).toMillis();
        b.flatBundle(p.modules.get(0));
        assertEquals(before, Files.getLastModifiedTime(jar).toMillis());
    }
}
