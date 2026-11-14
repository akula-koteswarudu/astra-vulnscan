package com.vulnscan.matching;

import com.vulnscan.config.MatchingConfig;
import com.vulnscan.scan.Finding;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Structured similarity of (item, finding) in [0, 1] from configs/matching.yaml: a location score and a CWE score,
 * each used only when the item has that field, combined with the configured weights. Description hints (exact,
 * case-insensitive names of the app's classes, components and string resources found in the item's description)
 * count as a location signal.
 */
public class StructuredScorer {

    private final MatchingConfig cfg;
    private final List<String> appNames;

    /** appNames: simple class names, component names and string-resource names of the app (for description hints). */
    public StructuredScorer(MatchingConfig cfg, List<String> appNames) {
        this.cfg = cfg;
        this.appNames = appNames.stream().filter(n -> n.length() >= 4).distinct().toList();
    }

    public record Score(double total, Double location, Double cwe, boolean hasSignals) {
    }

    public Score score(ReferenceItem item, Finding f) {
        Double location = null;
        if (item.locations() != null && !item.locations().isEmpty()) {
            location = item.locations().stream().mapToDouble(p -> placeScore(p, f)).max().orElse(0);
        }
        List<String> hints = hints(item);
        if (!hints.isEmpty()) {
            double hintScore = hints.stream().anyMatch(h -> mentions(f, h)) ? cfg.locationScores().clazz() : 0;
            location = location == null ? hintScore : Math.max(location, hintScore);
        }
        Double cwe = null;
        if (item.acceptedCwes() != null && !item.acceptedCwes().isEmpty()) {
            cwe = cweScore(item, f);
        } else if (item.owaspCategory() != null && f.owaspCategory() != null) {
            cwe = item.owaspCategory().equals(f.owaspCategory()) ? cfg.cweScores().sameOwasp() : 0.0;
        }
        double wSum = 0;
        double sum = 0;
        if (location != null) {
            sum += cfg.weights().location() * location;
            wSum += cfg.weights().location();
        }
        if (cwe != null) {
            sum += cfg.weights().cwe() * cwe;
            wSum += cfg.weights().cwe();
        }
        return new Score(wSum == 0 ? 0 : sum / wSum, location, cwe, wSum > 0);
    }

    double placeScore(ReferenceItem.Place p, Finding f) {
        String fFile = f.location().file();
        String fClass = classOf(f);
        String fMethod = methodOf(f);
        String pClass = p.clazz() == null ? null : simple(p.clazz());
        if (pClass != null && pClass.equals(fClass)) {
            return p.method() != null && p.method().equals(fMethod) ? cfg.locationScores().method() : cfg.locationScores().clazz();
        }
        if (p.component() != null && (p.component().equals(f.location().component()) || simple(p.component()).equals(fClass))) {
            return cfg.locationScores().component();
        }
        if (p.file() != null && fFile != null && (fFile.equals(p.file()) || fFile.endsWith("/" + p.file()) || p.file().endsWith("/" + fFile))) {
            return cfg.locationScores().file();
        }
        return 0;
    }

    double cweScore(ReferenceItem item, Finding f) {
        if (f.cwe() == null) {
            return 0;
        }
        if (item.acceptedCwes().contains(f.cwe())) {
            return cfg.cweScores().accepted();
        }
        for (List<String> group : cfg.cweGroups().values()) {
            if (group.contains(f.cwe()) && item.acceptedCwes().stream().anyMatch(group::contains)) {
                return cfg.cweScores().sameGroup();
            }
        }
        return 0;
    }

    /** App names mentioned in the item's title or description (exact word, case-insensitive). */
    public List<String> hints(ReferenceItem item) {
        String text = (item.title() + " " + item.description()).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String n : appNames) {
            if (Pattern.compile("(?<![\\w$])" + Pattern.quote(n.toLowerCase(Locale.ROOT)) + "(?![\\w$])").matcher(text).find()) {
                out.add(n);
            }
        }
        return out;
    }

    private static boolean mentions(Finding f, String name) {
        return Objects.equals(classOf(f), name) || (f.location().component() != null && simple(f.location().component()).equals(name))
                || (f.location().file() != null && f.location().file().endsWith("/" + name + ".java"));
    }

    static String classOf(Finding f) {
        String symbol = f.location().symbol();
        if (symbol != null && !symbol.isBlank()) {
            String[] parts = symbol.split("\\.");
            // "Foo.bar" -> Foo ; "com.x.Foo.bar" -> Foo (the part before the method)
            for (int i = parts.length - 1; i >= 0; i--) {
                if (!parts[i].isEmpty() && Character.isUpperCase(parts[i].charAt(0))) {
                    return parts[i];
                }
            }
        }
        String file = f.location().file();
        if (file != null && file.endsWith(".java")) {
            return file.substring(file.lastIndexOf('/') + 1, file.length() - 5);
        }
        return null;
    }

    static String methodOf(Finding f) {
        String symbol = f.location().symbol();
        if (symbol == null || !symbol.contains(".")) {
            return null;
        }
        String last = symbol.substring(symbol.lastIndexOf('.') + 1);
        return last.isEmpty() || Character.isUpperCase(last.charAt(0)) ? null : last;
    }

    static String simple(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }
}
