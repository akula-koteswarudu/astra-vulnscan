package com.vulnscan.pipeline;

import com.vulnscan.ingest.AppModel;
import com.vulnscan.ingest.CodeBase;
import com.vulnscan.ingest.DecompiledApp;
import com.vulnscan.ingest.Ingestor;
import com.vulnscan.ingest.InputResolver;
import java.nio.file.Path;

/** Decompile (or accept a decompiled directory) and ingest. */
public class AppPreparer {

    private final InputResolver inputResolver;
    private final Ingestor ingestor;

    public AppPreparer(InputResolver inputResolver, Ingestor ingestor) {
        this.inputResolver = inputResolver;
        this.ingestor = ingestor;
    }

    public PreparedApp prepare(Path input, String expectedSha256) {
        DecompiledApp app = inputResolver.resolve(input, expectedSha256);
        AppModel model = ingestor.ingest(app);
        return new PreparedApp(app, model, new CodeBase(app.layout().root()));
    }
}
