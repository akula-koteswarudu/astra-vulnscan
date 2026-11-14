package com.vulnscan.evaluate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * ground_truth/&lt;app&gt;.yaml. Required per item: id, title, description (verbatim from the source).
 * enrichment is optional and is the author's own labelling, written before any scan.
 */
public record GroundTruth(int schemaVersion, String source, String enrichmentNote, List<Item> items) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String id, String title, String description, Enrichment enrichment) {
    }

    /** kind: code | manifest | resource | dependency. staticVisibility: full | partial | inventory_only. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Enrichment(List<String> acceptedCwes, String kind, List<Location> locations, String staticVisibility,
                             String note) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Location(String component, @JsonProperty("class") String clazz, String method, String file) {
    }
}
