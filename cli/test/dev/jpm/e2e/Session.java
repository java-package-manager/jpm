package dev.jpm.e2e;

import dev.jpm.testing.TestRepo;
import dev.jpm.util.Fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One end-to-end scenario: a project in a temporary folder, a jpm home and a Maven repository of its own,
 * and the {@code jpm} binary run as a separate process. Every command and what it printed go into a
 * transcript, which {@link #verify(String)} compares with {@code fixtures/expected/<name>.txt}.
 *
 * <p>The binary is {@code $JPM_E2E_BINARY} (CI points it at the native image), otherwise jpm's classes on
 * a JVM with the options in {@code JPM_TEST_JVM_ARGS} (so a coverage agent sees the end-to-end runs too).
 * {@code JPM_UPDATE_EXPECTED=1} writes the transcripts instead of comparing them.
 */
final class Session {
    static final Path FIXTURES = dev.jpm.testing.Checkout.root().resolve("fixtures");

    final Path tmp, project, home;
    final TestRepo repo;
    private final Map<String, String> frameworkVersions;
    private final StringBuilder transcript = new StringBuilder();
    private final Map<String, String> extraEnv = new LinkedHashMap<>();

    Session(Path tmp) {
        this.tmp = tmp;
        this.project = tmp.resolve("project");
        this.home = tmp.resolve("home");
        this.repo = new TestRepo(tmp.resolve("repo"));
        this.frameworkVersions = repo.copyTestClasspath();
        try {
            Files.createDirectories(project);
            Files.createDirectories(home);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Sets an environment variable for every command run after this.
     */
    Session env(String name, String value) {
        extraEnv.put(name, value);
        return this;
    }

    /**
     * Leaves an environment variable out of every command run after this, including one {@link #env()} sets.
     */
    Session unsetEnv(String name) {
        extraEnv.put(name, null);
        return this;
    }

    /**
     * Copies {@code fixtures/<name>} into the project folder.
     */
    Session fixture(String name) {
        Path from = FIXTURES.resolve(name);
        try (Stream<Path> s = Files.walk(from)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                Path rel = from.relativize(f);
                if (rel.toString().startsWith(".cache") || rel.toString().contains("dist") || rel.endsWith("project.lock")) continue;
                if (Files.isRegularFile(f)) {
                    Path to = project.resolve(rel.toString());
                    Files.createDirectories(to.getParent());
                    Files.copy(f, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    /**
     * Runs {@code jpm args} in the project folder.
     */
    String jpm(String... args) {
        return jpmIn(".", args);
    }

    /**
     * Runs {@code jpm args} in a folder relative to the project folder.
     */
    String jpmIn(String dir, String... args) {
        List<String> cmd = new ArrayList<>(binary());
        cmd.addAll(List.of(args));
        return run(dir, "jpm " + String.join(" ", args), cmd);
    }

    /**
     * Runs {@code jpm args} and records the command and its exit code, but not its output.
     */
    String jpmUnrecorded(String... args) {
        int mark = transcript.length();
        String out = jpm(args);
        String exit = transcript.substring(mark).contains("[exit ") ? transcript.substring(transcript.lastIndexOf("[exit ")) : "\n";
        transcript.setLength(mark);
        transcript.append("$ jpm ").append(String.join(" ", args)).append("\n(output not recorded)\n");
        if (!exit.equals("\n")) transcript.append(exit);
        else transcript.append('\n');
        return out;
    }

    /**
     * Runs another program in a folder relative to the project folder, with the same environment as jpm.
     */
    String exec(String dir, String... cmd) {
        return run(dir, String.join(" ", cmd), List.of(cmd));
    }

    private String run(String dir, String shown, List<String> cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(project.resolve(dir).toFile()).redirectErrorStream(true);
        pb.environment().keySet().removeIf(k -> k.startsWith("JPM_") || k.equals("NO_COLOR") || k.equals("FORCE_COLOR"));
        pb.environment().putAll(env());
        String out;
        int code;
        try {
            Process p = pb.start();
            p.getOutputStream().close();
            out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            code = p.waitFor();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        transcript.append("$ ").append(dir.equals(".") ? "" : "(cd " + dir + ") ").append(normalize(shown)).append('\n');
        transcript.append(normalize(out));
        if (!out.isEmpty() && !out.endsWith("\n")) transcript.append('\n');
        if (code != 0) transcript.append("[exit ").append(code).append("]\n");
        transcript.append('\n');
        return out;
    }

    /**
     * Adds a file's contents to the transcript.
     */
    Session show(String path) {
        Path f = project.resolve(path);
        transcript.append("--- ").append(path).append('\n');
        transcript.append(Files.exists(f) ? normalize(Fs.read(f)) : "(missing)\n");
        if (Files.exists(f) && !Fs.read(f).endsWith("\n")) transcript.append('\n');
        transcript.append('\n');
        return this;
    }

    /**
     * Adds the files under a folder (relative to the project folder) to the transcript.
     */
    Session tree(String dir) {
        Path root = project.resolve(dir);
        transcript.append("--- files in ").append(dir).append('\n');
        if (Files.isDirectory(root)) {
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(Files::isRegularFile).map(f -> root.relativize(f).toString().replace('\\', '/'))
                        .filter(f -> !f.startsWith(".cache/") && !f.contains("/.cache/"))
                        .sorted().forEach(f -> transcript.append(normalize(f)).append('\n'));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        transcript.append('\n');
        return this;
    }

    /**
     * Compares the transcript with {@code fixtures/expected/<name>.txt}.
     */
    void verify(String name) {
        Path expected = FIXTURES.resolve("expected").resolve(name + ".txt");
        String actual = transcript.toString();
        if ("1".equals(System.getenv("JPM_UPDATE_EXPECTED")) || !Files.exists(expected)) {
            Fs.write(expected, actual);
            if (!"1".equals(System.getenv("JPM_UPDATE_EXPECTED"))) {
                throw new AssertionError("wrote " + expected + "; check it and run the test again");
            }
            return;
        }
        assertEquals(Fs.read(expected).replace("\r\n", "\n"), actual, "transcript differs from " + expected
                + " (JPM_UPDATE_EXPECTED=1 writes it, if the change is intended)");
    }

    Map<String, String> env() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JPM_HOME", home.toString());
        env.put("JPM_MAVEN_CENTRAL", repo.uri());
        env.put("JPM_NO_UPDATE_CHECK", "1");
        env.put("JPM_NO_INSTALL", "1");
        env.put("JPM_NO_MODIFY_PATH", "1");
        env.put("NO_COLOR", "1");
        env.put("JAVA_HOME", System.getProperty("java.home"));
        env.put("PATH", pathWithoutOuterJpm());
        env.putAll(extraEnv);
        env.values().removeIf(java.util.Objects::isNull);
        return env;
    }

    /**
     * PATH without the bin folder of the jpm that runs the tests, so extensions installed there with
     * {@code jpm add -g} are not found by the jpm under test.
     */
    static String pathWithoutOuterJpm() {
        Path outer = dev.jpm.testing.JpmHome.outerRepository().getParent().resolve("bin").toAbsolutePath().normalize();
        String path = System.getenv("PATH");
        if (path == null) return null;
        List<String> kept = new ArrayList<>();
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            if (!Path.of(dir).toAbsolutePath().normalize().equals(outer)) kept.add(dir);
        }
        return String.join(java.io.File.pathSeparator, kept);
    }

    static List<String> binary() {
        String bin = System.getenv("JPM_E2E_BINARY");
        if (bin != null && !bin.isBlank()) return List.of(Path.of(bin).toAbsolutePath().toString());
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1"));
        String extra = System.getenv("JPM_TEST_JVM_ARGS");
        if (extra != null && !extra.isBlank()) cmd.addAll(List.of(extra.strip().split("\\s+")));
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), "dev.jpm.cli.Main"));
        return cmd;
    }

    private static final Pattern JDK_ID = Pattern.compile("\\b[a-z_]+-\\d+(\\.\\d+)*(\\+\\d+)?(-[\\w.]+)?(?=, release)");
    private static final Pattern MILLIS = Pattern.compile("after \\d+ ms");
    private static final Pattern SHA256 = Pattern.compile("\"sha256\": \"[0-9a-f]{64}\"");
    private static final Pattern POM_SHA256 = Pattern.compile("\"pom\": \"[0-9a-f]{64}\"");
    private static final Pattern HASH_CONTENT = Pattern.compile("\"content\": \"[0-9a-f]{64}\"");
    private static final Pattern UUID_URN = Pattern.compile("urn:uuid:[0-9a-f-]{36}");
    private static final Pattern INPUTS = Pattern.compile("\"inputs\": \"[0-9a-f]+\"");
    private static final Pattern COMMIT = Pattern.compile("\\b[0-9a-f]{40}\\b|(?<=\\+|\\()[0-9a-f]{7}\\b");
    private static final Pattern SECONDS = Pattern.compile("in \\d+(\\.\\d+)?s\\b");

    private static final Pattern WINDOWS_PATH = Pattern.compile("(?<![\\w\\\\])((?:<[a-z-]+>|[A-Za-z]:|[\\w.@+-]+)(?:\\\\[\\w.@+$-]+)+\\\\?)");
    private static final Pattern EXE = Pattern.compile("\\b(java|javac|jpm|jar|native-image)\\.exe\\b");

    private static String windows(String s) {
        java.util.regex.Matcher m = WINDOWS_PATH.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(1).replace('\\', '/')));
        m.appendTail(sb);
        return EXE.matcher(sb.toString()).replaceAll("$1");
    }

    String normalize(String s) {
        s = s.replaceAll("\u001b\\[[0-9;]*m", "").replace("\r\n", "\n");
        for (Path p : List.of(project, repo.root(), home, tmp, Path.of(System.getProperty("java.home")))) {
            String label = p.equals(project) ? "<project>" : p.equals(home) ? "<home>" : p.equals(tmp) ? "<tmp>"
                    : p.equals(repo.root()) ? "<repo>" : "<java-home>";
            try {
                Path real = p.toRealPath();
                s = s.replace(real.toUri().toString(), label + "/").replace(real.toString(), label);
            } catch (IOException ignored) {
            }
            s = s.replace(p.toUri().toString(), label + "/").replace(p.toString(), label);
        }
        if (java.io.File.separatorChar == '\\') s = windows(s).replaceAll("(?m)^stty: .*\n", "");
        s = JDK_ID.matcher(s).replaceAll("<jdk>");
        s = s.replace(dev.jpm.BuildConfig.VERSION, "<jpm-version>");
        s = COMMIT.matcher(s).replaceAll("<commit>");
        s = MILLIS.matcher(s).replaceAll("after <n> ms");
        s = SHA256.matcher(s).replaceAll("\"sha256\": \"<sha256>\"");
        s = POM_SHA256.matcher(s).replaceAll("\"pom\": \"<sha256>\"");
        s = INPUTS.matcher(s).replaceAll("\"inputs\": \"<inputs>\"");
        s = HASH_CONTENT.matcher(s).replaceAll("\"content\": \"<sha256>\"");
        s = UUID_URN.matcher(s).replaceAll("urn:uuid:<uuid>");
        s = SECONDS.matcher(s).replaceAll("in <n>s");
        for (Map.Entry<String, String> e : frameworkVersions.entrySet()) {
            String artifact = e.getKey().substring(e.getKey().indexOf(':') + 1);
            s = s.replaceAll("(" + Pattern.quote(artifact) + "\\b[^\\n0-9]{0,20}?)" + Pattern.quote(e.getValue()) + "\\b",
                    "$1<test-framework>");
        }
        return s;
    }
}
