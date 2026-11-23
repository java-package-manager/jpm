package dev.jpm.testing;

import dev.jpm.util.Fs;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gives every test its own empty jpm home (the {@code jpm.home} system property), so no test reads or writes
 * {@code ~/.jpm}, and restores the previous value afterwards. It is registered for all tests through
 * {@code test/assets/META-INF/services}; a test may still point {@code jpm.home} somewhere else.
 */
public final class JpmHome implements BeforeEachCallback, AfterEachCallback {
    /**
     * The home of the jpm that runs the tests, read before any test changes {@code jpm.home}.
     */
    private static final Path OUTER = Fs.home();

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(JpmHome.class);

    /**
     * The Maven cache of the jpm running the tests: it holds the test frameworks jpm's own tests use.
     */
    public static Path outerRepository() {
        return OUTER.resolve("repository");
    }

    @Override
    public void beforeEach(ExtensionContext context) throws IOException {
        Path dir = Files.createTempDirectory("jpm-home");
        context.getStore(NS).put("previous", System.getProperty("jpm.home", ""));
        context.getStore(NS).put("dir", dir);
        System.setProperty("jpm.home", dir.toString());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        String previous = context.getStore(NS).get("previous", String.class);
        if (previous == null || previous.isEmpty()) System.clearProperty("jpm.home");
        else System.setProperty("jpm.home", previous);
        Path dir = context.getStore(NS).get("dir", Path.class);
        if (dir != null) Fs.deleteTree(dir);
    }
}
