package com.vulnscan.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.vulnscan.matching.ReferenceItem;
import com.vulnscan.scan.Finding;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JudgeAuditTest {

    static ReferenceItem item(String id) {
        return new ReferenceItem(id, "title " + id, "desc " + id, null, null, null, null, null);
    }

    static Finding f(String id, String title, String arm) {
        return new Finding(id, title, "c", "CWE-1", null, "high", 0.5, new Finding.Location("a.java", null, "A.b", 1, 1), "evidence",
                "why", "code", "verified_exact", false, new Finding.Provenance("secret-model", arm, "r", 0, List.of("u"), "v1"));
    }

    @Test
    void exportIsBlindDedupedAndIncludesScreenedOutPairsOfUnmatchedItems() {
        List<ReferenceItem> items = List.of(item("GT-01"), item("GT-02"));
        // both arms report the same finding text; GT-01 confirmed+matched, GT-02 unmatched and never shortlisted
        JudgeAudit.ArmInput a = new JudgeAudit.ArmInput("baseline/run_1", List.of(f("a1", "same", "baseline")),
                Map.of("GT-01", List.of("a1")), Map.of("GT-01|a1", "match"), Map.of("GT-01", "a1"));
        JudgeAudit.ArmInput b = new JudgeAudit.ArmInput("lessons/run_1", List.of(f("b1", "same", "lessons")),
                Map.of("GT-01", List.of("b1")), Map.of("GT-01|b1", "match"), Map.of("GT-01", "b1"));
        JudgeAudit.Export ex = JudgeAudit.export(items, List.of(a, b), 42);
        assertThat(ex.rows()).hasSize(2); // GT-01 pair (shared by both arms) + GT-02 screened-out pair (shared)
        assertThat(ex.key()).extracting(JudgeAudit.KeyEntry::source).containsExactlyInAnyOrder("confirm", "screened_out");
        assertThat(ex.key()).allSatisfy(k -> assertThat(k.arms()).containsExactly("baseline/run_1", "lessons/run_1"));
        assertThat(ex.rows().toString()).doesNotContain("secret-model", "match", "baseline", "lessons");
    }

    @Test
    void scoreComputesAgreementKappaAndMissedScreenMatches() {
        List<JudgeAudit.KeyEntry> key = List.of(
                new JudgeAudit.KeyEntry("r1", "confirm", "match", List.of()),
                new JudgeAudit.KeyEntry("r2", "confirm", "partial", List.of()),
                new JudgeAudit.KeyEntry("r3", "confirm", "no", List.of()),
                new JudgeAudit.KeyEntry("r4", "screened_out", "no", List.of()));
        Map<String, String> labels = new HashMap<>(Map.of("r1", "match", "r2", "match", "r3", "no", "r4", "match"));
        JudgeAudit.Score s = JudgeAudit.score(labels, key, Map.of());
        assertThat(s.labelled()).isEqualTo(4);
        assertThat(s.rawAgreement()).isEqualTo(0.5);
        assertThat(s.collapsedAgreement()).isEqualTo(0.75);
        assertThat(s.screenedOutLabelledMatch()).isEqualTo(1);
        assertThat(s.disagreements()).hasSize(2);
        assertThat(s.confusion().get("partial").get("match")).isEqualTo(1);
        assertThat(s.cohensKappa()).isNotNull();
        // perfect agreement -> kappa 1
        JudgeAudit.Score perfect = JudgeAudit.score(Map.of("r1", "match", "r2", "partial", "r3", "no", "r4", "no"), key, Map.of());
        assertThat(perfect.cohensKappa()).isCloseTo(1.0, within(1e-9));
        labels.put("r1", "");
        assertThat(JudgeAudit.score(labels, key, Map.of()).unlabelled()).isEqualTo(1);
    }
}
