package com.vulnscan.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vulnscan.common.TokenEstimator;
import com.vulnscan.config.AppConfig;
import com.vulnscan.config.ModelsConfig;
import com.vulnscan.ingest.AppModel;
import com.vulnscan.ingest.CodeBase;
import com.vulnscan.ingest.DecompileStats;
import com.vulnscan.ingest.DecompiledApp;
import com.vulnscan.ingest.IngestorTest;
import com.vulnscan.ingest.LayoutDetector;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanPlannerTest {

    @TempDir
    Path dir;

    static AppConfig.Planning planning(AppConfig.PlanningMode mode) {
        return new AppConfig.Planning(mode, 1, 4, 0.0, 100, 100, null, 200, AppConfig.GraphBuilder.TEXT_REFERENCE, null);
    }

    static ModelsConfig.ModelSpec model(int tpm, int maxOutput) {
        return new ModelsConfig.ModelSpec("openai_compatible", "u", "m", "K", 131072, null, maxOutput, 0.0, null, Map.of(),
                new ModelsConfig.Price(0, 0), new ModelsConfig.RateLimit(30, tpm, null, null, null, null), "max_tokens", 1, 0,
                new ModelsConfig.Retry(1, 1));
    }

    ScanPlanner planner(AppConfig.PlanningMode mode) {
        return new ScanPlanner(planning(mode), new TokenEstimator(4, 0.0), new Prioritizer(), new TextReferenceGraphBuilder(),
                new UnitContextBuilder(), 50);
    }

    private AppModel ingest() throws IOException {
        IngestorTest.fixtureApp(dir);
        // Add an exported activity using a same-package helper by simple name (no import), and an unreachable util.
        IngestorTest.write(dir, "resources/AndroidManifest.xml", """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                  <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="30"/>
                  <application android:name=".App">
                    <activity android:name=".MainActivity" android:exported="true"/>
                    <activity android:name=".Internal" android:exported="false"/>
                  </application>
                </manifest>""");
        IngestorTest.write(dir, "sources/com/example/app/Helper.java",
                "package com.example.app;\npublic class Helper { void h() { " + "int x = 1; ".repeat(200) + "} }\n");
        IngestorTest.write(dir, "sources/com/example/app/MainActivity.java",
                "package com.example.app;\npublic class MainActivity { void a() { new Helper(); webView.loadUrl(getIntent().getStringExtra(\"u\")); } }\n");
        IngestorTest.write(dir, "sources/com/example/app/util/Lonely.java", "package com.example.app.util;\npublic class Lonely { }\n");
        return IngestorTest.ingestorForTests().ingest(new DecompiledApp("id", new LayoutDetector().detect(dir), "jadx", "1",
                DecompileStats.forDirectoryInput(0, 0, 0), false));
    }

    @Test
    void wholeAppWhenItFits() throws IOException {
        AppModel app = ingest();
        UnitPlan plan = planner(AppConfig.PlanningMode.AUTO).plan("teacher", model(1_000_000, 1000), false, app, new CodeBase(dir));
        assertThat(plan.mode()).isEqualTo("whole");
        assertThat(plan.units()).hasSize(1);
        assertThat(plan.units().get(0).files()).hasSize(3);
        assertThat(plan.units().get(0).dependencies()).contains("androidx.core:core 1.3.1");
    }

    @Test
    void perMinuteLimitForcesChunkingWithEntryPointNeighboursAndPackageGroups() throws IOException {
        AppModel app = ingest();
        // budget = 0.8*1500 - 100 overhead - 300 output - 100 guidance = 700
        UnitPlan plan = planner(AppConfig.PlanningMode.AUTO).plan("student", model(1500, 300), true, app, new CodeBase(dir));
        assertThat(plan.mode()).isEqualTo("chunked");
        assertThat(plan.budget().effective()).isEqualTo(700);
        ScanUnit main = plan.units().stream().filter(u -> u.kind().equals("component")).findFirst().orElseThrow();
        assertThat(main.root()).isEqualTo("com.example.app.MainActivity");
        // same-package edge without an import:
        assertThat(main.files()).containsExactly("sources/com/example/app/Helper.java", "sources/com/example/app/MainActivity.java");
        assertThat(main.manifestSlice()).contains("MainActivity").doesNotContain("Internal");
        assertThat(plan.units()).anySatisfy(u -> {
            assertThat(u.kind()).isEqualTo("package");
            assertThat(u.files()).containsExactly("sources/com/example/app/util/Lonely.java");
        });
        assertThat(plan.units()).anySatisfy(u -> assertThat(u.kind()).isEqualTo("app_context"));
        assertThat(plan.units()).allSatisfy(u -> assertThat(u.estTokens()).isLessThanOrEqualTo(700));
    }

    @Test
    void unitIdsStayStableWhenAnUnrelatedFileChanges() throws IOException {
        AppModel before = ingest();
        UnitPlan p1 = planner(AppConfig.PlanningMode.CHUNKED).plan("s", model(1500, 300), true, before, new CodeBase(dir));
        IngestorTest.write(dir, "sources/com/example/app/util/Lonely.java", "package com.example.app.util;\npublic class Lonely { int changed; }\n");
        AppModel after = IngestorTest.ingestorForTests().ingest(new DecompiledApp("id", new LayoutDetector().detect(dir), "jadx", "1",
                DecompileStats.forDirectoryInput(0, 0, 0), false));
        UnitPlan p2 = planner(AppConfig.PlanningMode.CHUNKED).plan("s", model(1500, 300), true, after, new CodeBase(dir));
        String id1 = p1.units().stream().filter(u -> u.root().equals("com.example.app.MainActivity")).findFirst().orElseThrow().unitId();
        String id2 = p2.units().stream().filter(u -> u.root().equals("com.example.app.MainActivity")).findFirst().orElseThrow().unitId();
        assertThat(id1).isEqualTo(id2);
        Set<String> ids1 = Set.copyOf(p1.units().stream().map(ScanUnit::unitId).toList());
        assertThat(p2.units().stream().filter(u -> !ids1.contains(u.unitId())).map(ScanUnit::root).toList())
                .containsExactly("com.example.app.util#1");
    }

    @Test
    void oversizedFileIsSkippedAndReported() throws IOException {
        ingest();
        IngestorTest.write(dir, "sources/com/example/app/Huge.java", "package com.example.app;\nclass Huge {" + "x".repeat(5000) + "}\n");
        AppModel app = IngestorTest.ingestorForTests().ingest(new DecompiledApp("id", new LayoutDetector().detect(dir), "jadx", "1",
                DecompileStats.forDirectoryInput(0, 0, 0), false));
        UnitPlan plan = planner(AppConfig.PlanningMode.CHUNKED).plan("s", model(1500, 300), true, app, new CodeBase(dir));
        assertThat(plan.skipped()).anySatisfy(s -> assertThat(s.what()).contains("Huge.java"));
        assertThat(plan.units()).noneSatisfy(u -> assertThat(u.files()).contains("sources/com/example/app/Huge.java"));
    }

    @Test
    void budgetFloorStopsWithTheNumbers() {
        assertThatThrownBy(() -> planner(AppConfig.PlanningMode.AUTO).budget("student", model(1000, 600), true))
                .isInstanceOf(PlanningException.class)
                .hasMessageContaining("student").hasMessageContaining("max_output_tokens 600").hasMessageContaining("min_code_budget_tokens");
    }

    @Test
    void inputLimitAndTotalLimitBothCapTheRequest() {
        ModelsConfig.ModelSpec both = new ModelsConfig.ModelSpec("openai_compatible", "u", "m", "K", 131072, null, 1500, 0.0, null, Map.of(),
                new ModelsConfig.Price(0, 0), new ModelsConfig.RateLimit(30, 8000, null, 7000, null, null), "max_tokens", 1, 0,
                new ModelsConfig.Retry(1, 1));
        UnitPlan.Budget b = planner(AppConfig.PlanningMode.AUTO).budget("student", both, true);
        assertThat(b.perRequestCap()).isEqualTo(Math.min(5600, 6400 - 1500));
        assertThat(b.effective()).isEqualTo(4900 - 100 - 100);
    }

    @Test
    void forcedWholeThatDoesNotFitFails() throws IOException {
        AppModel app = ingest();
        assertThatThrownBy(() -> planner(AppConfig.PlanningMode.WHOLE).plan("s", model(1500, 300), true, app, new CodeBase(dir)))
                .hasMessageContaining("mode whole");
    }

    @Test
    void truncatedUnitsSplitIntoSmallerOnes() throws IOException {
        AppModel app = ingest();
        ScanPlanner p = planner(AppConfig.PlanningMode.CHUNKED);
        UnitPlan plan = p.plan("s", model(1500, 300), true, app, new CodeBase(dir));
        ScanUnit main = plan.units().stream().filter(u -> u.kind().equals("component")).findFirst().orElseThrow();
        assertThat(p.split(main, "s", model(1500, 300), true, app, new CodeBase(dir))).hasSize(2).allSatisfy(u -> assertThat(u.files()).hasSize(1));
        String slice = new UnitContextBuilder().manifestSlice(app, List.of("com.example.app.MainActivity", "com.example.app.Internal"));
        ScanUnit manifestOnly = new ScanUnit("m", "app_context", "app-context/manifest", List.of(), slice, null, null, 100, 1);
        List<ScanUnit> halves = p.split(manifestOnly, "s", model(1500, 300), true, app, new CodeBase(dir));
        assertThat(halves).hasSize(2);
        assertThat(halves.get(0).manifestSlice()).contains("Internal").doesNotContain("MainActivity");
        assertThat(halves.get(1).manifestSlice()).contains("MainActivity");
        ScanUnit whole = new ScanUnit("w", "whole", "app", List.of(), null, null, null, 1, 1);
        assertThat(p.split(whole, "s", model(1500, 300), true, app, new CodeBase(dir))).isNotEmpty();
    }

    @Test
    void codeViewDropsKotlinMetadataOnly() {
        String text = "class A {\n@Metadata(bv = {1}, d1 = {\"xx\"})\n/* JADX INFO: compiled from: A.kt */\nint x;\n}";
        assertThat(CodeView.render(text)).isEqualTo("class A {\nint x;\n}");
    }

    @Test
    void anyOverheadSmallerThanThePromptIsRejected() {
        ScanPlanner p = new ScanPlanner(planning(AppConfig.PlanningMode.AUTO), new TokenEstimator(4, 0), new Prioritizer(),
                new TextReferenceGraphBuilder(), new UnitContextBuilder(), 500);
        assertThatThrownBy(() -> p.budget("m", model(100000, 100), false)).hasMessageContaining("prompt_overhead_tokens");
    }
}
