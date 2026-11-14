package com.vulnscan.planning;

import java.util.List;

/** The units for one model plus how the budget was computed and what was left out (all reported). */
public record UnitPlan(int schemaVersion, String model, String mode, Budget budget, List<ScanUnit> units,
                       List<Skipped> skipped) {

    /**
     * perRequestCap = min(planningBudget or context, 0.8 * itpm, 0.8 * tpm - maxOutput)  (terms only when the limit exists;
     *                 tpm counts output too, so the answer's room comes out of it)
     * effective     = perRequestCap - promptOverhead - reservedGuidance (student only).
     */
    public record Budget(int contextOrPlanningBudget, Integer itpm, Integer tpm, int maxOutput, int perRequestCap,
                         int promptOverhead, int reservedGuidance, int effective) {
    }

    public record Skipped(String what, int estTokens, String reason) {
    }
}
