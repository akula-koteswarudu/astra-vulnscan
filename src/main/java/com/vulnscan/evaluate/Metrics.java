package com.vulnscan.evaluate;

import java.util.List;
import java.util.Map;

/**
 * 07_evaluate/metrics.json. label is "recall" against ground truth, or "agreement with teacher" when the teacher's
 * findings are the reference (then the teacher ceiling and precision are omitted).
 */
public record Metrics(int schemaVersion, String headlineMode, String label, boolean groundTruth, List<Mode> modes, Lift lift) {

    /** One reference mode: readme | enriched | teacher_reference. */
    public record Mode(String mode, String label, int items, List<ArmRun> armRuns, List<ItemRow> perItem) {
    }

    /**
     * Recall = matched items / items (one-to-one). Headline: verified findings, strict. Precision = findings matching
     * any item (many-to-one) / findings, over verified findings; null in teacher-as-reference mode.
     */
    public record ArmRun(String arm, int run, String model, int findingsTotal, int findingsVerified,
                         double recallStrict, double recallLenient, double recallStrictInclUnverified,
                         Double precisionStrict, Double precisionLenient,
                         Map<String, String> matchedStrict, Map<String, String> matchedLenient,
                         Map<String, Long> unitStatus, int judgeCalls, int judgeErrors, int prefilterDropped) {
    }

    /** Per item and arm: run hit counts (k of N), strict and lenient. */
    public record ItemRow(String itemId, String title, String staticVisibility, Map<String, String> hitsStrict,
                          Map<String, String> hitsLenient) {
    }

    /** Mean (and range) of the headline recall per arm; lift = with lessons minus baseline. Small N, not a significance test. */
    public record Lift(String mode, Map<String, Stat> recallByArm, Double lift) {
    }

    public record Stat(double mean, double min, double max, int runs) {
    }
}
