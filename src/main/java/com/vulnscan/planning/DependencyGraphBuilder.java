package com.vulnscan.planning;

import com.vulnscan.ingest.AppModel.SourceFile;
import com.vulnscan.ingest.CodeBase;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Port: class-level "file A references file B" edges between first-party files (keys and values are file paths). */
public interface DependencyGraphBuilder {
    Map<String, Set<String>> build(List<SourceFile> files, CodeBase code);
}
