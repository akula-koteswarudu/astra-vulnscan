package com.vulnscan.scan;

import com.vulnscan.ingest.CodeBase;
import com.vulnscan.planning.ScanUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Post-processor 3: does the quoted evidence really exist? (plan 6.5)
 * <ol>
 *   <li>dependency findings: the evidence must match an inventory line exactly -> inventory_match (flagged
 *       needs_advisory_check: the "known vulnerable" claim relies on the model's advisory knowledge), else inventory_unmatched;</li>
 *   <li>whitespace-normalised exact match in the cited file -> verified_exact (line computed);</li>
 *   <li>exact match in another file of the unit, or in the unit's manifest/resource text -> relocated (location corrected;
 *       needs at least min_evidence_chars), except that manifest/resource findings found in that text are verified_exact;</li>
 *   <li>fuzzy (partial ratio &gt;= threshold, at least min_evidence_chars) in the cited file -> verified_fuzzy;</li>
 *   <li>otherwise unverified.</li>
 * </ol>
 */
public class EvidenceVerifier {

    public static final String MANIFEST_FILE = "AndroidManifest.xml";
    private static final Pattern ELLIPSIS = Pattern.compile("\\.\\.\\.|…");

    private final CodeBase code;
    private final int fuzzyThreshold;
    private final int minEvidenceChars;

    public EvidenceVerifier(CodeBase code, int fuzzyThreshold, int minEvidenceChars) {
        this.code = code;
        this.fuzzyThreshold = fuzzyThreshold;
        this.minEvidenceChars = minEvidenceChars;
    }

    /** Result: status plus the (possibly corrected) file and computed lines. */
    public record Check(String status, String file, Integer line, Integer endLine, boolean needsAdvisoryCheck) {
    }

    public Check verify(RawFinding f, ScanUnit unit) {
        RawFinding.RawLocation loc = f.locations().get(0);
        String evidence = TextMatch.normalize(f.evidence());

        if ("dependency".equals(f.kind())) {
            return inventoryCheck(evidence, unit, loc.file());
        }

        String cited = resolveFile(loc.file(), unit);
        if (cited != null) {
            Check c = exact(evidence, cited, code.text(cited));
            if (c != null) {
                return c;
            }
        }
        boolean longEnough = evidence.length() >= minEvidenceChars;
        for (String other : unit.files()) {
            if (!other.equals(cited) && longEnough) {
                Check c = exact(evidence, other, code.text(other));
                if (c != null) {
                    return new Check("relocated", other, c.line(), c.endLine(), false);
                }
            }
        }
        for (String[] ctx : contextTexts(unit)) {
            if (ctx[1] != null && TextMatch.normalize(ctx[1]).contains(evidence) && !evidence.isEmpty()) {
                boolean declaredHere = !"code".equals(f.kind());
                if (declaredHere || longEnough) {
                    return new Check(declaredHere ? "verified_exact" : "relocated", ctx[0], null, null, false);
                }
            }
        }
        // An abbreviated quote ("a ... b"): every fragment must appear in order in the cited file.
        if (cited != null && ELLIPSIS.matcher(f.evidence()).find() && evidence.length() >= minEvidenceChars) {
            Check c = fragments(f.evidence(), cited);
            if (c != null) {
                return c;
            }
        }
        if (cited != null && longEnough) {
            String fileText = TextMatch.normalize(code.text(cited));
            if (TextMatch.partialRatio(evidence, fileText) >= fuzzyThreshold) {
                return new Check("verified_fuzzy", cited, null, null, false);
            }
        }
        return new Check("unverified", cited != null ? cited : loc.file(), null, null, false);
    }

    private Check inventoryCheck(String evidence, ScanUnit unit, String citedFile) {
        if (unit.dependencies() != null) {
            for (String line : unit.dependencies().split("\n")) {
                String l = TextMatch.normalize(line);
                String name = l.split(" ")[0];
                // Exact inventory line, or the name with its known version written as name:version / name@version.
                if (!l.isEmpty() && (evidence.equals(l) || evidence.contains(l)
                        || (!l.contains("(version unknown)") && (evidence.contains(name + ":" + l.substring(name.length()).strip())
                        || evidence.contains(name + "@" + l.substring(name.length()).strip())))
                        || (l.endsWith("(version unknown)") && evidence.equals(name)))) {
                    return new Check("inventory_match", "dependency:" + name, null, null, true);
                }
            }
        }
        return new Check("inventory_unmatched", citedFile, null, null, true);
    }

    private Check exact(String evidence, String path, String original) {
        if (evidence.isEmpty()) {
            return null;
        }
        TextMatch.Normalized n = TextMatch.Normalized.of(original);
        int i = n.text().indexOf(evidence);
        if (i < 0) {
            return null;
        }
        return new Check("verified_exact", path, n.lineAt(original, i), n.lineAt(original, i + evidence.length() - 1), false);
    }

    private Check fragments(String rawEvidence, String path) {
        String original = code.text(path);
        TextMatch.Normalized n = TextMatch.Normalized.of(original);
        int from = 0;
        Integer first = null;
        int last = 0;
        for (String part : ELLIPSIS.split(rawEvidence)) {
            String p = TextMatch.normalize(part);
            if (p.length() < 10) {
                continue;
            }
            int i = n.text().indexOf(p, from);
            if (i < 0) {
                return null;
            }
            if (first == null) {
                first = i;
            }
            from = i + p.length();
            last = from - 1;
        }
        return first == null ? null : new Check("verified_fuzzy", path, n.lineAt(original, first), n.lineAt(original, last), false);
    }

    /** The unit file the model meant: exact path, else the unique unit file whose path ends with what was cited. */
    static String resolveFile(String cited, ScanUnit unit) {
        if (cited == null) {
            return null;
        }
        if (unit.files().contains(cited)) {
            return cited;
        }
        String suffix = cited.startsWith("/") ? cited : "/" + cited;
        List<String> hits = new ArrayList<>();
        for (String f : unit.files()) {
            if (("/" + f).endsWith(suffix)) {
                hits.add(f);
            }
        }
        if (hits.size() == 1) {
            return hits.get(0);
        }
        // "com.example.Foo" or "Foo" style citations
        String asPath = cited.replace('.', '/');
        for (String f : unit.files()) {
            if (f.endsWith("/" + asPath + ".java") || f.endsWith("/" + cited + ".java")) {
                return f;
            }
        }
        return null;
    }

    private static List<String[]> contextTexts(ScanUnit unit) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[] {MANIFEST_FILE, unit.manifestSlice()});
        if (unit.resources() != null) {
            for (String line : unit.resources().split("\n")) {
                int colon = line.indexOf(':');
                String file = colon > 0 && !line.startsWith("network_security_config") ? line.substring(0, colon).split(" ")[0] : "resources";
                out.add(new String[] {file, line});
            }
        }
        return out;
    }
}
