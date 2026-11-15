package com.vulnscan.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * Per-app configuration (configs/&lt;app&gt;.yaml), merged over the built-in defaults
 * (src/main/resources/config-defaults.yaml) and the profile defaults by {@link ConfigLoader}.
 */
public record AppConfig(
        @NotNull Profile profile,
        @NotBlank String modelsFile,
        RoleOverrides roles,
        @NotBlank String matchingFile,
        @NotNull @Valid App app,
        @NotNull @Valid Decompile decompile,
        @NotNull @Valid Ingest ingest,
        @NotNull @Valid Planning planning,
        @NotNull @Valid Scan scan,
        @NotNull @Valid Distill distill,
        @NotNull @Valid Runs runs,
        @NotNull @Valid Arms arms,
        @NotNull @Valid Evaluate evaluate,
        @NotNull @Valid Cache cache,
        @NotNull @Valid Output output) {

    /** Optional: which model in models.yaml fills a role for this app/experiment (null = the models file's roles). */
    public record RoleOverrides(String teacher, String student, String judge) {
    }

    public enum Profile {
        @JsonProperty("assignment") ASSIGNMENT,
        @JsonProperty("full") FULL
    }

    /** input: repo-relative .apk or decompiled directory. groundTruth is optional (omit for unseen apps). */
    public record App(@NotBlank String name, @NotBlank String input, String inputSha256, String groundTruth) {
    }

    public record Decompile(@NotBlank String jadxPath, boolean showBadCode, @Positive int timeoutS, String javaOpts) {
    }

    public record Ingest(@NotNull List<String> extraFirstPartyPrefixes,
                         @NotNull List<String> libraryPrefixes,
                         @NotNull List<String> excludeClassPatterns,
                         @NotNull @Valid StringsFilter stringsFilter,
                         boolean dependencyInventory) {
    }

    public record StringsFilter(@NotNull List<String> namePatterns,
                                @NotNull List<String> extraSecretRegexes,
                                @Positive double minEntropy,
                                @Positive int minLength) {
    }

    public record Planning(@NotNull PlanningMode mode,
                           @Min(1) int hopLimit,
                           @Positive int tokenEstimateCharsPerToken,
                           @DecimalMin("0.0") double tokenEstimateMargin,
                           @Min(0) int promptOverheadTokens,
                           @Min(0) int reservedGuidanceTokens,
                           @Positive Integer maxUnits,
                           @Positive int minCodeBudgetTokens,
                           @NotNull GraphBuilder graphBuilder,
                           String prioritizerSignalsFile) {
    }

    public enum PlanningMode {
        @JsonProperty("auto") AUTO,
        @JsonProperty("whole") WHOLE,
        @JsonProperty("chunked") CHUNKED
    }

    public enum GraphBuilder {
        @JsonProperty("text_reference") TEXT_REFERENCE,
        /** Placeholder only: rejected by the config validator until built. */
        @JsonProperty("androguard") ANDROGUARD
    }

    /**
     * perturbOrder: runs 2, 3, ... shuffle the order of code files and lessons (fixed seed per run, same order for both
     * student arms); run 1 keeps the original order. Measures how stable a temperature-0 result is under content-neutral
     * prompt changes.
     */
    public record Scan(@NotBlank String promptVersion, @NotNull @Valid Evidence evidence, boolean perturbOrder) {
    }

    public record Evidence(@Min(0) int fuzzyThreshold, @Min(0) int minEvidenceChars) {
    }

    public record Distill(@NotBlank String promptVersion,
                          @Positive int maxLessons,
                          @Positive int maxLessonTokens,
                          @Positive int maxInputFindings,
                          String lessonsFrom,
                          @NotNull @Valid Sanitizer sanitizer) {
    }

    public record Sanitizer(@NotNull OnViolation onViolation,
                            @DecimalMin("0.0") @DecimalMax("1.0") double exampleSimilarityMax) {
    }

    public enum OnViolation {
        @JsonProperty("redact") REDACT,
        @JsonProperty("drop") DROP,
        @JsonProperty("rewrite") REWRITE
    }

    /** Filled from the profile unless written explicitly in the app file. */
    public record Runs(@Min(1) int teacherRuns, @Min(1) int studentRuns) {
    }

    public record Arms(boolean checklistControl, boolean lessonsFocusStudentMisses) {
    }

    public record Evaluate(@NotNull Headline headline,
                           boolean countUnverified,
                           @Positive int screenBatchSize,
                           String auditFile) {
    }

    public enum Headline {
        @JsonProperty("readme") README,
        @JsonProperty("enriched") ENRICHED
    }

    public record Cache(boolean llm, @NotBlank String dir) {
    }

    public record Output(@NotBlank String runsDir) {
    }
}
