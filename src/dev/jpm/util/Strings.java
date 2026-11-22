package dev.jpm.util;

import java.util.Collection;

/** Text helpers. */
public final class Strings {
    private Strings() {}

    /**
     * The candidate closest to {@code s} by edit distance, ignoring case, if it is close enough to be a typo
     * (at most a third of the length, and at most 3 edits); otherwise null.
     */
    public static String closest(String s, Collection<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String c : candidates.stream().sorted().toList()) {
            int d = distance(s.toLowerCase(), c.toLowerCase());
            if (d < bestDistance) {
                best = c;
                bestDistance = d;
            }
        }
        return best != null && bestDistance <= Math.min(3, Math.max(1, s.length() / 3)) ? best : null;
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j], cur[j - 1]) + 1);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
