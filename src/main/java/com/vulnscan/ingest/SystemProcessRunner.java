package com.vulnscan.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a command with an argument list (never through a shell), merges stdout and stderr into a temp log
 * file (so a chatty tool cannot block on a full pipe), and kills the process tree on timeout.
 */
public class SystemProcessRunner implements ProcessRunner {

    @Override
    public Result run(List<String> command, Map<String, String> extraEnv, Path workDir, Duration timeout) {
        Path log = null;
        try {
            log = Files.createTempFile("vulnscan-proc-", ".log");
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            if (workDir != null) {
                pb.directory(workDir.toFile());
            }
            pb.environment().putAll(extraEnv);
            Process process = pb.start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                return new Result(-1, Files.readString(log, StandardCharsets.UTF_8), true);
            }
            return new Result(process.exitValue(), Files.readString(log, StandardCharsets.UTF_8), false);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot run " + command.get(0), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + command.get(0), e);
        } finally {
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // temp file; nothing else to do
                }
            }
        }
    }
}
