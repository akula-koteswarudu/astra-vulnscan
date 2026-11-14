package com.vulnscan.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LayoutDetectorTest {

    @TempDir
    Path dir;
    final LayoutDetector detector = new LayoutDetector();

    private void touch(String rel) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
    }

    @Test
    void jadxLayout() throws IOException {
        touch("resources/AndroidManifest.xml");
        touch("sources/com/a/A.java");
        AppLayout l = detector.detect(dir);
        assertThat(l.kind()).isEqualTo(AppLayout.Kind.JADX);
        assertThat(l.sourceRoots()).containsExactly(dir.resolve("sources"));
        assertThat(l.resourcesRoot()).isEqualTo(dir.resolve("resources"));
    }

    @Test
    void jadxGradleExport() throws IOException {
        touch("app/src/main/AndroidManifest.xml");
        touch("app/src/main/java/com/a/A.java");
        touch("app/src/main/res/values/strings.xml");
        AppLayout l = detector.detect(dir);
        assertThat(l.kind()).isEqualTo(AppLayout.Kind.JADX_GRADLE);
        assertThat(l.sourceRoots()).containsExactly(dir.resolve("app/src/main/java"));
    }

    @Test
    void apktoolLayout() throws IOException {
        touch("AndroidManifest.xml");
        touch("smali/com/a/A.smali");
        touch("smali_classes2/com/b/B.smali");
        AppLayout l = detector.detect(dir);
        assertThat(l.kind()).isEqualTo(AppLayout.Kind.APKTOOL);
        assertThat(l.sourceRoots()).containsExactly(dir.resolve("smali"), dir.resolve("smali_classes2"));
    }

    @Test
    void missingManifestIsNamed() throws IOException {
        touch("sources/A.java");
        assertThatThrownBy(() -> detector.detect(dir)).hasMessageContaining("no AndroidManifest.xml");
    }

    @Test
    void missingSourcesIsNamed() throws IOException {
        touch("AndroidManifest.xml");
        assertThatThrownBy(() -> detector.detect(dir)).hasMessageContaining("no source folder");
    }
}
