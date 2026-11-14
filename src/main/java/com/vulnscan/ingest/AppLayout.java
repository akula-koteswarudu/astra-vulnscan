package com.vulnscan.ingest;

import java.nio.file.Path;
import java.util.List;

/**
 * Where things are inside a decompiled directory. This is the anti-corruption layer between a tool's folder
 * structure (jadx, jadx Gradle export, apktool) and the rest of the ingest code.
 */
public record AppLayout(Kind kind, Path root, Path manifest, List<Path> sourceRoots, Path resourcesRoot) {

    public enum Kind { JADX, JADX_GRADLE, APKTOOL }
}
