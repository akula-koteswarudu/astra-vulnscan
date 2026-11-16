package com.vulnscan.ingest;

import com.vulnscan.common.Files2;
import com.vulnscan.common.Hashing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decompiles with jadx into {@code <cacheDir>/decompiled/<apk sha256>/}.
 * Output goes to a temp directory first and is renamed into place only on (partial) success, so a timeout
 * or crash never leaves a half-written cache entry.
 */
public class JadxDecompiler implements Decompiler {

    private static final Logger log = LoggerFactory.getLogger(JadxDecompiler.class);
    static final String STATS_FILE = "vulnscan-decompile-stats.json";

    private final Path jadx;
    private final boolean showBadCode;
    private final Duration timeout;
    private final String javaOpts;
    private final Path cacheRoot;
    private final ProcessRunner runner;
    private final LayoutDetector layoutDetector;

    public JadxDecompiler(Path jadx, boolean showBadCode, Duration timeout, String javaOpts, Path cacheDir,
                          ProcessRunner runner, LayoutDetector layoutDetector) {
        this.jadx = jadx;
        this.showBadCode = showBadCode;
        this.timeout = timeout;
        this.javaOpts = javaOpts;
        this.cacheRoot = cacheDir.resolve("decompiled");
        this.runner = runner;
        this.layoutDetector = layoutDetector;
    }

    @Override
    public DecompiledApp decompile(Path apk) {
        if (!Files.isRegularFile(apk)) {
            throw new DecompileException("APK not found: " + apk);
        }
        String sha = Hashing.sha256(apk);
        Path target = cacheRoot.resolve(sha);
        Path statsFile = target.resolve(STATS_FILE);
        if (Files.isRegularFile(statsFile)) {
            log.info("decompile cache hit: {}", target);
            CachedStats cached = Files2.readJson(statsFile, CachedStats.class);
            return new DecompiledApp(sha, layoutDetector.detect(target), "jadx", cached.toolVersion(), cached.stats(), true);
        }

        String version = version();
        Path tmp = cacheRoot.resolve(sha + ".tmp-" + UUID.randomUUID());
        List<String> cmd = new ArrayList<>(List.of(jadx.toString(), "-d", tmp.toString()));
        if (showBadCode) {
            cmd.add("--show-bad-code");
        }
        cmd.add(apk.toAbsolutePath().toString());
        Map<String, String> env = javaOpts == null || javaOpts.isBlank() ? Map.of() : Map.of("JAVA_OPTS", javaOpts);

        log.info("decompiling {} with jadx {} (timeout {}s)", apk.getFileName(), version, timeout.toSeconds());
        long start = System.currentTimeMillis();
        try {
            Files.createDirectories(cacheRoot);
            ProcessRunner.Result result = runner.run(cmd, env, null, timeout);
            long duration = System.currentTimeMillis() - start;
            if (result.timedOut()) {
                throw new DecompileException("jadx timed out after " + timeout.toSeconds() + "s (decompile.timeout_s)");
            }
            boolean hasOutput = Files.isDirectory(tmp.resolve("sources")) && Files.isRegularFile(tmp.resolve("resources/AndroidManifest.xml"));
            if (!hasOutput) {
                throw new DecompileException("jadx failed (exit " + result.exitCode() + ") with no usable output:\n" + tail(result.output()));
            }
            boolean partial = result.exitCode() != 0;
            if (partial) {
                log.warn("jadx exited with {} but produced output: treating as partial success", result.exitCode());
            }
            DecompileStats stats = countMarkers(tmp, result.exitCode(), partial, duration);
            Files2.writeJson(tmp.resolve(STATS_FILE), new CachedStats(version, stats));
            if (Files.exists(target)) {
                // a cache hit returned earlier, so this is a leftover without stats (e.g. an interrupted copy): replace it
                Files2.deleteRecursively(target);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            return new DecompiledApp(sha, layoutDetector.detect(target), "jadx", version, stats, false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            Files2.deleteRecursively(tmp); // no-op after a successful rename
        }
    }

    /** jadx version string, e.g. "1.5.6". */
    public String version() {
        ProcessRunner.Result r;
        try {
            r = runner.run(List.of(jadx.toString(), "--version"), Map.of(), null, Duration.ofSeconds(60));
        } catch (UncheckedIOException e) {
            throw new DecompileException("jadx not found at " + jadx + ": run `bash scripts/bootstrap.sh` first (installs it into .tools/), "
                    + "or use the Docker image, which includes it");
        }
        if (r.exitCode() != 0) {
            throw new DecompileException("cannot run jadx at " + jadx + " (decompile.jadx_path): " + tail(r.output()));
        }
        String[] lines = r.output().strip().split("\n");
        return lines[lines.length - 1].strip();
    }

    /** Counts jadx failure and warning markers in the decompiled sources. */
    static DecompileStats countMarkers(Path dir, int exitCode, boolean partial, long durationMs) {
        int failed = 0;
        int warnings = 0;
        int javaFiles = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".java")).sorted()::iterator) {
                javaFiles++;
                String text = Files2.read(p);
                failed += count(text, "Code decompiled incorrectly") + count(text, "Method dump skipped");
                warnings += count(text, "JADX WARN");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new DecompileStats(exitCode, partial, failed, warnings, javaFiles, durationMs);
    }

    static int count(String text, String marker) {
        int n = 0;
        for (int i = text.indexOf(marker); i >= 0; i = text.indexOf(marker, i + marker.length())) {
            n++;
        }
        return n;
    }

    private static String tail(String output) {
        String[] lines = output.split("\n");
        int from = Math.max(0, lines.length - 15);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    /** What is stored next to the cached output. */
    public record CachedStats(String toolVersion, DecompileStats stats) {
    }
}
