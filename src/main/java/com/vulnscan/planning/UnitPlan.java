package com.vulnscan.planning;

import java.util.List;

/** The units for one model plus how the budget was computed and what was left out (all reported). */
public record UnitPlan(int schemaVersion, String model, String mode, Budget budget, List<ScanUnit> units,
                       List<Skipped> skipped) {

    /**
     * effective = min(planningBudget or context, 0.8 * per-minute input limit) - promptOverhead
     *             - maxOutput (only when output shares the per-minute limit) - reservedGuidance (student only).
     */
    public record Budget(int contextOrPlanningBudget, Integer perMinuteInputLimit, int promptOverhead, int maxOutput,
                         boolean outputSharesLimit, int reservedGuidance, int effective) {
    }

    public record Skipped(String what, int estTokens, String reason) {
    }
}
