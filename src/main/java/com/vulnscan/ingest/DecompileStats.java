package com.vulnscan.ingest;

/**
 * jadx outcome. partial = non-zero exit but output present (jadx exits non-zero when some methods fail).
 * methodsFailed counts "Code decompiled incorrectly" / "Method dump skipped" markers; warnings counts "JADX WARN".
 */
public record DecompileStats(int exitCode, boolean partial, int methodsFailed, int warnings, int javaFiles,
                             long durationMs) {

    public static DecompileStats forDirectoryInput(int methodsFailed, int warnings, int javaFiles) {
        return new DecompileStats(0, false, methodsFailed, warnings, javaFiles, 0);
    }
}
