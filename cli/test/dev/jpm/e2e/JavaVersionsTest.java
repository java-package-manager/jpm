package dev.jpm.e2e;

import dev.jpm.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds and runs a plain project and a module project for every supported target (docs/java-versions.md)
 * with every JDK at hand: the one running the tests, or the JDK homes in {@code JPM_TEST_JDKS} (separated
 * like PATH), as the CI matrix passes them.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the fixtures run through sh-free jpm start, but CI covers this on Linux")
class JavaVersionsTest {
    static final List<Integer> TARGETS = List.of(8, 11, 17, 21, 25, 27);

    @Test
    void everyTargetWithEveryJdk(@TempDir Path tmp) {
        List<String> failures = new ArrayList<>();
        int built = 0;
        for (Path home : jdks()) {
            int jdk = major(home);
            for (int target : TARGETS) {
                if (target > jdk) continue;
                built++;
                check(tmp.resolve("plain-" + jdk + "-" + target), home, jdk, target, "java-plain", "plain ok", failures);
                check(tmp.resolve("module-" + jdk + "-" + target), home, jdk, target, "java-module", "module demo.mod", failures);
            }
        }
        assertTrue(built > 0, "no JDK to test with");
        assertTrue(failures.isEmpty(), String.join("\n\n", failures));
    }

    private static void check(Path tmp, Path home, int jdk, int target, String fixture, String output, List<String> failures) {
        Session s = new Session(tmp).fixture(fixture).env("JAVA_HOME", home.toString());
        String manifest = Fs.read(s.project.resolve("project.jsonc"));
        Fs.write(s.project.resolve("project.jsonc"), manifest.replace("\"version\": \"1.0.0\"",
                "\"version\": \"1.0.0\", \"java\": \"" + jdk + "\", \"target\": \"" + target + "\""));
        String what = fixture + " on JDK " + jdk + " for target " + target;
        boolean module = fixture.equals("java-module");
        String build = s.jpm("build");
        if (module && target < 9) {
            if (!build.contains("modules need target 9 or newer")) failures.add(what + ": expected the error for modules on 8, got\n" + build);
            return;
        }
        Path jar = s.project.resolve("dist/" + (module ? "module-app" : "plain") + "-1.0.0.jar");
        if (!Files.exists(jar)) {
            failures.add(what + ": build failed\n" + build);
            return;
        }
        int classVersion = classVersion(jar, module ? "demo/mod/Main.class" : "plain/Main.class");
        if (classVersion != 44 + target) failures.add(what + ": class file version " + classVersion + ", expected " + (44 + target));
        String run = s.jpm("start");
        if (!run.contains(output)) failures.add(what + ": jpm start printed\n" + run);
    }

    static List<Path> jdks() {
        String env = System.getenv("JPM_TEST_JDKS");
        List<Path> out = new ArrayList<>();
        if (env == null || env.isBlank()) out.add(Path.of(System.getProperty("java.home")));
        else for (String h : env.split(java.io.File.pathSeparator)) if (!h.isBlank()) out.add(Path.of(h));
        return out;
    }

    private static final Pattern RELEASE = Pattern.compile("JAVA_VERSION=\"(?:1\\.)?(\\d+)");

    static int major(Path home) {
        Matcher m = RELEASE.matcher(Fs.read(home.resolve("release")));
        if (!m.find()) throw new IllegalStateException("no JAVA_VERSION in " + home.resolve("release"));
        return Integer.parseInt(m.group(1));
    }

    private static int classVersion(Path jar, String entry) {
        try (JarFile jf = new JarFile(jar.toFile()); InputStream in = jf.getInputStream(jf.getEntry(entry))) {
            DataInputStream d = new DataInputStream(in);
            d.readInt();
            d.readUnsignedShort();
            return d.readUnsignedShort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
