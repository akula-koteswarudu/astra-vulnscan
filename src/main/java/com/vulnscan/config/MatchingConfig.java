package com.vulnscan.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/** configs/matching.yaml: CWE groups, structured score weights and thresholds. */
public record MatchingConfig(@NotNull Map<String, List<String>> cweGroups,
                             @NotNull @Valid Weights weights,
                             @NotNull @Valid LocationScores locationScores,
                             @NotNull @Valid CweScores cweScores,
                             @DecimalMin("0.0") @DecimalMax("1.0") double prefilterMinScore) {

    public record Weights(@DecimalMin("0.0") double location, @DecimalMin("0.0") double cwe) {
    }

    public record LocationScores(double method, @com.fasterxml.jackson.annotation.JsonProperty("class") double clazz, double component, double file) {
    }

    public record CweScores(double accepted, double sameGroup, double sameOwasp) {
    }
}
