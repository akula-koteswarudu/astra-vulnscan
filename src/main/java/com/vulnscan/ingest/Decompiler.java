package com.vulnscan.ingest;

import java.nio.file.Path;

/** Port: turns an APK into a decompiled directory. */
public interface Decompiler {
    DecompiledApp decompile(Path apk);
}
