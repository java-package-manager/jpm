package dev.jpm.api.model;

import java.util.List;

/**
 * An extension, as its manifest describes it ({@code "extension"} in its project.jsonc): what {@code extensions/list}
 * returns, without running it.
 *
 * @param name        the command it adds ({@code publish} for {@code jpm publish})
 * @param key         its dependency key, such as {@code github:java-package-manager/jpm-publish}
 * @param version     the installed or locked version, or null when it is not installed yet
 * @param source      {@code project} (declared in the root project.jsonc) or {@code global} ({@code jpm add -g})
 * @param description what it does, or null
 * @param commands    its commands; one named after it when its manifest lists none
 * @param checks      the checks it adds to {@code jpm check}
 */
public record Extension(String name, String key, String version, String source, String description, List<Command> commands,
                        List<Check> checks) {
    /**
     * A command an extension adds.
     *
     * @param name        what follows {@code jpm}: {@code publish}, or {@code format check} for a subcommand
     * @param description what it does, or null
     * @param options     its options
     */
    public record Command(String name, String description, List<Option> options) {}

    /**
     * An option of a command.
     *
     * @param name        such as {@code --dry-run}
     * @param argument    the name of its value, or null for a flag
     * @param description what it does, or null
     */
    public record Option(String name, String argument, String description) {}

    /**
     * A check the extension adds to {@code jpm check}.
     *
     * @param id          its id, such as {@code pom}
     * @param description what it checks, or null
     */
    public record Check(String id, String description) {}
}
