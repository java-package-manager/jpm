package dev.jpm.api.client;

import dev.jpm.api.model.Classpaths;
import dev.jpm.api.model.Define;
import dev.jpm.api.model.Dependency;
import dev.jpm.api.model.Extension;
import dev.jpm.api.model.Folders;
import dev.jpm.api.model.Jdk;
import dev.jpm.api.model.Module;
import dev.jpm.api.model.ModuleSplit;
import dev.jpm.api.model.Package;
import dev.jpm.api.model.Problem;
import dev.jpm.api.model.Project;
import dev.jpm.api.model.Script;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Protocol results, as JSON values ({@link dev.jpm.api.json.Jsonc#parse(String)}), turned into the model records:
 * what {@link JpmClient} does with jpm's answers, for programs that have the JSON from elsewhere.
 */
@SuppressWarnings("unchecked")
public final class Results {
    private Results() {
    }

    /**
     * A {@code project/load} result.
     *
     * @param m the result object
     * @return the project
     */
    public static Project project(Map<String, Object> m) {
        return new Project(path(m, "dir"), str(m, "name"), str(m, "version"), bool(m, "workspace"), strings(m, "repositories"),
                map(m, "extensions"), list(m, "scripts", Results::script), list(m, "modules", Results::module), bool(m, "lockfileChanged"));
    }

    static Module module(Map<String, Object> m) {
        Map<String, String> main = new LinkedHashMap<>();
        map(m, "main").forEach((k, v) -> main.put(k, String.valueOf(v)));
        Map<String, Object> f = map(m, "folders"), j = map(m, "jdk");
        Folders folders = new Folders(paths(f, "sources"), paths(f, "resources"), paths(f, "tests"), paths(f, "testResources"),
                paths(f, "generatedSources"), paths(f, "exclude"));
        Jdk jdk = new Jdk(str(j, "spec"), path(j, "home"), integer(j, "release"), str(j, "error"));
        return new Module(str(m, "name"), str(m, "group"), str(m, "version"), str(m, "description"), str(m, "license"), str(m, "url"),
                path(m, "dir"), str(m, "relativeDir"), str(m, "visibility"), str(m, "package"), str(m, "layout"), main, folders,
                path(m, "jar"), jdk, list(m, "dependencies", Results::dependency), list(m, "packages", Results::pkg),
                list(m, "defines", Results::define), list(m, "scripts", Results::script), map(m, "extensions"), split(m.get("modulePath")));
    }

    static Dependency dependency(Map<String, Object> m) {
        return new Dependency(str(m, "key"), str(m, "kind"), str(m, "scope"), str(m, "spec"), str(m, "resolved"), str(m, "commit"),
                strings(m, "exclude"), bool(m, "runtimeOnly"), bool(m, "api"), bool(m, "tests"), str(m, "group"), str(m, "artifact"),
                str(m, "classifier"), str(m, "module"), str(m, "host"));
    }

    static Package pkg(Map<String, Object> m) {
        return new Package(str(m, "id"), str(m, "kind"), str(m, "version"), str(m, "commit"), str(m, "scope"), str(m, "module"),
                paths(m, "jars"), path(m, "classes"), path(m, "sources"), strings(m, "dependencies"));
    }

    static Define define(Map<String, Object> m) {
        Object v = m.get("value");
        if (v instanceof java.math.BigDecimal bd) v = bd.intValueExact();
        return new Define(str(m, "name"), str(m, "type"), v, str(m, "class"));
    }

    /**
     * One entry of an {@code extensions/list} result.
     *
     * @param m the extension object
     * @return the extension
     */
    public static Extension extension(Map<String, Object> m) {
        List<Extension.Command> commands = list(m, "commands", c -> new Extension.Command(str(c, "name"), str(c, "description"),
                list(c, "options", o -> new Extension.Option(str(o, "name"), str(o, "argument"), str(o, "description")))));
        List<Extension.Check> checks = list(m, "checks", c -> new Extension.Check(str(c, "id"), str(c, "description")));
        return new Extension(str(m, "name"), str(m, "key"), str(m, "version"), str(m, "source"), str(m, "description"), commands, checks);
    }

    static Script script(Map<String, Object> m) {
        Map<String, String> deps = new LinkedHashMap<>();
        map(m, "dependencies").forEach((k, v) -> deps.put(k, String.valueOf(v)));
        return new Script(str(m, "name"), str(m, "run"), str(m, "kind"), deps);
    }

    /**
     * One module's entry of a {@code project/classpaths} result.
     *
     * @param m the module's classpaths object
     * @return its classpaths
     */
    public static Classpaths classpaths(Map<String, Object> m) {
        return new Classpaths(list(m, "compile", Results::pkg), list(m, "processor", Results::pkg), list(m, "runtime", Results::pkg),
                list(m, "testCompile", Results::pkg), list(m, "testRuntime", Results::pkg), split(m.get("modulePath")));
    }

    static ModuleSplit split(Object o) {
        if (!(o instanceof Map<?, ?> raw)) return null;
        Map<String, Object> m = (Map<String, Object>) raw;
        return new ModuleSplit(str(m, "name"), paths(m, "modulePath"), paths(m, "classPath"), strings(m, "options"));
    }

    /**
     * A problem, such as the {@code data} of an error.
     *
     * @param o the problem object
     * @return the problem, or null when {@code o} is not an object
     */
    public static Problem problem(Object o) {
        if (!(o instanceof Map<?, ?> raw)) return null;
        Map<String, Object> m = (Map<String, Object>) raw;
        Map<String, Object> loc = map(m, "location");
        return new Problem(str(m, "severity"), str(m, "message"), path(loc, "file"), integer(loc, "line"), integer(loc, "column"),
                str(m, "hint"), str(m, "check"));
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static boolean bool(Map<String, Object> m, String k) {
        return Boolean.TRUE.equals(m.get(k));
    }

    private static Integer integer(Map<String, Object> m, String k) {
        return m.get(k) instanceof Number n ? n.intValue() : null;
    }

    private static Path path(Map<String, Object> m, String k) {
        String s = str(m, k);
        return s == null ? null : Path.of(s);
    }

    private static Map<String, Object> map(Map<String, Object> m, String k) {
        return m.get(k) instanceof Map<?, ?> v ? (Map<String, Object>) v : Map.of();
    }

    private static List<String> strings(Map<String, Object> m, String k) {
        List<String> out = new ArrayList<>();
        if (m.get(k) instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
        return List.copyOf(out);
    }

    private static List<Path> paths(Map<String, Object> m, String k) {
        return strings(m, k).stream().map(Path::of).toList();
    }

    private static <T> List<T> list(Map<String, Object> m, String k, java.util.function.Function<Map<String, Object>, T> f) {
        List<T> out = new ArrayList<>();
        if (m.get(k) instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> e) out.add(f.apply((Map<String, Object>) e));
        return List.copyOf(out);
    }
}
