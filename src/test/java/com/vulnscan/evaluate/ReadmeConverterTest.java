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
    void failsOnMalformedLine() {
        String readme = "## Vulnerabilities\n\n1. **A:** first\n2. B without bold title\n";
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
        assertThatThrownBy(() -> converter.convert("# Nothing here\n", "x")).hasMessageContaining("no 'Vulnerabilities'");
    }
}
