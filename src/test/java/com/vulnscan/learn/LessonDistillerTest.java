package com.vulnscan.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vulnscan.config.ModelsConfig;
import com.vulnscan.models.LlmResponse;
import com.vulnscan.prompts.PromptLibrary;
import com.vulnscan.scan.Finding;
import com.vulnscan.testing.FakeLlmClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LessonDistillerTest {

    static final ModelsConfig.ModelSpec SPEC = new ModelsConfig.ModelSpec("anthropic", null, "t", "K", 100000, null, 1000, null, null,
            Map.of(), new ModelsConfig.Price(0, 0), new ModelsConfig.RateLimit(10, null, null, 100000, null, null), "max_tokens", 1, 0,
            new ModelsConfig.Retry(1, 1));
    static final String GOOD = "{\"title\": \"T\", \"why_exploitable\": \"W\", \"signals\": [], \"source_finding_ids\": []}";
    static final String EMPTY = "{\"title\": \"placeholder\", \"why_exploitable\": \"\"}";

    static Finding finding(String id, String cwe, String cls, double conf) {
        return new Finding(id, "t", cls, cwe, null, "high", conf, new Finding.Location("f", null, "A.b", 1, 1), "e", "r", "code",
                "verified_exact", false, new Finding.Provenance("m", "teacher", "r", 0, List.of("u"), "v1"));
    }

    LessonDistiller distiller(FakeLlmClient c, int maxInput) {
        return new LessonDistiller(c, SPEC, new PromptLibrary(Path.of("prompts")).get("distill", "v2"), maxInput, 15);
    }

    @Test
    void degenerateAnswerGetsTheOneRepairCall() {
        AtomicInteger n = new AtomicInteger();
        FakeLlmClient c = new FakeLlmClient(r -> FakeLlmClient.ok(n.incrementAndGet() == 1
                ? "{\"lessons\": [" + GOOD + ", " + EMPTY + "]}" : "{\"lessons\": [" + GOOD + ", " + GOOD + "]}"));
        LessonDistiller.Result r = distiller(c, 60).distill(List.of(finding("f1", "CWE-1", "x", 0.5)), "run");
        assertThat(r.lessons()).hasSize(2).extracting(Lesson::id).containsExactly("L01", "L02");
        assertThat(c.calls()).isEqualTo(2);
        assertThat(c.requests.get(1).metadata().purpose()).isEqualTo("repair");
    }

    @Test
    void sameFindingsFromAnotherRunGiveTheSamePromptAndSourceIdsMapBack() {
        FakeLlmClient c = FakeLlmClient.constant("{\"lessons\": [{\"title\": \"T\", \"why_exploitable\": \"W\", \"source_finding_ids\": [\"F2\"]}]}");
        LessonDistiller d = distiller(c, 60);
        LessonDistiller.Result a = d.distill(List.of(finding("run1-x", "CWE-1", "x", 0.5), finding("run1-y", "CWE-2", "y", 0.6)), "run1");
        d.distill(List.of(finding("run2-y", "CWE-2", "y", 0.6), finding("run2-x", "CWE-1", "x", 0.5)), "run2");
        assertThat(c.requests.get(0).messages()).isEqualTo(c.requests.get(1).messages());
        assertThat(c.requests.get(0).messages().get(0).content()).doesNotContain("run1-");
        assertThat(a.lessons().get(0).sourceFindingIds()).hasSize(1).first().asString().startsWith("run1-");
    }

    @Test
    void refusalAndPersistentFailureMeanNoLessons() {
        assertThatThrownBy(() -> distiller(new FakeLlmClient(r -> FakeLlmClient.withStop("", LlmResponse.StopReason.REFUSAL)), 60)
                .distill(List.of(finding("f1", "CWE-1", "x", 0.5)), "run")).isInstanceOf(NoLessonsException.class);
        assertThatThrownBy(() -> distiller(FakeLlmClient.constant("{\"lessons\": [" + EMPTY + "]}"), 60)
                .distill(List.of(finding("f1", "CWE-1", "x", 0.5)), "run")).isInstanceOf(NoLessonsException.class);
        assertThatThrownBy(() -> distiller(FakeLlmClient.constant("{}"), 60).distill(List.of(), "run")).isInstanceOf(NoLessonsException.class);
    }

    @Test
    void inputAboveTheCapIsDedupedByPatternAndCapped() {
        List<Finding> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(finding("a" + i, "CWE-79", "xss", 0.1 * i));
        }
        many.add(finding("b", "CWE-89", "sqli", 0.2));
        many.add(finding("c", "CWE-22", "path", 0.3));
        List<Finding> capped = LessonDistiller.capByPattern(many, 2);
        assertThat(capped).extracting(Finding::id).containsExactly("a9", "c");
        FakeLlmClient c = FakeLlmClient.constant("{\"lessons\": [" + GOOD + "]}");
        assertThat(distiller(c, 2).distill(many, "run").cappedFrom()).isEqualTo(12);
    }
}
