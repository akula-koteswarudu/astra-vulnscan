package com.vulnscan.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Opt-in: needs .tools/jadx and apps/InsecureShop.apk. Run with -Dgroups=integration -Dtest.excludedGroups=none. */
@Tag("integration")
class JadxIntegrationTest {

    @Test
    void decompilesRealApkOnceThenHitsCache() {
        Path apk = Path.of("apps/InsecureShop.apk");
        Assumptions.assumeTrue(Files.isRegularFile(apk), "APK not downloaded");
        JadxDecompiler d = new JadxDecompiler(Path.of(".tools/jadx/bin/jadx"), true, Duration.ofSeconds(900), "-Xmx3g",
                Path.of("cache"), new SystemProcessRunner(), new LayoutDetector());
        DecompiledApp first = d.decompile(apk);
        DecompiledApp second = d.decompile(apk);
        assertThat(second.cacheHit()).isTrue();
        assertThat(second.stats()).isEqualTo(first.stats());
        assertThat(first.stats().javaFiles()).isGreaterThan(2000);
        System.out.println("first cacheHit=" + first.cacheHit() + " stats=" + first.stats());
    }
}
