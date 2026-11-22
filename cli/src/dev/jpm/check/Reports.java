package dev.jpm.check;

import dev.jpm.api.json.Jsonc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Machine-readable reports of {@code jpm check}: SARIF, which GitHub code scanning shows as annotations, and JUnit XML. */
public final class Reports {
    private Reports() {}

    /** A SARIF 2.1.0 log: one rule per check, one result per problem, paths relative to {@code root}. */
    public static String sarif(List<CheckResult> results, Path root, String jpmVersion) {
        List<Object> rules = new ArrayList<>();
        List<Object> found = new ArrayList<>();
        for (CheckResult r : results) {
            rules.add(Map.of("id", r.id(), "shortDescription", Map.of("text", r.title())));
            for (Problem p : r.problems()) {
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("ruleId", r.id());
                res.put("level", p.severity() == Problem.Severity.ERROR ? "error" : "warning");
                res.put("message", Map.of("text", p.message() + (p.hint() == null ? "" : " (" + p.hint() + ")")));
                if (p.file() != null) {
                    Map<String, Object> physical = new LinkedHashMap<>();
                    physical.put("artifactLocation", Map.of("uri", relative(root, p.file())));
                    if (p.line() != null) physical.put("region", Map.of("startLine", p.line()));
                    res.put("locations", List.of(Map.of("physicalLocation", physical)));
                }
                found.add(res);
            }
        }
        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", "jpm check");
        driver.put("version", jpmVersion);
        driver.put("informationUri", "https://github.com/java-package-manager/jpm");
        driver.put("rules", rules);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", Map.of("driver", driver));
        run.put("results", found);
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
        log.put("version", "2.1.0");
        log.put("runs", List.of(run));
        return Jsonc.write(log) + "\n";
    }

    /** JUnit XML: one test case per check, failing with its errors (and its warnings as system-out). */
    public static String junit(List<CheckResult> results, Path root) {
        int failures = 0, skipped = 0;
        StringBuilder cases = new StringBuilder();
        for (CheckResult r : results) {
            cases.append("    <testcase classname=\"jpm check\" name=\"").append(xml(r.id())).append("\"");
            List<Problem> errors = r.problems().stream().filter(p -> p.severity() == Problem.Severity.ERROR).toList();
            List<Problem> warnings = r.problems().stream().filter(p -> p.severity() == Problem.Severity.WARNING).toList();
            if (r.skipped() == null && errors.isEmpty() && warnings.isEmpty()) {
                cases.append("/>\n");
                continue;
            }
            cases.append(">\n");
            if (r.skipped() != null) {
                skipped++;
                cases.append("      <skipped message=\"").append(xml(r.skipped())).append("\"/>\n");
            }
            if (!errors.isEmpty()) {
                failures++;
                cases.append("      <failure message=\"").append(xml(errors.size() + (errors.size() == 1 ? " error" : " errors"))).append("\">")
                        .append(xml(lines(errors, root))).append("</failure>\n");
            }
            if (!warnings.isEmpty()) cases.append("      <system-out>").append(xml(lines(warnings, root))).append("</system-out>\n");
            cases.append("    </testcase>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites>\n  <testsuite name=\"jpm check\" tests=\"" + results.size()
                + "\" failures=\"" + failures + "\" errors=\"0\" skipped=\"" + skipped + "\">\n" + cases + "  </testsuite>\n</testsuites>\n";
    }

    /** One line per problem: {@code file:line: severity: message}, as compilers print them. */
    public static String lines(List<Problem> problems, Path root) {
        StringBuilder sb = new StringBuilder();
        for (Problem p : problems) {
            if (p.file() != null) sb.append(relative(root, p.file())).append(p.line() == null ? "" : ":" + p.line()).append(": ");
            sb.append(p.severity() == Problem.Severity.ERROR ? "error" : "warning").append(": ").append(p.message()).append('\n');
        }
        return sb.toString();
    }

    private static String relative(Path root, Path file) {
        Path f = file.toAbsolutePath().normalize();
        return (root != null && f.startsWith(root) ? root.relativize(f) : f).toString().replace('\\', '/');
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
