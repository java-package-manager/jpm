package dev.jpm.api.model;

import java.nio.file.Path;
import java.util.List;

/**
 * A module's folders.
 *
 * @param sources          source folders
 * @param resources        resource folders
 * @param tests            test source folders
 * @param testResources    test resource folders
 * @param generatedSources folders of generated sources, such as the defines class; they may not exist yet
 * @param exclude          folders an IDE should not index ({@code .cache}, {@code dist})
 */
public record Folders(List<Path> sources, List<Path> resources, List<Path> tests, List<Path> testResources,
                      List<Path> generatedSources, List<Path> exclude) {}
