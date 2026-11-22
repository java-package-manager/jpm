package dev.jpm.version.impl;

import dev.jpm.version.Version;
import dev.jpm.version.VersionSpec;

import java.util.ArrayList;
import java.util.List;

public final class NpmRange implements VersionSpec {
    private record Cmp(String op, Version v, String pre) {
        boolean matches(Version x) {
            int c = x.compareTo(v);
            return switch (op) {
                case ">" -> c > 0;
                case ">=" -> c >= 0;
                case "<" -> c < 0;
                case "<=" -> c <= 0;
                default -> c == 0;
            };
        }
    }

    private final String text;
    private final List<List<Cmp>> alternatives;
    private final boolean exact;

    private NpmRange(String text, List<List<Cmp>> alternatives, boolean exact) {
        this.text = text;
        this.alternatives = alternatives;
        this.exact = exact;
    }

    public static boolean looksLikeRange(String s) {
        s = s.trim();
        if (s.isEmpty()) return false;
        if (s.equals("*") || s.equals("latest")) return true;
        return s.matches("[\\^~<>=v\\d*xX|. -]+") && s.matches(".*[\\d*xX].*");
    }

    public static NpmRange parse(String text) {
        String s = text.trim();
        if (s.equals("latest") || s.isEmpty()) s = "*";
        List<List<Cmp>> alts = new ArrayList<>();
        for (String alt : s.split("\\|\\|")) {
            alt = alt.trim();
            List<Cmp> set = new ArrayList<>();
            String[] hy = alt.split("\\s+-\\s+");
            if (hy.length == 2) {
                Part lo = Part.of(hy[0]);
                set.add(new Cmp(">=", lo.filled(), lo.pre));
                set.add(upperFromPartial(Part.of(hy[1])));
            } else {
                for (String tok : alt.replaceAll("([<>=^~]+)\\s+", "$1").split("\\s+")) {
                    if (tok.isEmpty()) continue;
                    set.addAll(comparators(tok, text));
                }
            }
            if (set.isEmpty()) set.add(new Cmp(">=", Version.of("0"), null));
            alts.add(set);
        }
        boolean exact = alts.size() == 1 && alts.get(0).size() == 1 && alts.get(0).get(0).op.equals("=");
        return new NpmRange(text, alts, exact);
    }

    private record Part(int[] n, String pre) {
        static Part of(String s) {
            s = s.trim();
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
            String pre = null;
            int dash = s.indexOf('-');
            if (dash >= 0) {
                pre = s.substring(dash + 1);
                s = s.substring(0, dash);
            }
            int plus = s.indexOf('+');
            if (plus >= 0) s = s.substring(0, plus);
            int[] out = {-1, -1, -1};
            if (s.isEmpty() || s.equals("*") || s.equalsIgnoreCase("x")) return new Part(out, pre);
            String[] parts = s.split("\\.");
            if (parts.length > 3) throw new IllegalArgumentException("Invalid version \"" + s + "\" in a range: at most major.minor.patch");
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i];
                if (p.equalsIgnoreCase("x") || p.equals("*")) break;
                try {
                    out[i] = Integer.parseInt(p);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid version \"" + s + "\" in a range");
                }
            }
            return new Part(out, pre);
        }

        int major() { return n[0]; }
        int minor() { return n[1]; }
        int patch() { return n[2]; }

        Version filled() {
            return version(Math.max(n[0], 0), Math.max(n[1], 0), Math.max(n[2], 0), pre);
        }
    }

    private static Version version(int major, int minor, int patch, String pre) {
        return Version.of(major + "." + minor + "." + patch + (pre == null ? "" : "-" + pre));
    }

    private static Cmp upperFromPartial(Part p) {
        if (p.patch() >= 0) return new Cmp("<=", p.filled(), p.pre);
        if (p.minor() >= 0) return new Cmp("<", version(p.major(), p.minor() + 1, 0, null), null);
        if (p.major() >= 0) return new Cmp("<", version(p.major() + 1, 0, 0, null), null);
        return new Cmp(">=", Version.of("0"), null);
    }

    private static List<Cmp> comparators(String tok, String text) {
        int i = 0;
        while (i < tok.length() && "<>=^~".indexOf(tok.charAt(i)) >= 0) i++;
        String op = tok.substring(0, i);
        Part p = Part.of(tok.substring(i));
        List<Cmp> out = new ArrayList<>();
        switch (op) {
            case "^" -> {
                Version hi;
                if (p.major() < 0) {
                    out.add(new Cmp(">=", Version.of("0"), null));
                    return out;
                } else if (p.major() > 0 || p.minor() < 0) hi = version(p.major() + 1, 0, 0, null);
                else if (p.minor() > 0 || p.patch() < 0) hi = version(0, p.minor() + 1, 0, null);
                else hi = version(0, 0, p.patch() + 1, null);
                out.add(new Cmp(">=", p.filled(), p.pre));
                out.add(new Cmp("<", hi, null));
            }
            case "~", "~>" -> {
                if (p.major() < 0) {
                    out.add(new Cmp(">=", Version.of("0"), null));
                    return out;
                }
                out.add(new Cmp(">=", p.filled(), p.pre));
                if (p.minor() < 0) out.add(new Cmp("<", version(p.major() + 1, 0, 0, null), null));
                else out.add(new Cmp("<", version(p.major(), p.minor() + 1, 0, null), null));
            }
            case "", "=" -> {
                if (p.major() < 0) {
                    out.add(new Cmp(">=", Version.of("0"), null));
                } else if (p.minor() < 0) {
                    out.add(new Cmp(">=", version(p.major(), 0, 0, null), null));
                    out.add(new Cmp("<", version(p.major() + 1, 0, 0, null), null));
                } else if (p.patch() < 0) {
                    out.add(new Cmp(">=", version(p.major(), p.minor(), 0, null), null));
                    out.add(new Cmp("<", version(p.major(), p.minor() + 1, 0, null), null));
                } else {
                    out.add(new Cmp("=", p.filled(), p.pre));
                }
            }
            case ">" -> {
                if (p.major() < 0) out.add(new Cmp("<", Version.of("0"), null));
                else if (p.minor() < 0) out.add(new Cmp(">=", version(p.major() + 1, 0, 0, null), null));
                else if (p.patch() < 0) out.add(new Cmp(">=", version(p.major(), p.minor() + 1, 0, null), null));
                else out.add(new Cmp(">", p.filled(), p.pre));
            }
            case ">=" -> out.add(new Cmp(">=", p.filled(), p.pre));
            case "<" -> out.add(new Cmp("<", p.filled(), p.pre));
            case "<=" -> {
                if (p.patch() < 0) out.add(upperFromPartial(p));
                else out.add(new Cmp("<=", p.filled(), p.pre));
            }
            default -> throw new IllegalArgumentException("Invalid range operator \"" + op + "\" in \"" + text + "\"");
        }
        return out;
    }

    @Override
    public boolean matches(Version version) {
        boolean pre = version.isPreRelease();
        for (List<Cmp> set : alternatives) {
            boolean ok = true;
            for (Cmp c : set) {
                if (!c.matches(version)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            if (!pre) return true;
            for (Cmp c : set) {
                if (c.pre != null && sameRelease(version, c.v)) return true;
            }
        }
        return false;
    }

    private static boolean sameRelease(Version a, Version b) {
        return Version.of(numericPart(a.toString())).equals(Version.of(numericPart(b.toString())));
    }

    private static String numericPart(String v) {
        if (v.startsWith("v")) v = v.substring(1);
        int i = 0;
        while (i < v.length() && (Character.isDigit(v.charAt(i)) || v.charAt(i) == '.')) i++;
        String n = v.substring(0, i);
        while (n.endsWith(".")) n = n.substring(0, n.length() - 1);
        return n.isEmpty() ? "0" : n;
    }

    @Override
    public String text() { return text; }

    @Override
    public boolean isExact() { return exact; }

    @Override
    public Version exactVersion() { return exact ? alternatives.get(0).get(0).v : null; }

    @Override
    public String toString() { return text; }
}
