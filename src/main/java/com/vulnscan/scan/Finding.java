package com.vulnscan.scan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * Our finding: one primary location (after splitting), with the evidence check result and provenance.
 * evidenceStatus: verified_exact | verified_fuzzy | relocated | unverified | inventory_match | inventory_unmatched.
 * alsoAt: the locations of duplicates merged into this finding (lossless merge); empty when nothing was merged.
 */
public record Finding(String id, String title, String vulnClass, String cwe, String owaspCategory, String severity,
                      double confidence, Location location, String evidence, String rationale, String kind,
                      String evidenceStatus, boolean needsAdvisoryCheck, Provenance provenance, List<Location> alsoAt) {

    /** A finding with nothing merged into it. */
    public Finding(String id, String title, String vulnClass, String cwe, String owaspCategory, String severity,
                   double confidence, Location location, String evidence, String rationale, String kind,
                   String evidenceStatus, boolean needsAdvisoryCheck, Provenance provenance) {
        this(id, title, vulnClass, cwe, owaspCategory, severity, confidence, location, evidence, rationale, kind, evidenceStatus,
                needsAdvisoryCheck, provenance, List.of());
    }

    /** Primary location first, then the merged ones (files written before alsoAt existed read it as null). */
    @JsonIgnore
    public List<Location> allLocations() {
        List<Location> out = new java.util.ArrayList<>(List.of(location));
        if (alsoAt != null) {
            out.addAll(alsoAt);
        }
        return out;
    }

    /** The same finding seen at another of its locations (for location-based scoring). */
    public Finding atLocation(Location other) {
        return new Finding(id, title, vulnClass, cwe, owaspCategory, severity, confidence, other, evidence, rationale, kind,
                evidenceStatus, needsAdvisoryCheck, provenance, alsoAt);
    }

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
