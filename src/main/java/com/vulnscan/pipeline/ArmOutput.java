package com.vulnscan.pipeline;

import com.vulnscan.scan.Finding;
import java.util.List;

/** 04_scan/&lt;arm&gt;/run_&lt;k&gt;/findings.json */
public record ArmOutput(int schemaVersion, String arm, int run, String model, List<Finding> findings) {
}
