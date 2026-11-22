package dev.jpm.impl;

import dev.jpm.DepKey;
import dev.jpm.Manifest;

import dev.jpm.api.json.Jsonc;
import dev.jpm.util.JpmException;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class ManifestReader {
    private final Path file;
    private final String source;
    private final Jsonc.Node root;

    public ManifestReader(Path file, String source, Jsonc.Node root) {
        this.file = file;
        this.source = source;
        this.root = root;
    }

    public int line(int offset) {
        return Jsonc.lineOf(source, offset);
    }

    public JpmException err(Jsonc.Node n, String msg) {
        return JpmException.at(file, n == null ? 1 : line(n.start), msg);
    }

    public Manifest.Kotlin parseKotlin() {
        Jsonc.Node n = root.get("kotlin");
        if (n == null) return null;
        if (n.isString()) return new Manifest.Kotlin(checkKotlinVersion(n, (String) n.value), List.of(), List.of());
        if (!n.isObject()) throw err(n, "kotlin must be a Kotlin version or {\"version\": ..., \"plugins\": [...], \"args\": [...]}");
        for (String k : n.members.keySet()) {
            if (!Set.of("version", "plugins", "args").contains(k)) throw err(n.get(k), "unknown kotlin option \"" + k + "\"");
        }
        Jsonc.Node v = n.get("version");
        if (v == null || !v.isString()) throw err(n, "kotlin needs a \"version\", such as \"2.2.20\"");
        List<String> plugins = strings(n.get("plugins"), "kotlin.plugins");
        for (String p : plugins) {
            boolean coords = p.matches("[^:\\s]+:[^:\\s]+(:[^:\\s]+)?");
            if (!coords && !Manifest.Kotlin.BUILT_IN.contains(p)) {
                throw err(n.get("plugins"), "kotlin.plugins: \"" + p + "\" is neither group:artifact[:version] nor one of Kotlin's own: "
                        + String.join(", ", new java.util.TreeSet<>(Manifest.Kotlin.BUILT_IN)));
            }
        }
        return new Manifest.Kotlin(checkKotlinVersion(v, (String) v.value), plugins, strings(n.get("args"), "kotlin.args"));
    }

    String checkKotlinVersion(Jsonc.Node n, String v) {
        if (!v.matches("\\d+\\.\\d+\\.\\d+([-.][0-9A-Za-z.-]+)?")) throw err(n, "kotlin version must be exact, such as \"2.2.20\"");
        return v;
    }

    List<String> strings(Jsonc.Node n, String what) {
        List<String> out = new ArrayList<>();
        if (n == null) return out;
        if (!n.isArray()) throw err(n, what + " must be an array of strings");
        for (Jsonc.Node i : n.items) {
            if (!i.isString()) throw err(i, what + " must be an array of strings");
            out.add((String) i.value);
        }
        return List.copyOf(out);
    }

    public Manifest.Dependency dependency(DepKey key, Jsonc.Member e, Manifest.Scope s, Set<String> exclude) {
        int ln = line(e.keyStart);
        Jsonc.Node v = e.value;
        Set<String> moduleExcludes = exclude == null || s == Manifest.Scope.TEST ? Set.of() : exclude;
        if (v.isString()) return new Manifest.Dependency(key, (String) v.value, s, ln, moduleExcludes, false);
        if (!v.isObject()) {
            throw err(v, "dependency " + e.key + " must be a version string or {\"version\": ..., \"exclude\": [...], \"runtimeOnly\": true}");
        }
        for (String k : v.members.keySet()) {
            if (!Set.of("version", "exclude", "runtimeOnly", "api", "tests").contains(k)) throw err(v.get(k), "unknown dependency option \"" + k + "\"");
        }
        Jsonc.Node ver = v.get("version");
        if (ver == null || !ver.isString()) throw err(v, "dependency " + e.key + " needs a \"version\" string");
        Set<String> excl = new java.util.TreeSet<>();
        excl.addAll(moduleExcludes);
        Jsonc.Node ex = v.get("exclude");
        if (ex != null) excl.addAll(exclusions(ex));
        Jsonc.Node ro = v.get("runtimeOnly");
        boolean runtimeOnly = ro != null && Boolean.TRUE.equals(ro.value);
        if (ro != null && !(ro.value instanceof Boolean)) throw err(ro, "runtimeOnly must be true or false");
        if (runtimeOnly && s != Manifest.Scope.RUNTIME) throw err(ro, "runtimeOnly applies to \"runtime\" dependencies only");
        Jsonc.Node apiNode = v.get("api");
        boolean api = apiNode != null && Boolean.TRUE.equals(apiNode.value);
        if (apiNode != null && !(apiNode.value instanceof Boolean)) throw err(apiNode, "api must be true or false");
        if (api && s != Manifest.Scope.DEV) {
            throw err(apiNode, "api applies to \"dev\" dependencies only" + (s == Manifest.Scope.RUNTIME
                    ? ": runtime dependencies are always passed on to consumers" : ""));
        }
        Jsonc.Node testsNode = v.get("tests");
        boolean tests = testsNode != null && Boolean.TRUE.equals(testsNode.value);
        if (testsNode != null && !(testsNode.value instanceof Boolean)) throw err(testsNode, "tests must be true or false");
        if (tests && (s != Manifest.Scope.TEST || !key.isWorkspace())) {
            throw err(testsNode, "tests applies to \"test\" dependencies on workspace modules (\"workspace:<module>\") only");
        }
        return new Manifest.Dependency(key, (String) ver.value, s, ln, excl, runtimeOnly, api, tests);
    }

    public Set<String> exclusions(Jsonc.Node ex) {
        Set<String> excl = new java.util.TreeSet<>();
        if (!ex.isArray()) throw err(ex, "exclude must be an array of \"group:artifact\" (artifact may be *)");
        for (Jsonc.Node i : ex.items) {
            if (!i.isString() || !((String) i.value).matches("[^:\\s]+(:[^:\\s]+)?")) {
                throw err(i, "exclude entries look like \"group:artifact\", \"group:*\" or \"group\"");
            }
            excl.add((String) i.value);
        }
        return excl;
    }

    public Manifest.Define parseDefine(Jsonc.Member e) {
        Jsonc.Node v = e.value;
        int ln = line(e.keyStart);
        if (!v.isObject()) {
            Manifest.DefineType t = v.value instanceof Boolean ? Manifest.DefineType.BOOLEAN
                    : v.value instanceof BigDecimal ? Manifest.DefineType.INT : Manifest.DefineType.STRING;
            if (v.value == null) throw err(v, "define " + e.key + " needs a value or {\"type\": ...}");
            return new Manifest.Define(e.key, t, v.value, null, null, ln);
        }
        Jsonc.Node tn = v.get("type");
        if (tn == null || !tn.isString()) throw err(v, "define " + e.key + " needs a \"type\" (string, int or boolean)");
        Manifest.DefineType type = switch ((String) tn.value) {
            case "string" -> Manifest.DefineType.STRING;
            case "int" -> Manifest.DefineType.INT;
            case "boolean" -> Manifest.DefineType.BOOLEAN;
            default -> throw err(tn, "define type must be string, int or boolean");
        };
        for (String k : v.members.keySet()) {
            if (!Set.of("type", "value", "env", "default").contains(k)) throw err(v.get(k), "unknown define field \"" + k + "\"");
        }
        Jsonc.Node env = v.get("env");
        if (env != null && !env.isString()) throw err(env, "env must be a variable name");
        return new Manifest.Define(e.key, type, v.get("value") == null ? null : v.get("value").value,
                env == null ? null : (String) env.value,
                v.get("default") == null ? null : v.get("default").value, ln);
    }

    public String str(String key, boolean required) {
        Jsonc.Node n = root.get(key);
        if (n == null) {
            if (required) throw JpmException.at(file, 1, "missing required field \"" + key + "\"");
            return null;
        }
        if (n.value instanceof BigDecimal bd && (key.equals("java") || key.equals("target"))) return bd.toPlainString();
        if (!n.isString()) throw err(n, key + " must be a string");
        return (String) n.value;
    }

    public Map<String, String> stringMap(String key) {
        Map<String, String> out = new LinkedHashMap<>();
        Jsonc.Node n = root.get(key);
        if (n == null) return out;
        if (!n.isObject()) throw err(n, key + " must be an object");
        for (Jsonc.Member e : n.members.values()) {
            if (!e.value.isString()) throw err(e.value, key + "." + e.key + " must be a string");
            out.put(e.key, (String) e.value.value);
        }
        return out;
    }

    public List<String> folders(String key) {
        List<String> out = stringList(key);
        for (String f : out) {
            Path p = Path.of(f).normalize();
            if (f.isBlank() || p.isAbsolute() || p.startsWith("..")) throw err(root.get(key), key + " are folders inside the module, such as \"src/main/java\"");
        }
        return out;
    }

    public List<String> stringList(String key) {
        List<String> out = new ArrayList<>();
        Jsonc.Node n = root.get(key);
        if (n == null) return out;
        if (!n.isArray()) throw err(n, key + " must be an array of strings");
        for (Jsonc.Node i : n.items) {
            if (!i.isString()) throw err(i, key + " must be an array of strings");
            out.add((String) i.value);
        }
        return out;
    }
}
