package com.vulnscan.planning;

import com.vulnscan.ingest.AppModel.SourceFile;
import com.vulnscan.ingest.CodeBase;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Documented placeholder (rejected by the config validator). The better alternative to the text graph is a
 * bytecode-based, method-level call graph (androguard in Python; on the JVM: SootUp or a dex parser). Future work.
 */
public class AndroguardGraphBuilder implements DependencyGraphBuilder {
    @Override
    public Map<String, Set<String>> build(List<SourceFile> files, CodeBase code) {
        throw new UnsupportedOperationException("bytecode call graph not built in v0.1; use graph_builder: text_reference");
    }
}
