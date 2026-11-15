package com.vulnscan.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.vulnscan.config.MatchingConfig;
import com.vulnscan.config.ModelsConfig;
import com.vulnscan.evaluate.Evaluator;
import com.vulnscan.evaluate.Metrics;
import com.vulnscan.models.LlmRequest;
import com.vulnscan.prompts.PromptLibrary;
import com.vulnscan.scan.Finding;
import com.vulnscan.testing.FakeLlmClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MatchingTest {

    static final MatchingConfig CFG = new MatchingConfig(Map.of("webview", List.of("CWE-939", "CWE-749", "CWE-20")),
            new MatchingConfig.Weights(0.6, 0.4), new MatchingConfig.LocationScores(1.0, 0.8, 0.6, 0.5),
            new MatchingConfig.CweScores(1.0, 0.7, 0.4), 0.3);
    static final ModelsConfig.ModelSpec JUDGE = new ModelsConfig.ModelSpec("openai_compatible", "u", "judge", "K", 100000, null, 3000,
            0.0, null, Map.of(), new ModelsConfig.Price(0, 0), new ModelsConfig.RateLimit(30, 8000, null, null, null, null), "max_tokens", 1, 0,
            new ModelsConfig.Retry(1, 1));

    static Finding finding(String id, String title, String cwe, String symbol, String status) {
        return new Finding(id, title, title, cwe, "MASVS-PLATFORM", "high", 0.8,
                new Finding.Location("sources/a/" + symbol.split("\\.")[0] + ".java", null, symbol, 3, 3), "evidence for " + title,
                "rationale for " + title, "code", status, false, new Finding.Provenance("secret-model", "student_with_lessons", "run-x", 0, List.of("u"), "v1"));
    }

    static ReferenceItem readmeItem(String id, String title, String desc) {
        return new ReferenceItem(id, title, desc, null, null, "code", "full", null);
    }

    /** Fake judge: screen says every finding may describe every item; confirm answers from a table. */
    static FakeLlmClient judge(Function<String, String> confirmVerdict) {
        return new FakeLlmClient(req -> {
            String user = req.messages().get(0).content();
            if (req.metadata().purpose().equals("judge_screen")) {
                StringBuilder sb = new StringBuilder("{\"results\": [");
                List<String> itemIds = new java.util.ArrayList<>();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("- (GT-\\d+|T-\\d+):").matcher(user);
                while (m.find()) {
                    itemIds.add("\"" + m.group(1) + "\"");
                }
                java.util.regex.Matcher f = java.util.regex.Pattern.compile("- (F\\d+):").matcher(user);
                boolean first = true;
                while (f.find()) {
                    sb.append(first ? "" : ",").append("{\"finding\": \"").append(f.group(1)).append("\", \"items\": ").append(itemIds).append("}");
                    first = false;
                }
                return FakeLlmClient.ok(sb.append("]}").toString());
            }
            return FakeLlmClient.ok("{\"same_type\": true, \"same_root_cause\": true, \"same_place\": null, \"verdict\": \""
                    + confirmVerdict.apply(user) + "\", \"reason\": \"r\"}");
        });
    }

    static MatchingPipeline pipeline(FakeLlmClient client, List<String> appNames) {
        PromptLibrary p = new PromptLibrary(Path.of("prompts"));
        return new MatchingPipeline(new StructuredScorer(CFG, appNames),
                new Judge(client, JUDGE, p.get("judge_screen", "v1"), p.get("judge_confirm", "v1")), 0.3, 15);
    }

    @Test
    void hungarianPrefersTwoMatchesOverOneHigherScoringMatch() {
        // item0 can take f0 (1000.9) or f1 (1000.1); item1 can only take f0 (1000.2). Greedy would give 1 match.
        double[][] w = {{1000.9, 1000.1}, {1000.2, 0}};
        int[] a = HungarianAssignment.maximize(w);
        assertThat(a).containsExactly(1, 0);
        assertThat(HungarianAssignment.maximize(new double[][] {{0, 0}})).containsExactly(-1);
        assertThat(HungarianAssignment.maximize(new double[0][0])).isEmpty();
        assertThat(HungarianAssignment.maximize(new double[][] {{5}, {7}, {6}})).containsExactly(-1, 0, -1);
    }

    @Test
    void structuredScoreUsesOnlyFieldsTheItemHas() {
        StructuredScorer s = new StructuredScorer(CFG, List.of());
        Finding f = finding("f1", "WebView", "CWE-749", "WebActivity.onCreate", "verified_exact");
        ReferenceItem enriched = new ReferenceItem("GT-1", "t", "d", List.of("CWE-939"),
                List.of(new ReferenceItem.Place(null, "com.x.WebActivity", "onCreate", null)), "code", "full", null);
        StructuredScorer.Score sc = s.score(enriched, f);
        assertThat(sc.location()).isEqualTo(1.0);
        assertThat(sc.cwe()).isEqualTo(0.7); // same group
        assertThat(sc.total()).isEqualTo(0.6 * 1.0 + 0.4 * 0.7, org.assertj.core.data.Offset.offset(1e-9));
        StructuredScorer.Score readme = s.score(readmeItem("GT-2", "t", "d"), f);
        assertThat(readme.hasSignals()).isFalse();
        assertThat(readme.total()).isZero();
    }

    @Test
    void descriptionHintsAreExactAppNames() {
        StructuredScorer s = new StructuredScorer(CFG, List.of("ChooserActivity", "Util", "web_url"));
        ReferenceItem item = readmeItem("GT-7", "Theft", "Steal files via ChooserActivity using the 'web_url' parameter.");
        assertThat(s.hints(item)).containsExactly("ChooserActivity", "web_url");
        assertThat(s.hints(readmeItem("GT-8", "Utility", "utilities"))).isEmpty(); // whole words only
        Finding f = finding("f1", "x", "CWE-22", "ChooserActivity.onCreate", "verified_exact");
        assertThat(s.score(item, f).location()).isEqualTo(0.8);
    }

    @Test
    void readmeModeNeverPrefiltersButEnrichedModeDoes() {
        Finding far = finding("f1", "Logging", "CWE-532", "Other.log", "verified_exact");
        ReferenceItem readme = readmeItem("GT-01", "WebView URL", "loads any URL");
        ReferenceItem enriched = new ReferenceItem("GT-01", "WebView URL", "loads any URL", List.of("CWE-939"),
                List.of(new ReferenceItem.Place(null, "com.x.WebActivity", "onCreate", null)), "code", "full", null);
        MatchingPipeline.Decisions d1 = pipeline(judge(u -> "match"), List.of()).decide(List.of(readme), List.of(far), false, "t");
        assertThat(d1.prefilterDropped()).isZero();
        assertThat(d1.verdicts()).hasSize(1);
        MatchingPipeline.Decisions d2 = pipeline(judge(u -> "match"), List.of()).decide(List.of(enriched), List.of(far), true, "t");
        assertThat(d2.prefilterDropped()).isEqualTo(1);
        assertThat(d2.verdicts()).isEmpty();
    }

    @Test
    void judgeInputIsBlindToProvenanceAndPlaceIsConditional() {
        FakeLlmClient client = judge(u -> "match");
        Finding f = finding("f-secret-id", "WebView", "CWE-749", "WebActivity.onCreate", "verified_exact");
        pipeline(client, List.of()).decide(List.of(readmeItem("GT-01", "WebView URL", "loads any URL")), List.of(f), false, "t");
        for (LlmRequest r : client.requests) {
            String all = r.system() + r.messages().get(0).content();
            assertThat(all).doesNotContain("secret-model", "student_with_lessons", "run-x", "f-secret-id");
        }
        String confirm = client.requests.get(1).messages().get(0).content();
        assertThat(confirm).contains("does not say where it is");
        FakeLlmClient c2 = judge(u -> "match");
        pipeline(c2, List.of("ResultActivity")).decide(List.of(readmeItem("GT-14", "SetResult", "in ResultActivity")), List.of(f), false, "t");
        assertThat(c2.requests.get(1).messages().get(0).content()).contains("names a place");
    }

    @Test
    void strictAndLenientRecallAndPrecision() {
        List<ReferenceItem> items = List.of(readmeItem("GT-01", "A", "a"), readmeItem("GT-02", "B", "b"));
        Finding fa = finding("fa", "find A", "CWE-1", "X.a", "verified_exact");
        Finding fb = finding("fb", "find B", "CWE-2", "X.b", "unverified");
        Finding fc = finding("fc", "find C", "CWE-3", "X.c", "verified_exact");
        // fa matches GT-01; fb (unverified) matches GT-02; fc is partial for GT-02.
        Function<String, String> table = u -> u.contains("find A") && u.contains("Title: A") ? "match"
                : u.contains("find B") && u.contains("Title: B") ? "match"
                : u.contains("find C") && u.contains("Title: B") ? "partial" : "no";
        Evaluator ev = new Evaluator(pipeline(judge(table), List.of()));
        var src = new ReferenceSource() {
            public String name() { return "readme"; }
            public List<ReferenceItem> items() { return items; }
            public boolean isGroundTruth() { return true; }
        };
        Metrics.ArmRun r = ev.evaluate(src, List.of(new Evaluator.ArmRunInput("student_baseline", 1, "m", List.of(fa, fb, fc),
                Map.of("ok", 1L))), false).metrics().armRuns().get(0);
        assertThat(r.recallStrict()).isEqualTo(0.5);               // verified only: GT-01
        assertThat(r.recallLenient()).isEqualTo(1.0);              // + fc partial for GT-02
        assertThat(r.recallStrictInclUnverified()).isEqualTo(1.0); // fb counts here
        assertThat(r.precisionStrict()).isEqualTo(0.5);            // fa of {fa, fc}
        assertThat(r.precisionLenient()).isEqualTo(1.0);
    }

    @Test
    void oneFindingMatchingTwoItemsCountsOnceAndTheOtherIsMarkedUncredited() {
        List<ReferenceItem> items = List.of(readmeItem("GT-01", "A", "a"), readmeItem("GT-02", "B", "b"));
        Finding broad = finding("f", "broad", "CWE-1", "X.a", "verified_exact");
        MatchingPipeline p = pipeline(judge(u -> "match"), List.of());
        MatchingPipeline.Decisions d = p.decide(items, List.of(broad), false, "t");
        Map<String, String> a = MatchingPipeline.assign(items, List.of(broad), d, false);
        assertThat(a).hasSize(1);
        assertThat(MatchingPipeline.uncredited(items, List.of(broad), d, a)).hasSize(1).doesNotContainAnyElementsOf(a.keySet());
    }

    @Test
    void parallelConfirmGivesTheSameDecisionsInTheSameOrder() {
        List<ReferenceItem> items = List.of(readmeItem("GT-01", "A", "a"), readmeItem("GT-02", "B", "b"), readmeItem("GT-03", "C", "c"));
        List<Finding> fs = List.of(finding("f1", "x", "CWE-1", "X.a", "verified_exact"), finding("f2", "y", "CWE-2", "X.b", "verified_exact"));
        PromptLibrary pl = new PromptLibrary(Path.of("prompts"));
        MatchingPipeline serial = new MatchingPipeline(new StructuredScorer(CFG, List.of()),
                new Judge(judge(u -> u.contains("Title: B") ? "partial" : "match"), JUDGE, pl.get("judge_screen", "v1"), pl.get("judge_confirm", "v1")), 0.3, 100, 1);
        MatchingPipeline parallel = new MatchingPipeline(new StructuredScorer(CFG, List.of()),
                new Judge(judge(u -> u.contains("Title: B") ? "partial" : "match"), JUDGE, pl.get("judge_screen", "v1"), pl.get("judge_confirm", "v1")), 0.3, 100, 4);
        assertThat(parallel.decide(items, fs, false, "t").verdicts()).isEqualTo(serial.decide(items, fs, false, "t").verdicts());
    }

    @Test
    void quickLookUsesContentOrderSoIdsDoNotChangeThePrompt() {
        List<ReferenceItem> items = List.of(readmeItem("GT-01", "A", "a"));
        FakeLlmClient c1 = judge(u -> "match");
        FakeLlmClient c2 = judge(u -> "match");
        pipeline(c1, List.of()).decide(items, List.of(finding("zz1", "alpha", "CWE-1", "X.a", "verified_exact"),
                finding("aa2", "beta", "CWE-2", "X.b", "verified_exact")), false, "t");
        pipeline(c2, List.of()).decide(items, List.of(finding("aa1", "beta", "CWE-2", "X.b", "verified_exact"),
                finding("zz2", "alpha", "CWE-1", "X.a", "verified_exact")), false, "t");
        assertThat(c1.requests.get(0).messages()).isEqualTo(c2.requests.get(0).messages());
    }

    @Test
    void extraQuickLookOrdersAddCandidatesAsAUnionAndAreCounted() {
        List<ReferenceItem> items = List.of(readmeItem("GT-01", "A", "a"));
        // a deliberately order-sensitive screen: it only ever suggests the item for the FIRST finding in the list
        FakeLlmClient c = new FakeLlmClient(req -> req.metadata().purpose().equals("judge_screen")
                ? FakeLlmClient.ok("{\"results\": [{\"finding\": \"F1\", \"items\": [\"GT-01\"]}]}")
                : FakeLlmClient.ok("{\"verdict\": \"match\", \"reason\": \"r\"}"));
        List<Finding> fs = List.of(finding("f1", "a1", "CWE-1", "X.a", "verified_exact"), finding("f2", "a2", "CWE-1", "X.b", "verified_exact"),
                finding("f3", "a3", "CWE-1", "X.c", "verified_exact"), finding("f4", "a4", "CWE-1", "X.d", "verified_exact"));
        MatchingPipeline.Decisions one = pipeline(c, List.of()).decide(items, fs, false, "t");
        assertThat(one.screened().get("GT-01")).hasSize(1);
        assertThat(one.screenExtraPairs()).isZero();
        MatchingPipeline.Decisions three = pipeline(c, List.of()).withScreenOrders(3).decide(items, fs, false, "t");
        assertThat(three.screened().get("GT-01")).hasSizeGreaterThan(1);
        assertThat(three.screenExtraPairs()).isEqualTo(three.screened().get("GT-01").size() - 1);
    }

    @Test
    void edgeCasesZeroFindingsAndZeroItems() {
        Evaluator ev = new Evaluator(pipeline(judge(u -> "match"), List.of()));
        var src = new ReferenceSource() {
            public String name() { return "readme"; }
            public List<ReferenceItem> items() { return List.of(readmeItem("GT-01", "A", "a")); }
            public boolean isGroundTruth() { return true; }
        };
        Metrics.ArmRun none = ev.evaluate(src, List.of(new Evaluator.ArmRunInput("a", 1, "m", List.of(), Map.of())), false).metrics().armRuns().get(0);
        assertThat(none.recallStrict()).isZero();
        assertThat(none.precisionStrict()).isNull();
        var empty = new ReferenceSource() {
            public String name() { return "readme"; }
            public List<ReferenceItem> items() { return List.of(); }
            public boolean isGroundTruth() { return true; }
        };
        Metrics.ArmRun noItems = ev.evaluate(empty, List.of(new Evaluator.ArmRunInput("a", 1, "m",
                List.of(finding("f", "x", "CWE-1", "X.a", "verified_exact")), Map.of())), false).metrics().armRuns().get(0);
        assertThat(noItems.recallStrict()).isZero();
    }

    @Test
    void teacherAsReferenceIsLabelledAgreementWithoutPrecision() {
        Finding t = finding("t1", "WebView", "CWE-749", "WebActivity.onCreate", "verified_exact");
        TeacherFindingsReference ref = new TeacherFindingsReference(List.of(t));
        assertThat(ref.isGroundTruth()).isFalse();
        assertThat(ref.items().get(0).locations().get(0).clazz()).isEqualTo("WebActivity");
        Evaluator ev = new Evaluator(pipeline(judge(u -> "match"), List.of()));
        Metrics.Mode m = ev.evaluate(ref, List.of(new Evaluator.ArmRunInput("student_baseline", 1, "m",
                List.of(finding("s1", "WebView", "CWE-749", "WebActivity.onCreate", "verified_exact")), Map.of())), false).metrics();
        assertThat(m.label()).isEqualTo("agreement with teacher");
        assertThat(m.armRuns().get(0).precisionStrict()).isNull();
        assertThat(m.armRuns().get(0).recallStrict()).isEqualTo(1.0);
    }

    @Test
    void unparseableJudgeAnswerCountsAsNoWithAnError() {
        FakeLlmClient broken = FakeLlmClient.constant("I think so");
        Evaluator ev = new Evaluator(pipeline(broken, List.of()));
        var src = new ReferenceSource() {
            public String name() { return "readme"; }
            public List<ReferenceItem> items() { return List.of(readmeItem("GT-01", "A", "a")); }
            public boolean isGroundTruth() { return true; }
        };
        Metrics.ArmRun r = ev.evaluate(src, List.of(new Evaluator.ArmRunInput("a", 1, "m",
                List.of(finding("f", "x", "CWE-1", "X.a", "verified_exact")), Map.of())), false).metrics().armRuns().get(0);
        assertThat(r.recallStrict()).isZero();
        assertThat(r.judgeErrors()).isEqualTo(1); // the screen failed (after one repair)
    }
}
