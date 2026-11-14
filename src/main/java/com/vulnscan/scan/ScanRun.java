package com.vulnscan.scan;

/** Which arm/run/repeat a scan belongs to (provenance and cache key repeat index). */
public record ScanRun(String arm, String runId, int repeatIndex) {
}
