package com.vulnscan.planning;

import java.util.List;

/**
 * One request's worth of app: files (relative paths) plus the context text shown with them.
 * unitId = sha256 over member file hashes and the context, so it stays stable when an unrelated file changes.
 * kind: whole (entire app), component (an entry point + one-hop neighbours), package (files no entry point reaches),
 * app_context (manifest, resources and dependency inventory without code; chunked mode only).
 */
public record ScanUnit(String unitId, String kind, String root, List<String> files, String manifestSlice,
                       String resources, String dependencies, int estTokens, int priority) {
}
