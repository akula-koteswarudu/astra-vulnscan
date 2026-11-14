package com.vulnscan.scan;

import java.util.List;

/**
 * Outcome of scanning one unit. status: ok | parse_failed | refused | truncated | failed.
 * exchanges: every model call made for this unit (scan, retry with more tokens, repair), saved as raw/&lt;unit&gt;.json.
 * subUnits: when a truncated unit was re-planned into smaller units, their results (findings are already merged here).
 */
public record ScanResult(String unitId, String status, List<Finding> findings, int badFindings, long inputTokens,
                         long outputTokens, boolean anyCached, String refusalCategory, List<String> errors,
                         List<Exchange> exchanges, List<ScanResult> subUnits) {

    public record Exchange(String purpose, int maxTokens, String stopReason, String modelUsed, long inputTokens,
                           long outputTokens, boolean cached, String text) {
    }
}
