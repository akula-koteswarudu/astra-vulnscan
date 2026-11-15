package com.vulnscan.evaluate;

import com.vulnscan.common.Hashing;
import com.vulnscan.matching.ReferenceItem;
import com.vulnscan.scan.Finding;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Hand audit of the judge (plan 6.8). Export: every confirm decision of the core arms, plus, for every item an arm left
 * unmatched, that arm's findings the screen discarded for it (so missed matches are measured too). Rows are blind (no
 * verdict, arm or model), identical (item, finding text) pairs appear once, and the order is shuffled with a fixed seed.
 * The key (row -> source, judge verdict, arms) is kept in a separate file. Score: raw agreement on match/partial/no,
 * a confusion matrix, agreement with partial collapsed into match, and Cohen's kappa (only next to raw agreement).
 */
public class JudgeAudit {

    public static final List<String> LABELS = List.of("match", "partial", "no");

    /** One row to label. */
    public record Row(String rowId, String itemId, String itemTitle, String itemDescription, String findingTitle, String findingCwe,
                      String findingLocation, String findingEvidence, String findingRationale) {
    }

    /** What a row stands for. source: confirm | screened_out; judgeVerdict for screened_out rows is "no" (never confirmed). */
    public record KeyEntry(String rowId, String source, String judgeVerdict, List<String> arms) {
    }

    public record Export(List<Row> rows, List<KeyEntry> key) {
    }

    /** One arm run's material for the audit. */
    public record ArmInput(String arm, List<Finding> findings, Map<String, List<String>> screened,
                           Map<String, String> verdictByPair, Map<String, String> matched) {
    }

    public static Export export(List<ReferenceItem> items, List<ArmInput> arms, long seed) {
        Map<String, Row> rows = new LinkedHashMap<>();
        Map<String, KeyEntry> key = new LinkedHashMap<>();
        for (ArmInput a : arms) {
            for (ReferenceItem item : items) {
                List<String> screenedIds = a.screened().getOrDefault(item.id(), List.of());
                for (Finding f : a.findings()) {
                    String verdict = a.verdictByPair().get(item.id() + "|" + f.id());
                    boolean confirmed = verdict != null;
                    boolean screenedOut = !screenedIds.contains(f.id()) && !a.matched().containsKey(item.id());
                    if (!confirmed && !screenedOut) {
                        continue;
                    }
                    Row row = row(item, f);
                    String source = confirmed ? "confirm" : "screened_out";
                    rows.putIfAbsent(row.rowId(), row);
                    KeyEntry prev = key.get(row.rowId());
                    List<String> armsList = prev == null ? new ArrayList<>() : new ArrayList<>(prev.arms());
                    if (!armsList.contains(a.arm())) {
                        armsList.add(a.arm());
                    }
                    // a pair confirmed in any arm keeps its confirm verdict (identical text -> identical cached verdict)
                    if (prev == null || prev.source().equals("screened_out") && confirmed) {
                        key.put(row.rowId(), new KeyEntry(row.rowId(), source, confirmed ? verdict : "no", armsList));
                    } else {
                        key.put(row.rowId(), new KeyEntry(row.rowId(), prev.source(), prev.judgeVerdict(), armsList));
                    }
                }
            }
        }
        List<Row> shuffled = new ArrayList<>(rows.values());
        Collections.shuffle(shuffled, new Random(seed));
        return new Export(shuffled, new ArrayList<>(key.values()));
    }

    static Row row(ReferenceItem item, Finding f) {
        String where = (f.location().component() == null ? "" : f.location().component() + " / ")
                + (f.location().symbol() != null ? f.location().symbol() : f.location().file());
        String id = Hashing.sha256(item.id() + "|" + f.title() + "|" + f.cwe() + "|" + where + "|" + f.evidence() + "|" + f.rationale())
                .substring(0, 12);
        return new Row(id, item.id(), item.title(), item.description(), f.title(), f.cwe(), where, f.evidence().strip(),
                f.rationale() == null ? "" : f.rationale().strip());
    }

    /** Agreement between hand labels and the judge. Rows without a valid label are skipped and counted. */
    public record Score(int labelled, int unlabelled, double rawAgreement, double collapsedAgreement, Double cohensKappa,
                        Map<String, Map<String, Integer>> confusion, int screenedOutLabelledMatch,
                        List<Map<String, String>> disagreements) {
    }

    public static Score score(Map<String, String> labels, List<KeyEntry> key, Map<String, Row> rowsById) {
        Map<String, Map<String, Integer>> confusion = new LinkedHashMap<>();
        for (String judge : LABELS) {
            Map<String, Integer> r = new LinkedHashMap<>();
            LABELS.forEach(h -> r.put(h, 0));
            confusion.put(judge, r);
        }
        int n = 0;
        int agree = 0;
        int agreeCollapsed = 0;
        int unlabelled = 0;
        int screenedMatch = 0;
        List<Map<String, String>> disagreements = new ArrayList<>();
        for (KeyEntry k : key) {
            String human = labels.getOrDefault(k.rowId(), "").strip().toLowerCase();
            if (!LABELS.contains(human)) {
                unlabelled++;
                continue;
            }
            n++;
            String judge = k.judgeVerdict();
            confusion.get(judge).merge(human, 1, Integer::sum);
            if (judge.equals(human)) {
                agree++;
            } else {
                Row r = rowsById.get(k.rowId());
                Map<String, String> d = new LinkedHashMap<>();
                d.put("row_id", k.rowId());
                d.put("source", k.source());
                d.put("item", r == null ? "" : r.itemId());
                d.put("finding", r == null ? "" : r.findingTitle());
                d.put("judge", judge);
                d.put("label", human);
                disagreements.add(d);
            }
            if (!judge.equals("no") == !human.equals("no")) {
                agreeCollapsed++;
            }
            if (k.source().equals("screened_out") && human.equals("match")) {
                screenedMatch++;
            }
        }
        return new Score(n, unlabelled, n == 0 ? 0 : agree / (double) n, n == 0 ? 0 : agreeCollapsed / (double) n,
                kappa(confusion, n), confusion, screenedMatch, disagreements);
    }

    /** Cohen's kappa over the 3-class confusion matrix (rows: judge, columns: hand label). */
    static Double kappa(Map<String, Map<String, Integer>> c, int n) {
        if (n == 0) {
            return null;
        }
        double po = 0;
        double pe = 0;
        for (String l : LABELS) {
            po += c.get(l).get(l);
            double judgeTotal = c.get(l).values().stream().mapToInt(Integer::intValue).sum();
            double humanTotal = LABELS.stream().mapToInt(j -> c.get(j).get(l)).sum();
            pe += (judgeTotal / n) * (humanTotal / n);
        }
        po /= n;
        return pe >= 1 ? null : (po - pe) / (1 - pe);
    }
}
