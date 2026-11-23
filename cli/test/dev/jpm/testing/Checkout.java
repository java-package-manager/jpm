package dev.jpm.testing;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The jpm checkout the tests run in. Tests run in their module's folder, which is below it.
 */
public final class Checkout {
    private Checkout() {
    }

    /**
     * The checkout's root: the nearest folder at or above the working folder that holds {@code bootstrap.sh}.
     */
    public static Path root() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("bootstrap.sh"))) return p;
        }
        throw new IllegalStateException("not inside a jpm checkout: " + Path.of("").toAbsolutePath());
    }
}
