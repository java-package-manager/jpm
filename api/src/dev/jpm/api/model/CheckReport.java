package dev.jpm.api.model;

import java.util.List;

/**
 * What {@code check/run} found: every problem of every check that ran.
 *
 * @param problems the problems, each naming the check that found it ({@link Problem#check()})
 * @param ran      the ids of the checks that ran; a check skipped because an earlier one failed is not among them
 */
public record CheckReport(List<Problem> problems, List<String> ran) {
    /**
     * Whether any problem is an error, which fails {@code jpm check}.
     *
     * @return true when one is
     */
    public boolean failed() {
        return problems.stream().anyMatch(p -> "error".equals(p.severity()));
    }
}
