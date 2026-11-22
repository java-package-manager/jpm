package dev.jpm.api.client;

/**
 * Options for {@link JpmClient#load}.
 *
 * @param frozen  fail instead of writing {@code project.lock} when it is out of date
 * @param sources download the sources jars of Maven dependencies
 */
public record LoadOptions(boolean frozen, boolean sources) {
    /**
     * Loading as {@code jpm install} does, without downloading sources.
     *
     * @return the options
     */
    public static LoadOptions defaults() {
        return new LoadOptions(false, false);
    }

    /**
     * Loading with the sources jars, as an IDE wants it.
     *
     * @return the options
     */
    public static LoadOptions withSources() {
        return new LoadOptions(false, true);
    }
}
