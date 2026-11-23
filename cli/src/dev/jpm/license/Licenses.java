package dev.jpm.license;

import dev.jpm.api.json.Jsonc;
import dev.jpm.util.Fs;
import dev.jpm.util.Http;
import dev.jpm.util.JpmException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SPDX license list (a copy in {@code spdx-licenses.json}, updated with {@code schema/spdx.py}): checking ids and
 * expressions, finding the id for a license's name, URL or text, and writing a license file from SPDX's text.
 */
public final class Licenses {
    private Licenses() {
    }

    /**
     * The files a project's license is in, in the order jpm looks for them.
     */
    public static final List<String> FILES = List.of("LICENSE", "LICENSE.md", "LICENSE.txt", "COPYING", "COPYING.md");

    /**
     * One license of the list.
     *
     * @param id         its SPDX id, such as {@code Apache-2.0}
     * @param name       its full name
     * @param deprecated whether SPDX deprecated the id
     * @param urls       where its text is published
     */
    public record License(String id, String name, boolean deprecated, List<String> urls) {}

    private static Map<String, License> byId;
    private static String listVersion;

    /**
     * Every license, by id.
     */
    public static synchronized Map<String, License> all() {
        if (byId == null) load();
        return byId;
    }

    /**
     * The version of the SPDX license list jpm has, such as {@code 3.29.0}.
     */
    public static synchronized String listVersion() {
        if (byId == null) load();
        return listVersion;
    }

    @SuppressWarnings("unchecked")
    private static void load() {
        try (InputStream in = Licenses.class.getResourceAsStream("/dev/jpm/spdx-licenses.json")) {
            if (in == null) throw new JpmException("this jpm build has no SPDX license list");
            Map<String, Object> root = (Map<String, Object>) Jsonc.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            Map<String, License> out = new LinkedHashMap<>();
            for (Object o : (List<Object>) root.get("licenses")) {
                Map<String, Object> m = (Map<String, Object>) o;
                List<String> urls = new ArrayList<>();
                if (m.get("urls") instanceof List<?> l) for (Object u : l) urls.add(String.valueOf(u));
                String id = String.valueOf(m.get("id"));
                out.put(id, new License(id, String.valueOf(m.get("name")), Boolean.TRUE.equals(m.get("deprecated")), List.copyOf(urls)));
            }
            byId = out;
            listVersion = String.valueOf(root.get("version"));
        } catch (IOException e) {
            throw new JpmException("Could not read the SPDX license list: " + e.getMessage());
        }
    }

    /**
     * The license with this id, matched as SPDX does, ignoring case; null when the list has none.
     */
    public static License find(String id) {
        License exact = all().get(id);
        if (exact != null) return exact;
        for (License l : all().values()) if (l.id().equalsIgnoreCase(id)) return l;
        return null;
    }

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9.+-]+");

    /**
     * The license ids of an SPDX expression ({@code MIT}, {@code MIT OR Apache-2.0}, {@code GPL-2.0-only WITH
     * Classpath-exception-2.0}): every id that is not an operator, an exception after {@code WITH}, or a
     * {@code LicenseRef-}.
     */
    public static List<String> ids(String expression) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(expression);
        boolean afterWith = false;
        while (m.find()) {
            String t = m.group();
            if (t.equals("AND") || t.equals("OR")) continue;
            if (t.equals("WITH")) {
                afterWith = true;
                continue;
            }
            if (!afterWith && !t.startsWith("LicenseRef-") && !t.startsWith("DocumentRef-")) out.add(t);
            afterWith = false;
        }
        return out;
    }

    /**
     * The ids of an expression that are not on the SPDX list.
     */
    public static List<String> unknown(String expression) {
        return ids(expression).stream().filter(id -> find(id) == null).toList();
    }

    /**
     * The id on the list closest to {@code id} (a different case, a missing {@code -only}, a small typo), or null.
     */
    public static String suggest(String id) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        String wanted = id.toLowerCase(Locale.ROOT);
        for (License l : all().values()) {
            if (l.deprecated()) continue;
            String cand = l.id().toLowerCase(Locale.ROOT);
            if (cand.equals(wanted + "-only") || cand.equals(wanted + "-or-later") && best == null) return l.id();
            int d = distance(wanted, cand);
            if (d < bestDistance) {
                bestDistance = d;
                best = l.id();
            }
        }
        return bestDistance <= Math.max(2, wanted.length() / 4) ? best : null;
    }

    private static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    /**
     * The id of the license a POM or a build names by {@code name} or {@code url}, or null when neither is on the list.
     */
    public static String forNameOrUrl(String name, String url) {
        if (name != null) {
            String n = name.strip();
            if (find(n) != null) return find(n).id();
            String normalized = normalizeName(n);
            for (License l : all().values()) if (!l.deprecated() && normalizeName(l.name()).equals(normalized)) return l.id();
        }
        if (url != null) {
            String u = normalizeUrl(url);
            for (License l : all().values()) {
                if (l.deprecated()) continue;
                for (String s : l.urls()) if (normalizeUrl(s).equals(u)) return l.id();
            }
        }
        return null;
    }

    private static String normalizeName(String s) {
        return s.toLowerCase(Locale.ROOT).replace("licence", "license").replaceAll("[,()-]", " ")
                .replaceAll("\\b(the|software|license|version|v)\\b", " ").replaceAll("[^a-z0-9.]", "");
    }

    private static String normalizeUrl(String s) {
        return s.toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("^www\\.", "").replaceAll("/+$", "")
                .replaceAll("\\.(html?|txt|php)$", "");
    }

    /**
     * A license family recognised from its text, with the ids that name it.
     */
    private record Fingerprint(List<String> ids, List<String> phrases, List<String> absent) {}

    private static final List<Fingerprint> FINGERPRINTS = List.of(
            new Fingerprint(List.of("AGPL-3.0-or-later", "AGPL-3.0-only"), List.of("gnu affero general public license", "version 3"), List.of()),
            new Fingerprint(List.of("LGPL-3.0-or-later", "LGPL-3.0-only"), List.of("gnu lesser general public license", "version 3"), List.of()),
            new Fingerprint(List.of("LGPL-2.1-or-later", "LGPL-2.1-only"), List.of("gnu lesser general public license", "version 2.1"), List.of()),
            new Fingerprint(List.of("GPL-3.0-or-later", "GPL-3.0-only"), List.of("gnu general public license", "version 3, 29 june 2007"), List.of()),
            new Fingerprint(List.of("GPL-2.0-or-later", "GPL-2.0-only"), List.of("gnu general public license", "version 2, june 1991"), List.of()),
            new Fingerprint(List.of("Apache-2.0"), List.of("apache license", "version 2.0, january 2004"), List.of()),
            new Fingerprint(List.of("MPL-2.0"), List.of("mozilla public license version 2.0"), List.of()),
            new Fingerprint(List.of("EPL-2.0"), List.of("eclipse public license - v 2.0"), List.of()),
            new Fingerprint(List.of("EPL-1.0"), List.of("eclipse public license - v 1.0"), List.of()),
            new Fingerprint(List.of("BSL-1.0"), List.of("boost software license - version 1.0"), List.of()),
            new Fingerprint(List.of("Unlicense"), List.of("this is free and unencumbered software released into the public domain"), List.of()),
            new Fingerprint(List.of("CC0-1.0"), List.of("cc0 1.0 universal"), List.of()),
            new Fingerprint(List.of("MIT"), List.of("permission is hereby granted, free of charge",
                    "the above copyright notice and this permission notice shall be included"), List.of()),
            new Fingerprint(List.of("BSD-3-Clause"), List.of("redistribution and use in source and binary forms", "neither the name"), List.of()),
            new Fingerprint(List.of("BSD-2-Clause"), List.of("redistribution and use in source and binary forms"), List.of("neither the name")),
            new Fingerprint(List.of("ISC"), List.of("permission to use, copy, modify, and/or distribute this software for any purpose",
                    "provided that the above copyright notice and this permission notice appear in all copies"), List.of()),
            new Fingerprint(List.of("0BSD"), List.of("permission to use, copy, modify, and/or distribute this software for any purpose"),
                    List.of("provided that the above copyright notice")));

    /**
     * The ids that name the license this text is of, the most likely first (for the GPL family, {@code -or-later}, which
     * the text alone cannot tell from {@code -only}); empty when it is none jpm recognises.
     */
    public static List<String> detect(String text) {
        String t = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (Fingerprint f : FINGERPRINTS) {
            if (f.phrases().stream().allMatch(t::contains) && f.absent().stream().noneMatch(t::contains)) return f.ids();
        }
        return List.of();
    }

    /**
     * The license file in {@code dir}, or null when it has none.
     */
    public static Path file(Path dir) {
        for (String f : FILES) if (Files.isRegularFile(dir.resolve(f))) return dir.resolve(f);
        return null;
    }

    /**
     * Whether an expression names one of these ids (the license file's), which is what a match means.
     */
    public static boolean names(String expression, List<String> ids) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String i : ids) wanted.add(i.toLowerCase(Locale.ROOT));
        for (String id : ids(expression)) {
            String l = id.toLowerCase(Locale.ROOT);
            if (wanted.contains(l) || wanted.contains(l.replaceFirst("\\+$", "-or-later")) || wanted.contains(l + "-only")) return true;
        }
        return false;
    }

    /**
     * The text of a license, from SPDX ({@code https://spdx.org/licenses/<id>.json}, or {@code JPM_SPDX_BASE} instead of
     * that address), with the year and the copyright holder filled in where it has places for them.
     */
    @SuppressWarnings("unchecked")
    public static String text(String id, int year, String holder) {
        License l = find(id);
        if (l == null) throw new JpmException("\"" + id + "\" is not an SPDX license id");
        String base = System.getenv("JPM_SPDX_BASE");
        String url = (base == null || base.isBlank() ? "https://spdx.org/licenses" : base.replaceAll("/+$", "")) + "/" + l.id() + ".json";
        String json = url.startsWith("file:") ? Fs.read(Path.of(java.net.URI.create(url))) : Http.getString(url);
        Object text = ((Map<String, Object>) Jsonc.parse(json)).get("licenseText");
        if (!(text instanceof String s)) throw new JpmException(url + " has no license text");
        return fill(s, year, holder);
    }

    /**
     * {@code text} with SPDX's placeholders for the year and the copyright holder replaced.
     */
    public static String fill(String text, int year, String holder) {
        String out = text.replaceAll("(?i)<\\s*year\\s*>|\\[yyyy\\]|\\{yyyy\\}", Integer.toString(year));
        if (holder != null && !holder.isBlank()) {
            out = out.replaceAll("(?i)<\\s*(copyright holders?|owner|name of author|author)\\s*>|\\[name of copyright owner\\]|\\{name of copyright owner\\}",
                    Matcher.quoteReplacement(holder));
        }
        return out.strip() + "\n";
    }
}
