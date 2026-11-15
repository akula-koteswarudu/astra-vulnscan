package com.vulnscan.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReadmeConverterTest {

    private final ReadmeConverter converter = new ReadmeConverter();

    @Test
    void convertsTheRealReadmeFormat() throws IOException {
        String readme = Files.readString(Path.of("ground_truth/insecureshop.README.md"));
        GroundTruth gt = converter.convert(readme, "README");
        assertThat(gt.items()).hasSize(19);
        assertThat(gt.items().get(1).id()).isEqualTo("GT-02");
        assertThat(gt.items().get(1).title()).isEqualTo("Insufficient URL Validation");
        assertThat(gt.items().get(1).description()).isEqualTo("Possible to load any arbitrary URL in webview via Deeplink.");
        assertThat(gt.items().get(18).id()).isEqualTo("GT-19");
    }

    @Test
    void convertsUntitledNumberedLists() throws IOException {
        GroundTruth gt = converter.convert(Files.readString(Path.of("ground_truth/ovaa.README.md")), "README");
        assertThat(gt.items()).hasSize(18);
        assertThat(gt.items().get(13).title()).isEqualTo("Use of the hardcoded AES key in `WeakCrypto`");
        assertThat(gt.items().get(0).description()).startsWith("Installation of an arbitrary `login_url`").endsWith("when they log in.");
    }

    @Test
    void failsOnMixedFormats() {
        assertThatThrownBy(() -> converter.convert("## Vulnerabilities\n1. **A:** a\n2. plain text\n", "x")).hasMessageContaining("mixes");
    }

    @Test
    void failsOnMalformedLine() {
        String readme = "## Vulnerabilities\n\n1. **A:** first\n- a bullet, not a numbered item\n";
        assertThatThrownBy(() -> converter.convert(readme, "x")).hasMessageContaining("line 4");
    }

    @Test
    void failsOnNumberingGap() {
        String readme = "## Vulnerabilities\n1. **A:** first\n3. **C:** third\n";
        assertThatThrownBy(() -> converter.convert(readme, "x")).hasMessageContaining("expected item 2");
    }

    @Test
    void stopsAtNextHeadingAndFailsWithoutSection() {
        GroundTruth gt = converter.convert("## Vulnerabilities\n1. **A:** a\n## Other\nfree text\n", "x");
        assertThat(gt.items()).hasSize(1);
        assertThat(converter.convert("## Vulnerabilities\n1. **A:** a\n\n-----\n*footer*\n", "x").items()).hasSize(1);
        assertThatThrownBy(() -> converter.convert("# Nothing here\n", "x")).hasMessageContaining("no 'Vulnerabilities'");
    }
}
