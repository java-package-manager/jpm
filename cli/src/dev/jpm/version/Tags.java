package dev.jpm.version;

import java.util.Collection;

/**
 * Versions named by Git tags (ADR 0007).
 */
public final class Tags {
    private Tags() {
    }

    /**
     * The version a tag names: {@code 1.2.0} or {@code v1.2.0}, or {@code <module>/1.2.0} when the repository
     * tags its modules that way (some tag starts with {@code <module>/}).
     *
     * @param module  the Git module the dependency names, or null
     * @param tag     the tag
     * @param allTags every tag of the repository
     * @return the version, or null when the tag names none for this module
     */
    public static String version(String module, String tag, Collection<String> allTags) {
        boolean prefixed = module != null && allTags.stream().anyMatch(t -> t.startsWith(module + "/"));
        String v;
        if (prefixed) {
            if (!tag.startsWith(module + "/")) return null;
            v = tag.substring(module.length() + 1);
        } else {
            if (tag.contains("/")) return null;
            v = tag;
        }
        if (v.startsWith("v")) v = v.substring(1);
        return v.matches("\\d+(\\.\\d+){0,2}(-[0-9A-Za-z.-]+)?") ? v : null;
    }
}
