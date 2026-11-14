package com.vulnscan.ingest;

import com.vulnscan.common.Files2;
import com.vulnscan.common.Hashing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Accepts the configured input: an .apk (sha256 verified when configured, then decompiled) or an
 * already-decompiled directory (layout detected; app_id = sha256 over sorted (relative path, file sha256)).
 */
public class InputResolver {

    private final Decompiler decompiler;
    private final LayoutDetector layoutDetector;

    public InputResolver(Decompiler decompiler, LayoutDetector layoutDetector) {
        this.decompiler = decompiler;
        this.layoutDetector = layoutDetector;
    }

    public DecompiledApp resolve(Path input, String expectedSha256) {
        if (Files.isDirectory(input)) {
            AppLayout layout = layoutDetector.detect(input);
            return new DecompiledApp(directoryId(input), layout, "directory", null, directoryStats(layout), true);
        }
        if (!Files.isRegularFile(input)) {
            throw new DecompileException("input not found: " + input + " (app.input). For an APK, download it into apps/ first (see README).");
        }
        if (expectedSha256 != null && !expectedSha256.isBlank()) {
            String actual = Hashing.sha256(input);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new DecompileException("sha256 mismatch for " + input + ": expected " + expectedSha256 + ", got " + actual
                        + " (app.input_sha256)");
            }
        }
        return decompiler.decompile(input);
    }

    static String directoryId(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            StringBuilder sb = new StringBuilder();
            for (Path f : files) {
                sb.append(dir.relativize(f).toString().replace('\\', '/')).append('\0').append(Hashing.sha256(f)).append('\n');
            }
            return Hashing.sha256(sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DecompileStats directoryStats(AppLayout layout) {
        int failed = 0;
        int warnings = 0;
        int files = 0;
        for (Path root : layout.sourceRoots()) {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".java")).sorted()::iterator) {
                    files++;
                    String text = Files2.read(p);
                    failed += JadxDecompiler.count(text, "Code decompiled incorrectly") + JadxDecompiler.count(text, "Method dump skipped");
                    warnings += JadxDecompiler.count(text, "JADX WARN");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return DecompileStats.forDirectoryInput(failed, warnings, files);
    }
}
