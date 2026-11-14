package com.vulnscan.scan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * Our finding: one location per finding (after splitting), with the evidence check result and provenance.
 * evidenceStatus: verified_exact | verified_fuzzy | relocated | unverified | inventory_match | inventory_unmatched.
 */
public record Finding(String id, String title, String vulnClass, String cwe, String owaspCategory, String severity,
                      double confidence, Location location, String evidence, String rationale, String kind,
                      String evidenceStatus, boolean needsAdvisoryCheck, Provenance provenance) {

    public record Location(String file, String component, String symbol, Integer line, Integer endLine) {
    }

    public record Provenance(String modelUsed, String arm, String runId, int repeatIndex, List<String> unitIds,
                             String promptVersion) {
    }

    public static final List<String> VERIFIED = List.of("verified_exact", "verified_fuzzy", "relocated", "inventory_match");

    /** Counts toward the headline (verified-only) metrics. Not a stored field (derived from evidenceStatus). */
    @JsonIgnore
    public boolean isVerified() {
        return VERIFIED.contains(evidenceStatus);
    }
}
