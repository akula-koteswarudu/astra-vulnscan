package com.vulnscan.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.vulnscan.config.ModelsConfig;
import com.vulnscan.ingest.AppModel;
import com.vulnscan.ingest.CodeBase;
import com.vulnscan.ingest.DecompileStats;
import com.vulnscan.ingest.DecompiledApp;
import com.vulnscan.ingest.IngestorTest;
import com.vulnscan.ingest.LayoutDetector;
import com.vulnscan.models.LlmResponse;
import com.vulnscan.planning.ScanUnit;
import com.vulnscan.prompts.PromptLibrary;
import com.vulnscan.testing.FakeLlmClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScannerTest {

    @TempDir
    Path dir;
    AppModel app;
    CodeBase code;
    ScanUnit unit;
    static final String MAIN = "sources/com/example/app/MainActivity.java";
    static final String HELPER = "sources/com/example/app/Helper.java";

    static final ModelsConfig.ModelSpec SPEC = new ModelsConfig.ModelSpec("openai_compatible", "u", "student-model", "K", 100000,
            null, 1000, 0.0, null, Map.of(), new ModelsConfig.Price(0, 0), new ModelsConfig.RateLimit(30, 8000, null, null, null, null),
            "max_tokens", 2, 0, new ModelsConfig.Retry(1, 1));

    @BeforeEach
    void setUp() throws IOException {
        IngestorTest.fixtureApp(dir);
        IngestorTest.write(dir, HELPER, """
                package com.example.app;

                public class Helper {
                    public static void save(android.content.Context c, String pw) {
                        c.getSharedPreferences("p", 0).edit().putString("password", pw).apply();
                    }
                }
                """);
        app = IngestorTest.ingestorForTests().ingest(new DecompiledApp("id", new LayoutDetector().detect(dir), "jadx", "1",
                DecompileStats.forDirectoryInput(0, 0, 0), false));
        code = new CodeBase(dir);
        unit = new ScanUnit("unit1", "component", "com.example.app.MainActivity", List.of(HELPER, MAIN),
                "<application>\n  <activity android:name=\"com.example.app.MainActivity\" android:exported=\"true\"/>\n</application>",
                "res/values/strings.xml: <string name=\"api_key\">abc123</string>", "androidx.core:core 1.3.1\nnet.lib.upload 3.2.3", 500, 5);
    }

    Scanner scanner(FakeLlmClient client, UnitSplitter splitter) throws IOException {
        PromptBuilder pb = new PromptBuilder(new PromptLibrary(Path.of("prompts")).get("scan", "v1"), app, code);
        return new Scanner(client, SPEC, "student", pb, new FindingParser(), new Normalizer(), new Splitter(),
                new EvidenceVerifier(code, 90, 25), splitter);
    }

    static String finding(String file, String evidence, String kind) {
        return """
                {"title": "Password in prefs", "vuln_class": "insecure storage", "cwe": "312", "owasp_category": "MASVS-STORAGE",
                 "severity": "HIGH", "confidence": 0.9, "locations": [{"file": "%s", "component": null, "symbol": "Helper$1.save"}],
                 "evidence": %s, "rationale": "plaintext", "kind": "%s"}""".formatted(file, com.fasterxml.jackson.databind.node.TextNode.valueOf(evidence), kind);
    }

    static final String EVIDENCE = "c.getSharedPreferences(\"p\", 0).edit().putString(\"password\", pw).apply();";

    @Test
    void validResponseIsNormalisedSplitAndVerified() throws IOException {
        String twoLocations = """
                {"findings": [{"title": "Password in prefs", "cwe": "CWE-312", "severity": "high", "confidence": 0.8,
                  "locations": [{"file": "%s", "symbol": "Helper.save"}, {"file": "com/example/app/Helper.java", "symbol": "Helper"}],
                  "evidence": "%s", "rationale": "r", "kind": "code"}]}""".formatted(HELPER, EVIDENCE.replace("\"", "\\\""));
        ScanResult r = scanner(FakeLlmClient.constant(twoLocations), u -> List.of()).scan(unit, null, new ScanRun("teacher", "r1", 0));
        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.findings()).hasSize(2);
        Finding f = r.findings().get(0);
        assertThat(f.cwe()).isEqualTo("CWE-312");
        assertThat(f.severity()).isEqualTo("high");
        assertThat(f.evidenceStatus()).isEqualTo("verified_exact");
        assertThat(f.location().line()).isEqualTo(5);
        assertThat(r.findings().get(1).location().file()).isEqualTo(HELPER); // suffix path resolved
        assertThat(f.provenance().arm()).isEqualTo("teacher");
        assertThat(f.provenance().promptVersion()).isEqualTo("v1");
    }

    @Test
    void oneBadFindingIsCountedAndTheRestKept() throws IOException {
        String text = "{\"findings\": [" + finding(HELPER, EVIDENCE, "code") + ", {\"title\": \"no locations\", \"evidence\": \"x\"}]}";
        ScanResult r = scanner(FakeLlmClient.constant(text), u -> List.of()).scan(unit, null, new ScanRun("a", "r", 0));
        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.findings()).hasSize(1);
        assertThat(r.badFindings()).isEqualTo(1);
    }

    @Test
    void brokenJsonIsRepairedWithOneCall() throws IOException {
        AtomicInteger n = new AtomicInteger();
        FakeLlmClient client = new FakeLlmClient(req -> n.incrementAndGet() == 1
                ? FakeLlmClient.ok("Sure! {\"findings\": [ {oops")
                : FakeLlmClient.ok("```json\n{\"findings\": [" + finding(HELPER, EVIDENCE, "code") + "]}\n```"));
        ScanResult r = scanner(client, u -> List.of()).scan(unit, null, new ScanRun("a", "r", 0));
        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.findings()).hasSize(1);
        assertThat(client.calls()).isEqualTo(2);
        assertThat(client.requests.get(1).messages()).hasSize(3);
        assertThat(client.requests.get(1).metadata().purpose()).isEqualTo("repair");
        assertThat(r.exchanges()).extracting(ScanResult.Exchange::purpose).containsExactly("scan", "repair");
    }

    @Test
    void stillBrokenAfterRepairIsParseFailed() throws IOException {
        ScanResult r = scanner(FakeLlmClient.constant("not json at all"), u -> List.of()).scan(unit, null, new ScanRun("a", "r", 0));
        assertThat(r.status()).isEqualTo("parse_failed");
        assertThat(r.exchanges()).hasSize(2);
    }

    @Test
    void refusalIsRecordedWithCategory() throws IOException {
        FakeLlmClient client = new FakeLlmClient(req -> FakeLlmClient.withStop("", LlmResponse.StopReason.REFUSAL));
        ScanResult r = scanner(client, u -> List.of()).scan(unit, null, new ScanRun("a", "r", 0));
        assertThat(r.status()).isEqualTo("refused");
        assertThat(r.refusalCategory()).isEqualTo("cyber");
        assertThat(client.calls()).isEqualTo(1);
    }

    @Test
    void truncatedRetriesWithDoubledTokensThenSucceeds() throws IOException {
        FakeLlmClient client = new FakeLlmClient(req -> req.maxTokens() == 1000
                ? FakeLlmClient.withStop("{\"findings\": [", LlmResponse.StopReason.MAX_TOKENS)
                : FakeLlmClient.ok("{\"findings\": []}"));
        ScanResult r = scanner(client, u -> List.of()).scan(unit, null, new ScanRun("a", "r", 0));
        assertThat(r.status()).isEqualTo("ok");
        assertThat(client.requests).extracting(req -> req.maxTokens()).containsExactly(1000, 2000);
    }

    @Test
    void stillTruncatedIsReplannedIntoSmallerUnits() throws IOException {
        FakeLlmClient client = new FakeLlmClient(req -> req.metadata().unitId().equals("unit1")
                ? FakeLlmClient.withStop("{\"findings\": [", LlmResponse.StopReason.MAX_TOKENS)
                : FakeLlmClient.ok("{\"findings\": []}"));
        ScanUnit a = new ScanUnit("partA", "component", "x/a", List.of(HELPER), unit.manifestSlice(), null, null, 100, 1);
        ScanUnit b = new ScanUnit("partB", "component", "x/b", List.of(MAIN), unit.manifestSlice(), null, null, 100, 1);
        ScanResult r = scanner(client, u -> List.of(a, b)).scan(unit, null, new ScanRun("a", "r", 0));
        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.subUnits()).extracting(ScanResult::unitId).containsExactly("partA", "partB");
    }

    @Test
    void whenTheOutputLimitForbidsDoublingItReplansDirectly() throws IOException {
        ModelsConfig.ModelSpec capped = new ModelsConfig.ModelSpec("openai_compatible", "u", "m", "K", 100000, null, 1000, 0.0, null,
                Map.of(), new ModelsConfig.Price(0, 0), new ModelsConfig.RateLimit(30, 8000, null, 7000, 1000, null), "max_tokens", 1, 0,
                new ModelsConfig.Retry(1, 1));
        FakeLlmClient client = new FakeLlmClient(req -> req.metadata().unitId().equals("unit1")
                ? FakeLlmClient.withStop("{\"findings\": [", LlmResponse.StopReason.MAX_TOKENS)
                : FakeLlmClient.ok("{\"findings\": []}"));
        ScanUnit a = new ScanUnit("partA", "component", "x/a", List.of(HELPER), unit.manifestSlice(), null, null, 100, 1);
        PromptBuilder pb = new PromptBuilder(new PromptLibrary(Path.of("prompts")).get("scan", "v1"), app, code);
        Scanner s = new Scanner(client, capped, "student", pb, new FindingParser(), new Normalizer(), new Splitter(),
                new EvidenceVerifier(code, 90, 25), u -> List.of(a));
        ScanResult r = s.scan(unit, null, new ScanRun("a", "r", 0));
        assertThat(client.requests).extracting(req -> req.maxTokens()).containsExactly(1000, 1000); // no 2000 attempt
        assertThat(r.subUnits()).hasSize(1);
    }

    @Test
    void stillTruncatedAndUnsplittableIsTruncatedNeverHalfParsed() throws IOException {
        FakeLlmClient client = new FakeLlmClient(req -> FakeLlmClient.withStop("{\"findings\": [" + finding(HELPER, EVIDENCE, "code"),
                LlmResponse.StopReason.MAX_TOKENS));
        ScanResult r = scanner(client, u -> List.of()).scan(unit, null, new ScanRun("a", "r", 0));
        assertThat(r.status()).isEqualTo("truncated");
        assertThat(r.findings()).isEmpty();
    }

    @Test
    void perturbOrderShufflesFilesAndLessonsOnlyFromRunTwo() throws IOException {
        PromptBuilder pb = new PromptBuilder(new PromptLibrary(Path.of("prompts")).get("scan", "v1"), app, code);
        ScanUnit many = new ScanUnit("u", "whole", "app", List.of(HELPER, MAIN, "sources/com/example/app/R.java"), null, null, null, 1, 1);
        String guidance = "- L01 A\n  Why: a\n- L02 B\n  Why: b\n- L03 C\n  Why: c\n- L04 D\n  Why: d";
        String run1 = pb.user(many, guidance, 0, true);
        assertThat(run1).isEqualTo(pb.user(many, guidance, 0, false));
        String run2 = pb.user(many, guidance, 1, true);
        assertThat(run2).isNotEqualTo(run1).isEqualTo(pb.user(many, guidance, 1, true)); // reproducible
        for (String part : List.of("- L01 A", "- L04 D", "=== FILE: " + HELPER, "=== FILE: " + MAIN)) {
            assertThat(run2).contains(part);
        }
        assertThat(run2.length()).isBetween(run1.length() - 5, run1.length() + 5); // same content, different order
    }

    @Test
    void guidanceGoesBeforeContextAndCodeAndSystemIsStable() throws IOException {
        FakeLlmClient client = FakeLlmClient.constant("{\"findings\": []}");
        Scanner s = scanner(client, u -> List.of());
        s.scan(unit, "- Lesson one", new ScanRun("a", "r", 0));
        s.scan(unit, null, new ScanRun("b", "r", 0));
        String withLessons = client.requests.get(0).messages().get(0).content();
        assertThat(withLessons.indexOf("Lesson one")).isLessThan(withLessons.indexOf("## App context"));
        assertThat(withLessons.indexOf("## App context")).isLessThan(withLessons.indexOf("=== FILE: " + HELPER));
        assertThat(client.requests.get(1).messages().get(0).content()).doesNotContain("Guidance");
        assertThat(client.requests.get(0).system()).isEqualTo(client.requests.get(1).system());
        assertThat(withLessons).doesNotContain("@Metadata");
    }
}
