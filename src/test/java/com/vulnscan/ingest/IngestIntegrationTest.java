package com.vulnscan.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.vulnscan.common.Files2;
import com.vulnscan.config.ConfigLoader;
import com.vulnscan.config.LoadedConfig;
import jakarta.validation.Validation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Opt-in: ingests the real decompiled app and writes target/app_model.json for inspection. */
@Tag("integration")
class IngestIntegrationTest {

    @Test
    void ingestsRealApp() {
        Path apk = Path.of("apps/InsecureShop.apk");
        Assumptions.assumeTrue(Files.isRegularFile(apk), "APK not downloaded");
        LoadedConfig cfg = new ConfigLoader(Validation.buildDefaultValidatorFactory().getValidator(), Path.of(""))
                .load(Path.of("configs/insecureshop.yaml"));
        var ing = cfg.app().ingest();
        JadxDecompiler d = new JadxDecompiler(Path.of(".tools/jadx/bin/jadx"), true, Duration.ofSeconds(900), "-Xmx3g",
                Path.of("cache"), new SystemProcessRunner(), new LayoutDetector());
        DecompiledApp app = d.decompile(apk);
        Ingestor ingestor = new Ingestor(ing, new ManifestParser(), new ResourceExtractor(ing.stringsFilter().namePatterns(),
                new SecretPatterns(ing.stringsFilter().extraSecretRegexes()), ing.stringsFilter().minEntropy(),
                ing.stringsFilter().minLength()), new DependencyInventory());
        AppModel a = ingestor.ingest(app);
        AppModel b = ingestor.ingest(app);
        assertThat(a.stats().ingestHash()).isEqualTo(b.stats().ingestHash());
        Files2.writeJson(Path.of("target/app_model.json"), a);
    }
}
