package com.vulnscan.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JadxDecompilerTest {

    @TempDir
    Path dir;

    /** Fake jadx: "--version" prints a version; a decompile call writes output according to the scenario. */
    static class FakeJadx implements ProcessRunner {
        enum Mode { OK, PARTIAL, TIMEOUT, FAIL_NO_OUTPUT }

        final Mode mode;
        final List<List<String>> calls = new ArrayList<>();
        Map<String, String> lastEnv;

        FakeJadx(Mode mode) {
            this.mode = mode;
        }

        @Override
        public Result run(List<String> command, Map<String, String> extraEnv, Path workDir, Duration timeout) {
            calls.add(command);
            if (command.contains("--version")) {
                return new Result(0, "1.5.6\n", false);
            }
            lastEnv = extraEnv;
            Path out = Path.of(command.get(command.indexOf("-d") + 1));
            try {
                if (mode != Mode.FAIL_NO_OUTPUT) {
                    Files.createDirectories(out.resolve("sources/com/example"));
                    Files.createDirectories(out.resolve("resources"));
                    Files.writeString(out.resolve("resources/AndroidManifest.xml"), "<manifest package=\"com.example\"/>");
                    Files.writeString(out.resolve("sources/com/example/A.java"),
                            "class A {\n /* Code decompiled incorrectly, please refer to instructions dump. */\n"
                                    + " /* JADX WARN: x */ /* JADX WARN: y */\n}\n");
                    Files.writeString(out.resolve("sources/com/example/B.java"), "class B { /* Method dump skipped */ }\n");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return switch (mode) {
                case OK -> new Result(0, "done", false);
                case PARTIAL -> new Result(3, "ERROR - finished with errors, count: 11", false);
                case TIMEOUT -> new Result(-1, "", true);
                case FAIL_NO_OUTPUT -> new Result(1, "boom", false);
            };
        }

        long decompileCalls() {
            return calls.stream().filter(c -> !c.contains("--version")).count();
        }
    }

    private Path apk() throws IOException {
        Path apk = dir.resolve("app.apk");
        Files.writeString(apk, "fake apk bytes");
        return apk;
    }

    private JadxDecompiler decompiler(FakeJadx fake) {
        return new JadxDecompiler(Path.of("jadx"), true, Duration.ofSeconds(5), "-Xmx3g", dir.resolve("cache"), fake, new LayoutDetector());
    }

    @Test
    void decompilesOnceThenHitsTheCache() throws IOException {
        FakeJadx fake = new FakeJadx(FakeJadx.Mode.OK);
        Path apk = apk();
        DecompiledApp first = decompiler(fake).decompile(apk);
        DecompiledApp second = decompiler(fake).decompile(apk);

        assertThat(first.cacheHit()).isFalse();
        assertThat(second.cacheHit()).isTrue();
        assertThat(fake.decompileCalls()).isEqualTo(1);
        assertThat(second.stats()).isEqualTo(first.stats());
        assertThat(first.toolVersion()).isEqualTo("1.5.6");
        assertThat(first.layout().kind()).isEqualTo(AppLayout.Kind.JADX);
        assertThat(fake.lastEnv).containsEntry("JAVA_OPTS", "-Xmx3g");
        assertThat(fake.calls.get(1)).contains("--show-bad-code");
    }

    @Test
    void countsFailureAndWarningMarkers() throws IOException {
        DecompiledApp app = decompiler(new FakeJadx(FakeJadx.Mode.OK)).decompile(apk());
        assertThat(app.stats().methodsFailed()).isEqualTo(2);
        assertThat(app.stats().warnings()).isEqualTo(2);
        assertThat(app.stats().javaFiles()).isEqualTo(2);
    }

    @Test
    void nonZeroExitWithOutputIsPartialSuccess() throws IOException {
        DecompiledApp app = decompiler(new FakeJadx(FakeJadx.Mode.PARTIAL)).decompile(apk());
        assertThat(app.stats().partial()).isTrue();
        assertThat(app.stats().exitCode()).isEqualTo(3);
        assertThat(Files.isDirectory(dir.resolve("cache/decompiled").resolve(app.appId()))).isTrue();
    }

    @Test
    void timeoutLeavesNoCacheEntry() throws IOException {
        Path apk = apk();
        assertThatThrownBy(() -> decompiler(new FakeJadx(FakeJadx.Mode.TIMEOUT)).decompile(apk))
                .isInstanceOf(DecompileException.class).hasMessageContaining("timed out");
        try (var list = Files.list(dir.resolve("cache/decompiled"))) {
            assertThat(list.toList()).isEmpty();
        }
    }

    @Test
    void failureWithoutOutputLeavesNoCacheEntry() throws IOException {
        Path apk = apk();
        assertThatThrownBy(() -> decompiler(new FakeJadx(FakeJadx.Mode.FAIL_NO_OUTPUT)).decompile(apk))
                .hasMessageContaining("no usable output");
        try (var list = Files.list(dir.resolve("cache/decompiled"))) {
            assertThat(list.toList()).isEmpty();
        }
    }

    @Test
    void directoryInputSkipsDecompilerAndGetsStableId() throws IOException {
        FakeJadx fake = new FakeJadx(FakeJadx.Mode.OK);
        DecompiledApp fromApk = decompiler(fake).decompile(apk());
        Path decompiledDir = fromApk.layout().root();
        InputResolver resolver = new InputResolver(decompiler(fake), new LayoutDetector());

        DecompiledApp a = resolver.resolve(decompiledDir, null);
        DecompiledApp b = resolver.resolve(decompiledDir, null);
        assertThat(a.appId()).isEqualTo(b.appId()).hasSize(64);
        assertThat(a.tool()).isEqualTo("directory");
        assertThat(a.stats().methodsFailed()).isEqualTo(2);
        assertThat(fake.decompileCalls()).isEqualTo(1);
    }

    @Test
    void apkShaMismatchIsRejected() throws IOException {
        Path apk = apk();
        InputResolver resolver = new InputResolver(decompiler(new FakeJadx(FakeJadx.Mode.OK)), new LayoutDetector());
        assertThatThrownBy(() -> resolver.resolve(apk, "00ff")).hasMessageContaining("sha256 mismatch");
    }

    @Test
    void missingJadxIsAOneLineHint() {
        ProcessRunner missing = (cmd, env, wd, t) -> { throw new java.io.UncheckedIOException("cannot run " + cmd.get(0), new IOException("no such file")); };
        JadxDecompiler d = new JadxDecompiler(Path.of(".tools/jadx/bin/jadx"), true, Duration.ofSeconds(5), "", dir.resolve("cache"), missing, new LayoutDetector());
        org.assertj.core.api.Assertions.assertThatThrownBy(d::version).isInstanceOf(DecompileException.class).hasMessageContaining("bootstrap.sh");
    }
}
