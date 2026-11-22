package dev.jpm.build;

import dev.jpm.DepKey;
import dev.jpm.Project;
import dev.jpm.toolchain.Jdk;
import dev.jpm.resolve.Graph;
import dev.jpm.resolve.Resolver;
import dev.jpm.util.Exec;
import dev.jpm.util.JpmException;
import dev.jpm.log.Log;
import dev.jpm.version.Version;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs tests on the JUnit Platform. JUnit 5 is added automatically when no test framework is declared;
 * any other platform engine on the test classpath works too.
 */
public final class TestRunner {
    private TestRunner() {}

    /**
     * Which tests to run.
     *
     * @param filters      class names, {@code Class#method} or class name patterns with {@code *}; empty runs all tests
     * @param tags         JUnit tag expressions a test must match, all tests when empty
     * @param excludedTags JUnit tag expressions of tests to leave out
     */
    public record Selection(List<String> filters, List<String> tags, List<String> excludedTags) {}

    /** Runs a module's tests. Returns the exit code (0 when no tests exist). */
    public static int run(Builder b, Project.Module m, Selection selection) {
        if (!b.compileTests(m)) {
            Log.info(m.name() + ": no tests");
            return selection.filters().isEmpty() ? 0 : NO_MATCH;
        }
        List<String> filters = selection.filters().stream().filter(f -> selects(m, f)).toList();
        if (!selection.filters().isEmpty() && filters.isEmpty()) return NO_MATCH;
        List<Path> cp = b.testRuntimeClasspath(m);
        String platform = platformVersion(b.graph, m);
        if (platform == null) {
            throw new JpmException(m.name() + ": the test classpath has no JUnit Platform engine. Add a test framework such as "
                    + Resolver.JUNIT_JUPITER + " to \"test\".");
        }
        List<Path> launcher = b.resolveTool(List.of(new Resolver.Root("jpm test runner",
                DepKey.maven("org.junit.platform", "junit-platform-console"), platform)), null, dev.jpm.resolve.Graph.Walk.RUNTIME);
        List<Path> full = new ArrayList<>(launcher);
        full.addAll(cp);
        Jdk jdk = b.jdk(m.manifest);
        List<String> cmd = new ArrayList<>();
        cmd.add(jdk.java());
        if (m.manifest.dependencies.stream().anyMatch(d -> d.key().isGradle())) cmd.addAll(GRADLE_JVM_ARGS);
        cmd.addAll(m.manifest.testJvmArgs);
        cmd.addAll(extraJvmArgs(System.getenv("JPM_TEST_JVM_ARGS")));
        cmd.add("-cp");
        cmd.add(Compiler.join(Compiler.distinct(full)));
        cmd.add("org.junit.platform.console.ConsoleLauncher");
        if (Version.of(platform).compareTo(Version.of("1.10")) >= 0) cmd.add("execute");
        cmd.add("--disable-banner");
        cmd.add("--details=tree");
        if (filters.isEmpty()) {
            cmd.add("--scan-class-path=" + Builder.testClassesDir(m));
        } else {
            for (String f : filters) {
                if (f.contains("#")) cmd.add("--select-method=" + f);
                else if (f.contains("*")) {
                    cmd.add("--scan-class-path=" + Builder.testClassesDir(m));
                    cmd.add("--include-classname=" + f.replace(".", "\\.").replace("*", ".*"));
                } else cmd.add("--select-class=" + f);
            }
        }
        for (String t : selection.tags()) cmd.add("--include-tag=" + t);
        for (String t : selection.excludedTags()) cmd.add("--exclude-tag=" + t);
        Log.step("Testing", m.name());
        return Exec.inherit(m.dir, cmd, Env.forChild(b.project.dir, Env.Mode.DEVELOPMENT));
    }

    /** What {@link #run} returns when the module has none of the test classes the filters name. */
    public static final int NO_MATCH = -1;

    /**
     * Whether a filter can select tests of this module: a pattern always can; a class, or a method of one,
     * only when the module's compiled tests have that class.
     */
    static boolean selects(Project.Module m, String filter) {
        if (filter.contains("*")) return true;
        String cls = filter.contains("#") ? filter.substring(0, filter.indexOf('#')) : filter;
        return java.nio.file.Files.exists(Builder.testClassesDir(m).resolve(cls.replace('.', '/') + ".class"));
    }

    /** JVM options from {@code JPM_TEST_JVM_ARGS}, separated by spaces, for example a coverage agent. */
    static List<String> extraJvmArgs(String value) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(value.strip().split("\\s+"));
    }

    /** What Gradle opens in the JDK for its daemon and workers (JpmsConfiguration in Gradle's sources). */
    static final List<String> GRADLE_JVM_ARGS = List.of(
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
            "--add-opens=java.prefs/java.util.prefs=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
            "--add-opens=java.base/java.nio.charset=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
            "--add-opens=java.xml/javax.xml.namespace=ALL-UNNAMED",
            "--add-opens=java.base/java.time=ALL-UNNAMED");

    private static String platformVersion(Graph g, Project.Module m) {
        List<Graph.Edge> roots = new ArrayList<>(Resolver.rootEdges(m, dev.jpm.Manifest.Scope.TEST));
        roots.addAll(Resolver.rootEdges(m, dev.jpm.Manifest.Scope.RUNTIME));
        for (String id : g.closure(roots, dev.jpm.resolve.Graph.Walk.ALL)) {
            if (id.equals("org.junit.platform:junit-platform-engine")) return g.get(id).version;
        }
        return null;
    }
}
