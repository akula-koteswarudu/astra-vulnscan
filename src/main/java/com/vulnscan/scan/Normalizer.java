package com.vulnscan.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-processor 1: canonical forms. Relative '/' paths, inner classes folded into their top-level class
 * (Foo$1.run -> Foo.run), CWE as "CWE-n", severity and kind lower-case, confidence clamped to [0, 1].
 */
public class Normalizer {

    private static final Pattern CWE_NUMBER = Pattern.compile("(\\d+)");

    public RawFinding normalize(RawFinding f) {
        List<RawFinding.RawLocation> locations = new ArrayList<>();
        for (RawFinding.RawLocation l : f.locations()) {
            if (l == null) {
                continue;
            }
            locations.add(new RawFinding.RawLocation(path(l.file()), blankToNull(l.component()), symbol(l.symbol())));
        }
        double confidence = f.confidence() == null ? 0.5 : Math.max(0, Math.min(1, f.confidence()));
        return new RawFinding(f.title().strip(), f.vulnClass() == null ? f.title().strip() : f.vulnClass().strip(), cwe(f.cwe()),
                f.owaspCategory() == null ? null : f.owaspCategory().strip().toUpperCase(Locale.ROOT), lower(f.severity(), "medium"),
                confidence, locations, f.evidence(), f.rationale() == null ? "" : f.rationale().strip(), lower(f.kind(), "code"));
    }

    static String path(String p) {
        if (p == null || p.isBlank()) {
            return null;
        }
        String s = p.strip().replace('\\', '/');
        while (s.startsWith("/") || s.startsWith("./")) {
            s = s.startsWith("/") ? s.substring(1) : s.substring(2);
        }
        return s;
    }

    /** Foo$1 / Foo$Inner -> Foo, keeping a trailing ".method". */
    static String symbol(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.strip().replaceAll("\\$[\\w$]*", "").replaceAll("\\(.*\\)$", "");
    }

    static String cwe(String c) {
        if (c == null) {
            return null;
        }
        Matcher m = CWE_NUMBER.matcher(c);
        return m.find() ? "CWE-" + Integer.parseInt(m.group(1)) : null;
    }

    private static String lower(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.strip().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() || s.equalsIgnoreCase("null") ? null : s.strip();
    }
}
