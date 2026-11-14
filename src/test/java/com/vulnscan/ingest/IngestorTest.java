package com.vulnscan.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.vulnscan.common.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestorTest {

    @TempDir
    Path dir;

    static Ingestor ingestor() {
        var cfg = new com.vulnscan.config.AppConfig.Ingest(List.of(), List.of("androidx.", "android.", "kotlin."),
                List.of("R", "R$*", "BuildConfig", "*.databinding.*"),
                new com.vulnscan.config.AppConfig.StringsFilter(List.of("key", "password"), List.of(), 3.5, 12), true);
        SecretPatterns secrets = new SecretPatterns(List.of());
        return new Ingestor(cfg, new ManifestParser(), new ResourceExtractor(cfg.stringsFilter().namePatterns(), secrets, 3.5, 12),
                new DependencyInventory());
    }

    /** A small jadx-layout fixture app. */
    static Path fixtureApp(Path dir) throws IOException {
        write(dir, "resources/AndroidManifest.xml", """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                  <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="30"/>
                  <application android:name=".App">
                    <activity android:name=".MainActivity" android:exported="true"/>
                    <service android:name="net.lib.upload.UploadService" android:exported="true"/>
                  </application>
                </manifest>""");
        write(dir, "resources/res/values/strings.xml", """
                <resources><string name="app_name">Example</string><string name="api_key">abc123</string></resources>""");
        write(dir, "resources/META-INF/androidx.core_core.version", "1.3.1\n");
        write(dir, "sources/com/example/app/MainActivity.java", """
                package com.example.app;

                import android.app.Activity;
                import android.os.Bundle;

                public class MainActivity extends Activity {
                    @Override // android.app.Activity
                    protected void onCreate(Bundle b) {
                        String url = "https://api.example.com/v1";
                        loadThing(url);
                    }

                    private void loadThing(String u) {
                    }
                }
                """);
        write(dir, "sources/com/example/app/R.java", "package com.example.app;\npublic final class R { }\n");
        write(dir, "sources/net/lib/upload/UploadService.java", "package net.lib.upload;\npublic class UploadService { }\n");
        write(dir, "sources/net/lib/upload/BuildConfig.java",
                "package net.lib.upload;\npublic final class BuildConfig { public static final String VERSION_NAME = \"3.2.3\"; "
                        + "public static final String LIBRARY_PACKAGE_NAME = \"net.lib.upload\"; }\n");
        write(dir, "sources/com/other/Thing.java", "package com.other;\npublic class Thing { }\n");
        return dir;
    }

    static void write(Path dir, String rel, String text) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private AppModel ingest() throws IOException {
        fixtureApp(dir);
        DecompiledApp app = new DecompiledApp("id1", new LayoutDetector().detect(dir), "jadx", "1.5.6",
                DecompileStats.forDirectoryInput(0, 0, 5), false);
        return ingestor().ingest(app);
    }

    @Test
    void buildsFirstPartySplitAndComponents() throws IOException {
        AppModel m = ingest();
        assertThat(m.packageName()).isEqualTo("com.example.app");
        assertThat(m.firstPartySources()).extracting(AppModel.SourceFile::className).containsExactly("com.example.app.MainActivity");
        assertThat(m.stats().filesTotal()).isEqualTo(5);
        assertThat(m.stats().filesGeneratedExcluded()).isEqualTo(1);
        assertThat(m.stats().firstPartyPrefixes()).containsExactly("com.example.app");
        assertThat(m.firstPartySources().get(0).component()).isEqualTo("com.example.app.MainActivity");
    }

    @Test
    void dependencyInventoryFromMetaInfAndLibraryBuildConfig() throws IOException {
        AppModel m = ingest();
        assertThat(m.dependencies()).extracting(AppModel.Dependency::name, AppModel.Dependency::version, AppModel.Dependency::source)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("androidx.core:core", "1.3.1", "meta_inf_version"),
                        org.assertj.core.groups.Tuple.tuple("com.other", null, "package_prefix"),
                        org.assertj.core.groups.Tuple.tuple("net.lib.upload", "3.2.3", "library_build_config"));
    }

    @Test
    void identifiersLiteralsVocabularyAndCallbacks() throws IOException {
        AppModel m = ingest();
        assertThat(m.identifiers()).contains("com.example.app", "example", "MainActivity", "loadThing", "api_key", "api.example.com");
        assertThat(m.stringLiterals()).containsExactly("https://api.example.com/v1");
        assertThat(m.platformVocabulary()).containsExactly("Activity", "Bundle");
        assertThat(m.frameworkCallbacks()).containsExactly("onCreate");
        assertThat(m.resources().strings()).extracting(AppModel.StringEntry::name).containsExactly("api_key");
    }

    @Test
    void ingestIsDeterministic() throws IOException {
        AppModel a = ingest();
        AppModel b = ingestor().ingest(new DecompiledApp("id1", new LayoutDetector().detect(dir), "jadx", "1.5.6",
                DecompileStats.forDirectoryInput(0, 0, 5), false));
        assertThat(Json.json().writeValueAsString(a)).isEqualTo(Json.json().writeValueAsString(b));
        assertThat(a.stats().ingestHash()).hasSize(64).isEqualTo(b.stats().ingestHash());
    }
}
