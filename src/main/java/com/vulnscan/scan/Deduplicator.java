package com.vulnscan.scan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Post-processor 4 (across all units of an arm run): merges findings that are the same issue reported twice
 * (overlapping units, or two phrasings): same CWE + file + symbol; or same file + overlapping evidence lines + same
 * CWE group; or same file + the same quoted evidence (whitespace ignored) when the CWEs do not disagree (equal, same
 * group, or one missing) or the evidence is a single manifest attribute such as {@code android:allowBackup="true"}
 * (one setting is one issue, whatever CWE each copy chose). Chunked scans need the last rule: every unit sees its
 * manifest slice, so an app-wide setting comes back once per unit, often with a different CWE or none. The kept copy is chosen by evidence quality (exact/inventory > fuzzy > relocated > unverified), then
 * confidence, then content (file, symbol, title, evidence); never by id, because ids depend on the run, and a run-dependent
 * choice would make the same answer evaluate differently in another run folder. The merged finding lists every unit.
 */
public class Deduplicator {

    /** One manifest attribute, e.g. android:usesCleartextTraffic="true" (not a whole tag, which can carry several flaws). */
    private static final Pattern SINGLE_ATTRIBUTE = Pattern.compile("[\\w:.]+\\s*=\\s*\"[^\"]*\"");

    private final Map<String, List<String>> cweGroups;
    private final boolean enabled;

    public Deduplicator(Map<String, List<String>> cweGroups) {
        this(cweGroups, true);
    }

    /** enabled = false: no merging at all (scan.dedup: off, for an ablation); findings are only sorted. */
    public Deduplicator(Map<String, List<String>> cweGroups, boolean enabled) {
        this.cweGroups = cweGroups;
        this.enabled = enabled;
    }

    public List<Finding> dedup(List<Finding> findings) {
        if (!enabled) {
            List<Finding> all = new ArrayList<>(findings);
            all.sort(Comparator.comparing(Finding::id));
            return all;
        }
        List<Finding> sorted = new ArrayList<>(findings);
        sorted.sort(Comparator.comparingInt((Finding f) -> statusRank(f.evidenceStatus()))
                .thenComparing(Comparator.comparingDouble(Finding::confidence).reversed())
                .thenComparing(f -> String.valueOf(f.location().file()))
                .thenComparing(f -> String.valueOf(f.location().symbol()))
                .thenComparing(Finding::title)
                .thenComparing(Finding::evidence)
                .thenComparing(Finding::id));
        List<Finding> kept = new ArrayList<>();
        for (Finding f : sorted) {
            int match = -1;
            for (int i = 0; i < kept.size(); i++) {
                if (same(kept.get(i), f)) {
                    match = i;
                    break;
                }
            }
            if (match < 0) {
                kept.add(f);
            } else {
                kept.set(match, merge(kept.get(match), f));
            }
        }
        kept.sort(Comparator.comparing(Finding::id));
        return kept;
    }

    static int statusRank(String status) {
        return switch (status) {
            case "verified_exact", "inventory_match" -> 0;
            case "verified_fuzzy" -> 1;
            case "relocated" -> 2;
            default -> 3;
        };
    }

    boolean same(Finding a, Finding b) {
        if (!Objects.equals(a.location().file(), b.location().file())) {
            return false;
        }
        if (a.cwe() != null && a.cwe().equals(b.cwe()) && Objects.equals(a.location().symbol(), b.location().symbol())) {
            return true;
        }
        if (linesOverlap(a.location(), b.location()) && sameGroup(a.cwe(), b.cwe())) {
            return true;
        }
        String evidence = normalise(a.evidence());
        if (evidence.isEmpty() || !evidence.equals(normalise(b.evidence()))) {
            return false;
        }
        boolean cwesAgree = a.cwe() == null || b.cwe() == null || sameGroup(a.cwe(), b.cwe());
        boolean oneSetting = "manifest".equals(a.kind()) && "manifest".equals(b.kind()) && SINGLE_ATTRIBUTE.matcher(evidence).matches();
        return cwesAgree || oneSetting;
    }

    private static String normalise(String evidence) {
        return evidence == null ? "" : evidence.strip().replaceAll("\\s+", " ");
    }

    private static boolean linesOverlap(Finding.Location a, Finding.Location b) {
        if (a.line() == null || b.line() == null) {
            return false;
        }
        int aEnd = a.endLine() == null ? a.line() : a.endLine();
        int bEnd = b.endLine() == null ? b.line() : b.endLine();
        return a.line() <= bEnd && b.line() <= aEnd;
    }

    boolean sameGroup(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        return cweGroups.values().stream().anyMatch(g -> g.contains(a) && g.contains(b));
    }

    /** Lossless: the kept copy gets every location of the other copy in alsoAt; nothing is discarded. */
    private static Finding merge(Finding keep, Finding other) {
        TreeSet<String> units = new TreeSet<>(keep.provenance().unitIds());
        units.addAll(other.provenance().unitIds());
        Finding.Provenance p = keep.provenance();
        List<Finding.Location> also = new ArrayList<>(keep.alsoAt() == null ? List.of() : keep.alsoAt());
        for (Finding.Location l : other.allLocations()) {
            if (!l.equals(keep.location()) && !also.contains(l)) {
                also.add(l);
            }
        }
        also.sort(Comparator.comparing((Finding.Location l) -> String.valueOf(l.file())).thenComparing(l -> String.valueOf(l.symbol()))
                .thenComparing(l -> String.valueOf(l.line())));
        return new Finding(keep.id(), keep.title(), keep.vulnClass(), keep.cwe(), keep.owaspCategory(), keep.severity(),
                Math.max(keep.confidence(), other.confidence()), keep.location(), keep.evidence(), keep.rationale(), keep.kind(),
                keep.evidenceStatus(), keep.needsAdvisoryCheck(),
                new Finding.Provenance(p.modelUsed(), p.arm(), p.runId(), p.repeatIndex(), List.copyOf(units), p.promptVersion()),
                List.copyOf(also));
    }
}
