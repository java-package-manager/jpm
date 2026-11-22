package dev.jpm.build;

import dev.jpm.Manifest;
import dev.jpm.api.json.Jsonc;
import dev.jpm.util.JpmException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Build-time defines: values from the manifest, the environment or the consuming project, compiled
 * into a type-safe constants class such as {@code BuildConfig.API_URL}.
 */
public final class Defines {
    private Defines() {}

    /** A resolved define: its name, type and value. */
    public record Value(String name, Manifest.DefineType type, Object value) {
        /** The value as a Java literal, for the generated class. */
        public String javaLiteral() {
            return switch (type) {
                case STRING -> Jsonc.quote((String) value);
                case INT -> value.toString();
                case BOOLEAN -> value.toString();
            };
        }
    }

    /**
     * Resolves the defines a module declares.
     *
     * @param m        the module's manifest
     * @param library  the dependency key when the module is built as someone else's dependency, else null
     * @param provided values the consuming project gives for this library (may be null)
     * @param consumer the consuming project's manifest, for error messages (may be null)
     * @param env      environment variables (files plus process)
     */
    public static List<Value> resolve(Manifest m, String library, Map<String, Object> provided, Manifest consumer,
                                      Map<String, String> env) {
        List<Value> out = new ArrayList<>();
        for (Manifest.Define d : m.defines.values()) {
            Object raw = null;
            String origin = null;
            if (provided != null && provided.containsKey(d.name())) {
                raw = provided.get(d.name());
                origin = "provided";
            } else if (d.env() != null && env.get(d.env()) != null) {
                raw = env.get(d.env());
                origin = "env";
            } else if (d.value() != null) {
                raw = d.value();
                origin = "value";
            } else if (d.defaultValue() != null) {
                raw = d.defaultValue();
                origin = "default";
            }
            if (raw == null) throw missing(m, d, library, consumer);
            out.add(new Value(d.name(), d.type(), coerce(m, d, raw, origin, library, consumer)));
        }
        if (provided != null && library != null) {
            for (String k : provided.keySet()) {
                if (!m.defines.containsKey(k)) {
                    throw new JpmException((consumer != null ? consumer.file + ": " : "") + library + " has no define named " + k
                            + (m.defines.isEmpty() ? " (it declares none)" : " (it declares " + String.join(", ", m.defines.keySet()) + ")"));
                }
            }
        }
        return out;
    }

    private static Object coerce(Manifest m, Manifest.Define d, Object raw, String origin, String library, Manifest consumer) {
        String typeName = d.type().name().toLowerCase();
        switch (d.type()) {
            case STRING:
                if (raw instanceof String) return raw;
                if (origin.equals("env")) return raw.toString();
                break;
            case INT:
                if (raw instanceof BigDecimal bd) {
                    try {
                        return bd.intValueExact();
                    } catch (ArithmeticException e) {
                        break;
                    }
                }
                if (raw instanceof String s) {
                    try {
                        return Integer.parseInt(s.trim());
                    } catch (NumberFormatException e) {
                        break;
                    }
                }
                break;
            case BOOLEAN:
                if (raw instanceof Boolean) return raw;
                if (raw instanceof String s && (s.trim().equalsIgnoreCase("true") || s.trim().equalsIgnoreCase("false"))) {
                    return Boolean.parseBoolean(s.trim());
                }
                break;
        }
        String shown = raw instanceof String s ? Jsonc.quote(s) : String.valueOf(raw);
        String where = switch (origin) {
            case "env" -> "environment variable " + d.env();
            case "provided" -> "the value given for " + library;
            default -> m.file + " line " + d.line();
        };
        StringBuilder sb = new StringBuilder();
        sb.append("Define ").append(d.name()).append(library != null ? " of " + library : "")
                .append(" must be ").append(article(typeName)).append(" ").append(typeName).append(", but ")
                .append(where).append(" is ").append(shown).append('.');
        if (library != null && consumer != null) {
            sb.append("\nSet it in ").append(consumer.file).append(":\n").append(snippet(library, d));
        }
        throw new JpmException(sb.toString());
    }

    private static JpmException missing(Manifest m, Manifest.Define d, String library, Manifest consumer) {
        String typeName = d.type().name().toLowerCase();
        StringBuilder sb = new StringBuilder();
        if (library != null) {
            sb.append(library).append(" needs a value for define ").append(d.name()).append(" (").append(typeName).append(").");
            sb.append("\nAdd this to ").append(consumer != null ? consumer.file : Manifest.FILE).append(":\n").append(snippet(library, d));
        } else {
            sb.append("Define ").append(d.name()).append(" (").append(typeName).append(") has no value: ");
            sb.append(d.env() != null ? "environment variable " + d.env() + " is not set and there is no default"
                    : "it has no \"value\", \"env\" or \"default\"");
            sb.append(" (").append(m.file).append(" line ").append(d.line()).append(").");
            if (d.env() != null) sb.append("\nSet ").append(d.env()).append(" in the environment or in .env.");
        }
        return new JpmException(sb.toString());
    }

    private static String snippet(String library, Manifest.Define d) {
        String example = switch (d.type()) {
            case STRING -> "\"...\"";
            case INT -> "0";
            case BOOLEAN -> "false";
        };
        return "  \"define\": {\n    " + Jsonc.quote(library) + ": { " + Jsonc.quote(d.name()) + ": " + example + " }\n  }";
    }

    private static String article(String w) {
        return "aeiou".indexOf(w.charAt(0)) >= 0 ? "an" : "a";
    }

    /** Fully qualified name of the generated class. */
    public static String className(Manifest m) {
        if (m.defineClass != null) return m.defineClass;
        String pkg = null;
        if (!m.mains.isEmpty()) {
            String main = m.mains.values().iterator().next();
            int dot = main.lastIndexOf('.');
            if (dot > 0) pkg = main.substring(0, dot);
        }
        if (pkg == null && m.group != null) pkg = m.group + "." + sanitize(m.name);
        if (pkg == null) pkg = sanitize(m.name);
        return pkg + ".BuildConfig";
    }

    static String sanitize(String name) {
        String s = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) s = "p" + s;
        return s;
    }

    /**
     * The generated defines class as Kotlin, for a module with Kotlin sources only: an {@code object} whose
     * {@code const val}s are the same static final fields Java code reads.
     */
    public static String kotlinSource(String fqcn, List<Value> values, String projectName, String version) {
        int dot = fqcn.lastIndexOf('.');
        String pkg = dot > 0 ? fqcn.substring(0, dot) : null;
        StringBuilder sb = new StringBuilder();
        if (pkg != null) sb.append("package ").append(pkg).append("\n\n");
        sb.append("object ").append(fqcn.substring(dot + 1)).append(" {\n");
        sb.append("    const val NAME: String = ").append(kotlinString(projectName)).append('\n');
        sb.append("    const val VERSION: String = ").append(kotlinString(version)).append('\n');
        for (Value v : values) {
            String t = switch (v.type) {
                case STRING -> "String";
                case INT -> "Int";
                case BOOLEAN -> "Boolean";
            };
            String literal = v.type == Manifest.DefineType.STRING ? kotlinString((String) v.value) : v.value.toString();
            sb.append("    const val ").append(v.name).append(": ").append(t).append(" = ").append(literal).append('\n');
        }
        return sb.append("}\n").toString();
    }

    static String kotlinString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '$' -> sb.append("\\$");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** The source of the generated defines class {@code fqcn}, with NAME, VERSION and the values as constants. */
    public static String source(String fqcn, List<Value> values, String projectName, String version) {
        int dot = fqcn.lastIndexOf('.');
        String pkg = dot > 0 ? fqcn.substring(0, dot) : null;
        String simple = fqcn.substring(dot + 1);
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by jpm from the \"define\" section of project.jsonc. Do not edit.\n");
        if (pkg != null) sb.append("package ").append(pkg).append(";\n\n");
        sb.append("public final class ").append(simple).append(" {\n");
        sb.append("    private ").append(simple).append("() {}\n\n");
        sb.append("    public static final String NAME = ").append(Jsonc.quote(projectName)).append(";\n");
        sb.append("    public static final String VERSION = ").append(Jsonc.quote(version)).append(";\n");
        for (Value v : values) {
            String t = switch (v.type) {
                case STRING -> "String";
                case INT -> "int";
                case BOOLEAN -> "boolean";
            };
            sb.append("    public static final ").append(t).append(' ').append(v.name).append(" = ").append(v.javaLiteral()).append(";\n");
        }
        sb.append("}\n");
        return sb.toString();
    }
}
