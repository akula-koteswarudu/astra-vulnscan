package com.vulnscan.scan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** A finding as the model wrote it (validated per finding; extra fields from the model are ignored). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RawFinding(String title, String vulnClass, String cwe, String owaspCategory, String severity,
                         Double confidence, List<RawLocation> locations, String evidence, String rationale, String kind) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawLocation(String file, String component, String symbol) {
    }
}
