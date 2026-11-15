package com.vulnscan.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vulnscan.UserFacingException;
import com.vulnscan.common.Ticker;
import com.vulnscan.common.TokenEstimator;
import com.vulnscan.config.ConfigLoader;
import com.vulnscan.config.LoadedConfig;
import com.vulnscan.config.Secrets;
import com.vulnscan.evaluate.Metrics;
import com.vulnscan.ingest.IngestorTest;
import com.vulnscan.models.LlmClient;
import com.vulnscan.models.LlmRequest;
import com.vulnscan.models.ModelClientFactory;
import com.vulnscan.models.ProviderRegistry;
import com.vulnscan.scan.ArmScanner;
import com.vulnscan.scan.Deduplicator;
import com.vulnscan.testing.FakeLlmClient;
import com.vulnscan.wiring.PipelineFactory;
import jakarta.validation.Validation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End to end with fake models on a small decompiled-directory fixture: every stage, the run folder and the report. */
class ExperimentTest {

    @TempDir
    Path dir;

    static final String EVIDENCE = "webView.loadUrl(getIntent().getStringExtra(\\\"u\\\"));";

    /** Fake provider: answers by purpose. The student finds the WebView issue only when the lesson is in its prompt. */
    static LlmClient fake(String role) {
        return new FakeLlmClient(req -> {
            String purpose = req.metadata().purpose();
            String user = req.messages().get(req.messages().size() - 1).content();
            String first = req.messages().get(0).content();
            return switch (purpose) {
                case "scan" -> {
                    boolean knows = role.equals("teacher") || first.contains("Guidance: general lessons");
                    if (!first.contains("MainActivity.java") || !knows) {
                        yield FakeLlmClient.ok("{\"findings\": []}");
                    }
                    yield FakeLlmClient.ok("{\"findings\": [{\"title\": \"Untrusted URL in WebView\", \"vuln_class\": \"webview url loading\","
                            + " \"cwe\": \"CWE-939\", \"owasp_category\": \"MASVS-PLATFORM\", \"severity\": \"high\", \"confidence\": 0.9,"
                            + " \"locations\": [{\"file\": \"sources/com/example/app/MainActivity.java\", \"component\": \"com.example.app.MainActivity\","
                            + " \"symbol\": \"MainActivity.onCreate\"}], \"evidence\": \"" + EVIDENCE + "\", \"rationale\": \"exported\", \"kind\": \"code\"}]}");
                }
                case "distill" -> FakeLlmClient.ok("{\"lessons\": [{\"title\": \"Intent data loaded into a WebView\", \"applies_to\":"
                        + " {\"component_types\": [\"exported Activity\"], \"apis\": [\"WebView.loadUrl\"]}, \"signals\": [\"getStringExtra passed to loadUrl\"],"
                        + " \"why_exploitable\": \"another app controls the URL\", \"false_positive_guards\": [\"URL checked against an allow-list\"],"
                        + " \"cwe\": \"CWE-939\", \"owasp_category\": \"MASVS-PLATFORM\", \"severity_guidance\": \"high\","
                        + " \"abstract_example\": \"view.loadUrl(intent.getStringExtra(<key>))\", \"source_finding_ids\": []}]}");
                case "judge_screen" -> FakeLlmClient.ok(user.contains("- F1:") ? "{\"results\": [{\"finding\": \"F1\", \"items\": [\"GT-01\"]}]}" : "{\"results\": []}");
                case "judge_confirm" -> FakeLlmClient.ok("{\"same_type\": true, \"same_root_cause\": true, \"same_place\": null, \"verdict\": \"match\", \"reason\": \"same\"}");
                default -> FakeLlmClient.ok("{}");
            };
        });
    }

    private Experiment experiment(LoadedConfig cfg, Path resume) {
        PipelineFactory f = new PipelineFactory(Path.of("").toAbsolutePath());
        Map<String, BiFunction<com.vulnscan.config.ModelsConfig.ModelSpec, String, LlmClient>> adapters = Map.of(
                "anthropic", (spec, key) -> fake("teacher"), "openai_compatible", (spec, key) -> fake(spec.model()));
        ModelClientFactory models = new ModelClientFactory(cfg.models(), new ProviderRegistry(adapters),
                new Secrets(Map.of("ANTHROPIC_API_KEY", "x", "GROQ_API_KEY", "x", "POLLINATIONS_API_KEY", "x")), new TokenEstimator(4, 0.15),
                dir.resolve("cache"), true, Ticker.SYSTEM);
        RunStore store = resume == null ? RunStore.create(dir.resolve("runs"), "fixture", cfg.configHash(), Instant.now())
                : RunStore.resume(resume, cfg.configHash());
        RunManifest manifest = new RunManifest().put("config_hash", cfg.configHash());
        store.writeJson("manifest.json", manifest.snapshot());
        var prompts = f.prompts();
        return new Experiment(cfg, store, manifest, f.appPreparer(cfg), dir.resolve("app"), prompts, f.planner(cfg, prompts), models,
                new ArmScanner(new Deduplicator(cfg.matching().cweGroups())),
                new StandardLaterStages(new LessonStage(), new EvaluateStage(), new ReportStage()), msg -> { });
    }

    private LoadedConfig config(boolean withGroundTruth) throws IOException {
        IngestorTest.fixtureApp(dir.resolve("app"));
        IngestorTest.write(dir.resolve("app"), "sources/com/example/app/MainActivity.java", """
                package com.example.app;

                public class MainActivity {
                    void onCreate() {
                        webView.loadUrl(getIntent().getStringExtra("u"));
                    }
                }
                """);
        Files.writeString(dir.resolve("gt.yaml"), "schema_version: 1\nsource: test\nitems:\n"
                + "- {id: GT-01, title: Arbitrary URL in WebView, description: Any URL can be loaded via an intent extra.}\n"
                + "- {id: GT-02, title: Logging, description: Credentials in logs.}\n");
        Files.createDirectories(dir.resolve("configs"));
        Files.copy(Path.of("configs/models.yaml"), dir.resolve("configs/models.yaml"));
        Files.copy(Path.of("configs/matching.yaml"), dir.resolve("configs/matching.yaml"));
        Path app = dir.resolve("configs/app.yaml");
        Files.writeString(app, "app: {name: fixture, input: " + dir.resolve("app") + (withGroundTruth ? ", ground_truth: " + dir.resolve("gt.yaml") : "") + "}\n"
                + "cache: {llm: true, dir: " + dir.resolve("cache") + "}\noutput: {runs_dir: " + dir.resolve("runs") + "}\n");
        return new ConfigLoader(Validation.buildDefaultValidatorFactory().getValidator(), dir).load(app);
    }

    @Test
    void fullRunProducesEveryArtefactAndTheLift() throws IOException {
        LoadedConfig cfg = config(true);
        Experiment e = experiment(cfg, null);
        e.run();
        RunStore s = e.store();
        for (String f : List.of("manifest.json", "logs.jsonl", "01_decompile/stats.json", "02_ingest/app_model.json",
                "03_plan/units_teacher.json", "03_plan/units_student.json", "04_scan/teacher/run_1/findings.json",
                "04_scan/student_baseline/run_1/results.json", "04_scan/student_with_lessons/run_1/findings.json",
                "05_learn/lessons.json", "05_learn/rejected_lessons.json", "07_evaluate/metrics.json", "07_evaluate/matches.json",
                "07_evaluate/judge_decisions.json", "report.md", "report.html")) {
            assertThat(s.exists(f)).as(f).isTrue();
        }
        Metrics m = s.readJson("07_evaluate/metrics.json", Metrics.class);
        assertThat(m.label()).isEqualTo("recall");
        assertThat(m.modes()).hasSize(1); // no enrichment in this fixture -> no robustness mode
        assertThat(m.lift().lift()).isEqualTo(0.5); // baseline 0/2, with lessons 1/2
        String report = Files.readString(s.path("report.md"));
        java.util.List<com.vulnscan.models.UsageTracker.Line> usage = ReportStage.usage(s.dir());
        assertThat(usage).anySatisfy(l -> assertThat(l.billedCalls).isPositive());
        assertThat(report).contains("Lift: +50.0 points", "Intent data loaded into a WebView", "## 9. Limitations");
        String html = Files.readString(s.path("report.html"));
        assertThat(html).startsWith("<!doctype html>").contains("Lift: +50.0 points", "Intent data loaded into a WebView", "class=\"c hit\"")
                .doesNotContain("{{");

        // resume skips every completed stage (no new model calls) and reproduces the report
        Experiment again = experiment(cfg, s.dir());
        again.run();
        assertThat(Files.readString(s.path("report.md"))).isEqualTo(report);
    }

    @Test
    void withoutGroundTruthEverythingIsAgreementWithTeacher() throws IOException {
        LoadedConfig cfg = config(false);
        Experiment e = experiment(cfg, null);
        e.run();
        Metrics m = e.store().readJson("07_evaluate/metrics.json", Metrics.class);
        assertThat(m.label()).isEqualTo("agreement with teacher");
        assertThat(m.groundTruth()).isFalse();
        assertThat(m.modes().get(0).armRuns()).extracting(Metrics.ArmRun::arm).doesNotContain("teacher");
        assertThat(Files.readString(e.store().path("report.md"))).contains("agreement with teacher", "No ground truth for this app");
    }

    @Test
    void aBrokenJudgeStopsTheEvaluationInsteadOfReportingZeroRecall() throws IOException {
        LoadedConfig cfg = config(true);
        Experiment e = experiment(cfg, null);
        // replace the judge's answers with provider errors
        Experiment broken = new Experiment(cfg, e.store(), e.manifest(), new PipelineFactory(Path.of("").toAbsolutePath()).appPreparer(cfg),
                dir.resolve("app"), e.prompts(), new PipelineFactory(Path.of("").toAbsolutePath()).planner(cfg, e.prompts()),
                new ModelClientFactory(cfg.models(), new ProviderRegistry(Map.of(
                        "anthropic", (spec, key) -> fake("teacher"),
                        "openai_compatible", (spec, key) -> spec.model().contains("deepseek")
                                ? new FakeLlmClient(req -> { throw new com.vulnscan.models.LlmException("400 bad param", false, 400, null, null); })
                                : fake(spec.model()))),
                        new Secrets(Map.of("ANTHROPIC_API_KEY", "x", "GROQ_API_KEY", "x", "POLLINATIONS_API_KEY", "x")),
                        new TokenEstimator(4, 0.15), dir.resolve("cache2"), true, Ticker.SYSTEM),
                new ArmScanner(new Deduplicator(cfg.matching().cweGroups())),
                new StandardLaterStages(new LessonStage(), new EvaluateStage(), new ReportStage()), msg -> { });
        assertThatThrownBy(broken::run).isInstanceOf(UserFacingException.class).hasMessageContaining("judge is failing");
        assertThat(e.store().exists("07_evaluate/metrics.json")).isFalse();
    }

    @Test
    void resumeRefusesADifferentConfig() throws IOException {
        LoadedConfig cfg = config(true);
        Experiment e = experiment(cfg, null);
        e.plan();
        assertThatThrownBy(() -> RunStore.resume(e.store().dir(), "0".repeat(64))).isInstanceOf(UserFacingException.class)
                .hasMessageContaining("different configuration");
    }
}
