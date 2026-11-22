import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Counts what jpm wants less of and compares the counts with {@code checks/ratchets.properties}. A count
 * may never rise above its baseline. When one falls, the baseline must fall with it in the same change:
 * {@code java checks/Ratchets.java --update} lowers it (and never raises it).
 *
 * <p>Usage: {@code java checks/Ratchets.java [--update] [--list <ratchet>]}
 */
public final class Ratchets {
    private static final Path BASELINE = Path.of("checks/ratchets.properties");

    /** A ratchet: what it counts, over which files. */
    record Ratchet(String name, String description, List<String> roots, Function<Source, List<String>> find) {}

    /** A Java file split into code (comments and literals blanked) and its comments. */
    record Source(Path path, String text, String code, List<Comment> comments) {}

    record Comment(int line, int lines, boolean javadoc, String text) {}

    static final List<Ratchet> RATCHETS = List.of(
            new Ratchet("comments", "// and /* */ comments (Javadoc is not counted)", List.of("src", "test"),
                    s -> s.comments().stream().filter(c -> !c.javadoc()).map(c -> s.path() + ":" + c.line()).toList()),
            new Ratchet("comment-lines", "lines taken by // and /* */ comments", List.of("src", "test"),
                    s -> s.comments().stream().filter(c -> !c.javadoc())
                            .flatMap(c -> java.util.stream.IntStream.range(0, c.lines()).mapToObj(i -> s.path() + ":" + (c.line() + i))).toList()),
            new Ratchet("system-out", "System.out and System.err outside the logger", List.of("src"),
                    s -> isLogger(s.path()) ? List.of() : matches(s, Pattern.compile("\\bSystem\\s*\\.\\s*(out|err)\\b"))),
            new Ratchet("live-tests", "tests that need the network (@Tag(\"live\"))", List.of("test"),
                    s -> matches(s.path(), s.text(), Pattern.compile("@Tag\\(\"live\"\\)"))),
            new Ratchet("missing-api-javadoc", "public types and members in dev.jpm.api without Javadoc", List.of("src/dev/jpm/api", "api/src"),
                    Ratchets::missingJavadoc));

    private static boolean isLogger(Path p) {
        String s = p.toString().replace('\\', '/');
        return s.contains("/dev/jpm/log/");
    }

    private static List<String> matches(Source s, Pattern p) {
        return matches(s.path(), s.code(), p);
    }

    private static List<String> matches(Path path, String text, Pattern p) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) out.add(path + ":" + line(text, m.start()));
        return out;
    }

    private static final Pattern PUBLIC_DECL = Pattern.compile("(?m)^[ \\t]*((?:@\\w+(?:\\([^)]*\\))?\\s+)*)public\\s+[^;={]*?[({]");

    private static List<String> missingJavadoc(Source s) {
        if (s.path().getFileName().toString().equals("module-info.java") || s.path().getFileName().toString().equals("package-info.java")) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher m = PUBLIC_DECL.matcher(s.code());
        while (m.find()) {
            int line = line(s.code(), m.start());
            boolean documented = s.comments().stream().anyMatch(c -> c.javadoc() && c.line() + c.lines() >= line - annotationLines(m.group(1)) - 1
                    && c.line() + c.lines() <= line);
            if (!documented) out.add(s.path() + ":" + line);
        }
        return out;
    }

    private static int annotationLines(String annotations) {
        return annotations == null ? 0 : (int) annotations.chars().filter(c -> c == '\n').count();
    }

    static int line(String text, int offset) {
        int n = 1;
        for (int i = 0; i < offset; i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    /** Splits Java source into code and comments, minding strings, characters and text blocks. */
    static Source parse(Path path) throws IOException {
        String t = Files.readString(path, StandardCharsets.UTF_8);
        StringBuilder code = new StringBuilder(t.length());
        List<Comment> comments = new ArrayList<>();
        int i = 0, n = t.length();
        while (i < n) {
            char c = t.charAt(i);
            if (t.startsWith("\"\"\"", i)) {
                int end = t.indexOf("\"\"\"", i + 3);
                while (end > 0 && t.charAt(end - 1) == '\\') end = t.indexOf("\"\"\"", end + 1);
                end = end < 0 ? n : end + 3;
                blank(code, t, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && t.charAt(j) != c && t.charAt(j) != '\n') j += t.charAt(j) == '\\' ? 2 : 1;
                int end = Math.min(n, j + 1);
                blank(code, t, i, end);
                i = end;
            } else if (t.startsWith("//", i)) {
                int end = t.indexOf('\n', i);
                end = end < 0 ? n : end;
                comments.add(new Comment(line(t, i), 1, false, t.substring(i, end)));
                blank(code, t, i, end);
                i = end;
            } else if (t.startsWith("/*", i)) {
                int end = t.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                String text = t.substring(i, end);
                boolean javadoc = text.startsWith("/**") && !text.equals("/**/");
                comments.add(new Comment(line(t, i), (int) text.chars().filter(ch -> ch == '\n').count() + 1, javadoc, text));
                blank(code, t, i, end);
                i = end;
            } else {
                code.append(c);
                i++;
            }
        }
        return new Source(path, t, code.toString(), comments);
    }

    private static void blank(StringBuilder code, String t, int from, int to) {
        for (int k = from; k < to; k++) code.append(t.charAt(k) == '\n' ? '\n' : ' ');
    }

    static List<Path> javaFiles(List<String> roots) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String r : roots) {
            Path root = Path.of(r);
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p)).sorted().forEach(out::add);
            }
        }
        return out;
    }

    static Map<String, List<String>> count() throws IOException {
        Map<String, List<String>> found = new LinkedHashMap<>();
        for (Ratchet r : RATCHETS) {
            List<String> hits = new ArrayList<>();
            for (Path f : javaFiles(r.roots())) hits.addAll(r.find().apply(parse(f)));
            found.put(r.name(), hits);
        }
        return found;
    }

    public static void main(String[] args) throws IOException {
        boolean update = List.of(args).contains("--update");
        int list = List.of(args).indexOf("--list");
        Map<String, List<String>> found = count();
        if (list >= 0) {
            String name = args.length > list + 1 ? args[list + 1] : "";
            if (!found.containsKey(name)) {
                System.err.println("Unknown ratchet \"" + name + "\". Ratchets: " + String.join(", ", found.keySet()));
                System.exit(2);
            }
            found.get(name).forEach(System.out::println);
            return;
        }
        Properties base = new Properties();
        if (Files.exists(BASELINE)) {
            try (var in = Files.newBufferedReader(BASELINE)) {
                base.load(in);
            }
        }
        Map<String, Integer> next = new TreeMap<>();
        boolean failed = false;
        for (Ratchet r : RATCHETS) {
            int now = found.get(r.name()).size();
            String b = base.getProperty(r.name());
            int was = b == null ? now : Integer.parseInt(b.strip());
            next.put(r.name(), Math.min(now, was));
            String status;
            if (now > was) {
                status = "FAIL  above the baseline " + was + "; `java checks/Ratchets.java --list " + r.name() + "` lists them";
                failed = true;
            } else if (now < was && !update) {
                status = "FAIL  below the baseline " + was + "; lower it with `java checks/Ratchets.java --update`";
                failed = true;
            } else if (b == null && !update) {
                status = "FAIL  no baseline; add it with `java checks/Ratchets.java --update`";
                failed = true;
            } else {
                status = now < was ? "lowered from " + was : "ok";
            }
            System.out.printf("%-20s %6d  %s  (%s)%n", r.name(), now, status, r.description());
        }
        if (update) {
            StringBuilder out = new StringBuilder("# Baselines for checks/Ratchets.java. They may only go down.\n");
            next.forEach((k, v) -> out.append(k).append('=').append(v).append('\n'));
            Files.writeString(BASELINE, out.toString());
            System.out.println("Wrote " + BASELINE);
            return;
        }
        if (failed) System.exit(1);
    }
}
