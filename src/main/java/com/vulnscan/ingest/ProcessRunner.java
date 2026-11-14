package com.vulnscan.ingest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Port for running an external tool. Lets tests fake jadx without a subprocess. */
public interface ProcessRunner {

    Result run(List<String> command, Map<String, String> extraEnv, Path workDir, Duration timeout);

    record Result(int exitCode, String output, boolean timedOut) {
    }
}
