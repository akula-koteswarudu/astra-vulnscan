package com.vulnscan.pipeline;

import com.vulnscan.ingest.AppModel;
import com.vulnscan.ingest.CodeBase;
import com.vulnscan.ingest.DecompiledApp;

/** Result of decompile + ingest. */
public record PreparedApp(DecompiledApp decompiled, AppModel model, CodeBase code) {
}
