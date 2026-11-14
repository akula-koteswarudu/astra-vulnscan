package com.vulnscan.scan;

/**
 * Whitespace-insensitive search and fuzzy matching for evidence checks.
 * {@link Normalized} keeps a map from normalised offsets back to the original text, so a match can be turned into
 * line numbers of the original file.
 */
public final class TextMatch {

    private TextMatch() {
    }

    /** Text with every whitespace run collapsed to one space; origin[i] = offset in the original of char i. */
    public record Normalized(String text, int[] origin) {

        public static Normalized of(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            int[] origin = new int[s.length() + 1];
            boolean pendingSpace = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    pendingSpace = sb.length() > 0;
                    continue;
                }
                if (pendingSpace) {
                    origin[sb.length()] = i - 1;
                    sb.append(' ');
                    pendingSpace = false;
                }
                origin[sb.length()] = i;
                sb.append(c);
            }
            int[] trimmed = new int[sb.length() + 1];
            System.arraycopy(origin, 0, trimmed, 0, sb.length());
            trimmed[sb.length()] = s.length();
            return new Normalized(sb.toString(), trimmed);
        }

        /** 1-based line of the original text at normalised offset i. */
        public int lineAt(String original, int i) {
            int pos = origin[Math.min(i, origin.length - 1)];
            int line = 1;
            for (int k = 0; k < pos && k < original.length(); k++) {
                if (original.charAt(k) == '\n') {
                    line++;
                }
            }
            return line;
        }
    }

    public static String normalize(String s) {
        return Normalized.of(s == null ? "" : s).text();
    }

    /**
     * rapidfuzz-style partial ratio in [0, 100]: the best indel similarity between the shorter string and any window of
     * the longer string of the same length. Indel similarity = 2 * LCS / (len1 + len2).
     */
    public static double partialRatio(String needle, String haystack) {
        if (needle.isEmpty() || haystack.isEmpty()) {
            return 0;
        }
        if (needle.length() > haystack.length()) {
            String t = needle;
            needle = haystack;
            haystack = t;
        }
        int n = needle.length();
        double best = 0;
        for (int start = 0; start + n <= haystack.length(); start++) {
            // Cheap filter: windows must share the first or last character to be worth scoring.
            if (haystack.charAt(start) != needle.charAt(0) && haystack.charAt(start + n - 1) != needle.charAt(n - 1)) {
                continue;
            }
            double r = 200.0 * lcs(needle, haystack, start, n) / (2.0 * n);
            if (r > best) {
                best = r;
                if (best >= 100) {
                    break;
                }
            }
        }
        return best;
    }

    /** Longest common subsequence of a and b[start .. start+len). */
    static int lcs(String a, String b, int start, int len) {
        int[] prev = new int[len + 1];
        int[] cur = new int[len + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= len; j++) {
                cur[j] = a.charAt(i - 1) == b.charAt(start + j - 1) ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[len];
    }

    /** Indel similarity of two whole strings in [0, 1] (used by the sanitiser's answer-key check). */
    public static double ratio(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1;
        }
        return 2.0 * lcs(a, b, 0, b.length()) / (a.length() + b.length());
    }
}
