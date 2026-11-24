package dev.jpm.resolve;

import java.util.List;
import java.util.Optional;

/**
 * The registry of known extensions: the ones jpm's authors publish, by the command they add. {@code jpm add <name>}
 * takes a name from it for the extension's dependency, and an unknown {@code jpm <name>} names the dependency that
 * provides it. docs/extensions.md lists them (generated, see {@code DocsTest}).
 */
public final class KnownExtensions {
    private KnownExtensions() {
    }

    /**
     * One known extension.
     *
     * @param name        the command it adds, as in {@code jpm <name>}
     * @param dependency  what {@code jpm add} declares, such as {@code github:java-package-manager/jpm-publish}
     * @param description what it does
     */
    public record Known(String name, String dependency, String description) {}

    /**
     * Every known extension, by name.
     */
    public static final List<Known> ALL = List.of(
            new Known("changeset", "github:java-package-manager/jpm-changeset",
                    "Record changes as changeset files, then bump versions, write changelogs and publish"),
            new Known("format", "github:java-package-manager/jpm-format",
                    "Format and lint Java code: an IntelliJ-style formatter, IntelliJ's inspections and custom rules"),
            new Known("publish", "github:java-package-manager/jpm-publish",
                    "Publish jpm modules to Maven repositories and Maven Central"));

    /**
     * The known extension that adds {@code jpm <name>}.
     */
    public static Optional<Known> named(String name) {
        return ALL.stream().filter(k -> k.name().equals(name)).findFirst();
    }

    /**
     * What {@code jpm add} declares for {@code arg}: a known extension's dependency for its name (with a version
     * after {@code @} or a branch after {@code #} kept), anything else unchanged.
     */
    public static String expand(String arg) {
        int end = 0;
        while (end < arg.length() && arg.charAt(end) != '@' && arg.charAt(end) != '#') end++;
        String rest = arg.substring(end);
        return named(arg.substring(0, end)).map(k -> k.dependency() + rest).orElse(arg);
    }
}
