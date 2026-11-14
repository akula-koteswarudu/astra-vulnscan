package com.vulnscan.ingest;

import java.nio.file.Path;

/** Result of decompiling (or of accepting an already-decompiled directory). */
public record DecompiledApp(String appId, AppLayout layout, String tool, String toolVersion, DecompileStats stats,
                            boolean cacheHit) {
}
