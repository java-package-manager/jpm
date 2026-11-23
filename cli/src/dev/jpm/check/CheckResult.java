package dev.jpm.check;

import java.util.List;

/**
 * What one check found.
 *
 * @param id       the check's id, such as {@code paths} or {@code format:format} for an extension's
 * @param title    what it checks, for the output
 * @param problems what it found; empty when it passed
 * @param skipped  why it did not run (an earlier check failed, nothing to check), or null when it ran
 */
public record CheckResult(String id, String title, List<Problem> problems, String skipped) {
    /**
     * Whether it found an error.
     */
    public boolean failed() {
        return problems.stream().anyMatch(p -> p.severity() == Problem.Severity.ERROR);
    }
}
