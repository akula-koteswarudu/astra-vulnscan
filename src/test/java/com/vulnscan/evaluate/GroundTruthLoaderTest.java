package com.vulnscan.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GroundTruthLoaderTest {

    @Test
    void realGroundTruthValidatesAndKeepsReadmeLayerVerbatim() throws IOException {
        GroundTruth gt = new GroundTruthLoader().load(Path.of("ground_truth/insecureshop.yaml"));
        assertThat(gt.items()).hasSize(19).allSatisfy(i -> assertThat(i.enrichment()).isNotNull());
        GroundTruth readme = new ReadmeConverter().convert(Files.readString(Path.of("ground_truth/insecureshop.README.md")), "x");
        for (int i = 0; i < 19; i++) {
            assertThat(gt.items().get(i).title()).isEqualTo(readme.items().get(i).title());
            assertThat(gt.items().get(i).description()).isEqualTo(readme.items().get(i).description());
        }
    }

    @Test
    void minimalHandWrittenFormatLoads(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("gt.yaml");
        Files.writeString(f, "schema_version: 1\nsource: x\nitems:\n- {id: A, title: T, description: D}\n");
        assertThat(new GroundTruthLoader().load(f).items()).hasSize(1);
    }

    @Test
    void rejectsBadEnrichment() {
        var bad = new GroundTruth(1, "x", null, List.of(
                new GroundTruth.Item("A", "t", "d", new GroundTruth.Enrichment(List.of("798"), "weird", List.of(
                        new GroundTruth.Location(null, null, "m", null)), "sometimes", null)),
                new GroundTruth.Item("A", "t", "", null)));
        assertThat(GroundTruthLoader.validate(bad)).hasSize(6);
    }

    @Test
    void unknownKeysAreRejected(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("gt.yaml");
        Files.writeString(f, "schema_version: 1\nsource: x\nitems:\n- {id: A, title: T, description: D, severity: high}\n");
        assertThatThrownBy(() -> new GroundTruthLoader().load(f)).hasMessageContaining("severity");
    }
}
