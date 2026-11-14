package com.vulnscan.pipeline;

import com.vulnscan.scan.ScanResult;
import java.util.List;

/** One row of 04_scan/&lt;arm&gt;/run_&lt;k&gt;/results.json (findings and raw texts are saved separately). */
public record UnitSummary(String unitId, String status, int findings, int badFindings, long inputTokens, long outputTokens,
                          boolean cached, String refusalCategory, List<String> errors, List<UnitSummary> subUnits) {

    public static UnitSummary of(ScanResult r) {
        return new UnitSummary(r.unitId(), r.status(), r.findings().size(), r.badFindings(), r.inputTokens(), r.outputTokens(),
                r.anyCached(), r.refusalCategory(), r.errors(), r.subUnits().stream().map(UnitSummary::of).toList());
    }
}
