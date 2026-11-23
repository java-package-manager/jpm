package dev.jpm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes docs/manifest.md's field reference from project.schema.json, so the two never disagree.
 */
final class ManifestReference {
    private final Map<String, Object> schema;

    ManifestReference(Map<String, Object> schema) {
        this.schema = schema;
    }

    @SuppressWarnings("unchecked")
    String render() {
        StringBuilder out = new StringBuilder();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        for (Map.Entry<String, Object> e : props.entrySet()) {
            if (e.getKey().equals("$schema")) continue;
            Map<String, Object> node = (Map<String, Object>) e.getValue();
            out.append("### `").append(e.getKey()).append("`\n\n");
            String desc = description(node);
            if (desc != null) out.append(desc).append("\n\n");
            String type = type(node);
            if (type != null) out.append("Type: ").append(type).append(".");
            if (Manifest.INHERITED.contains(e.getKey())) out.append(type != null ? " " : "").append("Workspace modules inherit it from the root.");
            if (type != null || Manifest.INHERITED.contains(e.getKey())) out.append("\n\n");
            List<String> lines = new ArrayList<>();
            members(node, "", lines, 0);
            if (!lines.isEmpty()) out.append(String.join("\n", lines)).append("\n\n");
        }
        return out.toString().stripTrailing() + "\n";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolve(Map<String, Object> node) {
        Object ref = node.get("$ref");
        if (!(ref instanceof String r) || !r.startsWith("#/definitions/")) return node;
        Map<String, Object> defs = (Map<String, Object>) schema.get("definitions");
        Map<String, Object> target = (Map<String, Object>) defs.get(r.substring("#/definitions/".length()));
        return target == null ? node : resolve(target);
    }

    private String description(Map<String, Object> node) {
        if (node.get("description") instanceof String d) return d;
        Map<String, Object> r = resolve(node);
        return r != node && r.get("description") instanceof String d ? d : null;
    }

    @SuppressWarnings("unchecked")
    private String type(Map<String, Object> raw) {
        Map<String, Object> node = resolve(raw);
        if (node.get("enum") instanceof List<?> en) {
            if (en.size() > 12) return "one of " + en.size() + " values";
            List<String> v = new ArrayList<>();
            for (Object o : en) v.add("`" + quote(o) + "`");
            return "one of " + String.join(", ", v);
        }
        for (String k : List.of("oneOf", "anyOf")) {
            if (node.get(k) instanceof List<?> alts) {
                List<String> parts = new ArrayList<>();
                for (Object a : alts) {
                    String t = a instanceof Map<?, ?> m ? type((Map<String, Object>) m) : null;
                    if (t != null && !parts.contains(t)) parts.add(t);
                }
                return parts.isEmpty() ? null : String.join(", or ", parts);
            }
        }
        if (!(node.get("type") instanceof String t)) return null;
        return switch (t) {
            case "array" -> node.get("items") instanceof Map<?, ?> items && type((Map<String, Object>) items) != null
                    ? "a list of " + plural(type((Map<String, Object>) items)) : "a list";
            case "object" -> "an object";
            case "string" -> "a string";
            case "boolean" -> "`true` or `false`";
            case "integer" -> "a whole number";
            case "number" -> "a number";
            default -> t;
        };
    }

    private static String plural(String t) {
        return switch (t) {
            case "a string" -> "strings";
            case "an object" -> "objects";
            default -> t;
        };
    }

    private static String quote(Object o) {
        return o instanceof String s ? "\"" + s + "\"" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private void members(Map<String, Object> raw, String indent, List<String> lines, int depth) {
        if (depth > 2) return;
        Map<String, Object> node = resolve(raw);
        if (node.get("properties") instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> e : props.entrySet()) {
                Map<String, Object> child = (Map<String, Object>) e.getValue();
                if ("#/definitions/versionSpec".equals(child.get("$ref")) && !child.containsKey("description")) {
                    lines.add(indent + "- `" + e.getKey() + "`: a version or range, see [Versions](dependencies.md#versions)");
                    continue;
                }
                String d = description(child), t = type(child);
                lines.add(indent + "- `" + e.getKey() + "`" + (t != null ? " (" + t + ")" : "") + (d != null ? ": " + d : ""));
                members(child, indent + "  ", lines, depth + 1);
            }
        }
        if (node.get("additionalProperties") instanceof Map<?, ?> ap) {
            Map<String, Object> each = resolve((Map<String, Object>) ap);
            if (each.get("properties") instanceof Map<?, ?> || each.get("oneOf") instanceof List<?>) {
                List<String> inner = new ArrayList<>();
                members(each, indent + "  ", inner, depth + 1);
                if (!inner.isEmpty()) {
                    lines.add(indent + "- each entry" + (type(each) != null ? " is " + type(each) : "") + (inner.isEmpty() ? "" : "; an object has:"));
                    lines.addAll(inner);
                }
            }
        }
        for (String k : List.of("oneOf", "anyOf")) {
            if (node.get(k) instanceof List<?> alts) {
                for (Object a : alts) if (a instanceof Map<?, ?> m) members((Map<String, Object>) m, indent, lines, depth);
            }
        }
        if (node.get("items") instanceof Map<?, ?> items) members((Map<String, Object>) items, indent, lines, depth + 1);
    }
}
