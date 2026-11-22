package dev.jpm.api.client;

import dev.jpm.api.model.Classpaths;
import dev.jpm.api.model.Define;
import dev.jpm.api.model.Dependency;
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

@SuppressWarnings("unchecked")
final class Decode {
    private Decode() {}

    static Project project(Map<String, Object> m) {
        return new Project(path(m, "dir"), str(m, "name"), str(m, "version"), bool(m, "workspace"), strings(m, "repositories"),
                map(m, "extensions"), list(m, "scripts", Decode::script), list(m, "modules", Decode::module), bool(m, "lockfileChanged"));
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
                path(m, "jar"), jdk, list(m, "dependencies", Decode::dependency), list(m, "packages", Decode::pkg),
                list(m, "defines", Decode::define), list(m, "scripts", Decode::script), map(m, "extensions"), split(m.get("modulePath")));
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

    static Script script(Map<String, Object> m) {
        Map<String, String> deps = new LinkedHashMap<>();
        map(m, "dependencies").forEach((k, v) -> deps.put(k, String.valueOf(v)));
        return new Script(str(m, "name"), str(m, "run"), str(m, "kind"), deps);
    }

    static Classpaths classpaths(Map<String, Object> m) {
        return new Classpaths(list(m, "compile", Decode::pkg), list(m, "processor", Decode::pkg), list(m, "runtime", Decode::pkg),
                list(m, "testCompile", Decode::pkg), list(m, "testRuntime", Decode::pkg), split(m.get("modulePath")));
    }

    static ModuleSplit split(Object o) {
        if (!(o instanceof Map<?, ?> raw)) return null;
        Map<String, Object> m = (Map<String, Object>) raw;
        return new ModuleSplit(str(m, "name"), paths(m, "modulePath"), paths(m, "classPath"), strings(m, "options"));
    }

    static Problem problem(Object o) {
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
