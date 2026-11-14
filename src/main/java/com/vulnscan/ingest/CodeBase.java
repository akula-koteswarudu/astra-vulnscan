package com.vulnscan.ingest;

import com.vulnscan.common.Files2;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reads file text on demand from the decompiled root (AppModel stores paths, not text). */
public class CodeBase {

    private final Path root;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public CodeBase(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public String text(String relativePath) {
        return cache.computeIfAbsent(relativePath, p -> Files2.read(root.resolve(p)));
    }
}
