package com.vulnscan.evaluate;

import com.vulnscan.matching.MatchingPipeline;
import com.vulnscan.matching.ReferenceItem;
import com.vulnscan.matching.ReferenceSource;
import com.vulnscan.scan.Finding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes metrics for every (reference mode, arm, run) from saved findings. Runs offline: changing the matcher never
 * needs a rescan. The judge decides on ALL findings once per (mode, arm, run); the verified-only and all-findings views
 * and the strict/lenient variants are different assignments over the same decisions.
 */
public class Evaluator {

    private final MatchingPipeline matching;

    public Evaluator(MatchingPipeline matching) {
        this.matching = matching;
    }

    /** One arm run's input. unitStatus = counts of unit statuses (coverage). */
    public record ArmRunInput(String arm, int run, String model, List<Finding> findings, Map<String, Long> unitStatus) {
    }

    /** Everything one mode produced: metrics plus the raw decisions and assignments (saved for audit). */
    public record ModeResult(Metrics.Mode metrics, Map<String, MatchingPipeline.Decisions> decisions,
                             Map<String, Map<String, Object>> assignments) {
    }

    public ModeResult evaluate(ReferenceSource reference, List<ArmRunInput> inputs, boolean withEnrichment) {
        List<ReferenceItem> items = reference.items();
        boolean gt = reference.isGroundTruth();
        String label = gt ? "recall" : "agreement with teacher";
        List<Metrics.ArmRun> rows = new ArrayList<>();
        Map<String, MatchingPipeline.Decisions> decisions = new LinkedHashMap<>();
        Map<String, Map<String, Object>> assignments = new LinkedHashMap<>();
        for (ArmRunInput in : inputs) {
            String key = in.arm() + "/run_" + in.run();
            MatchingPipeline.Decisions d = matching.decide(items, in.findings(), withEnrichment, reference.name() + ":" + in.arm());
            decisions.put(key, d);
            List<Finding> verified = in.findings().stream().filter(Finding::isVerified).toList();
            Map<String, String> strict = MatchingPipeline.assign(items, verified, d, false);
            Map<String, String> lenient = MatchingPipeline.assign(items, verified, d, true);
            Map<String, String> strictAll = MatchingPipeline.assign(items, in.findings(), d, false);
            Double pStrict = null;
            Double pLenient = null;
            if (gt) {
                Set<String> anyStrict = MatchingPipeline.matchedAny(items, verified, d, false);
                Set<String> anyLenient = MatchingPipeline.matchedAny(items, verified, d, true);
                pStrict = verified.isEmpty() ? null : anyStrict.size() / (double) verified.size();
                pLenient = verified.isEmpty() ? null : anyLenient.size() / (double) verified.size();
            }
            int n = items.size();
            rows.add(new Metrics.ArmRun(in.arm(), in.run(), in.model(), in.findings().size(), verified.size(),
                    ratio(strict.size(), n), ratio(lenient.size(), n), ratio(strictAll.size(), n), pStrict, pLenient,
                    strict, lenient, MatchingPipeline.uncredited(items, verified, d, strict), in.unitStatus(), d.verdicts().size(), d.errors().size(), d.prefilterDropped()));
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("strict_verified", strict);
            a.put("lenient_verified", lenient);
            a.put("strict_all", strictAll);
            assignments.put(key, a);
        }
        return new ModeResult(new Metrics.Mode(reference.name(), label, items.size(), rows, perItem(items, rows)), decisions, assignments);
    }

    static List<Metrics.ItemRow> perItem(List<ReferenceItem> items, List<Metrics.ArmRun> rows) {
        List<Metrics.ItemRow> out = new ArrayList<>();
        for (ReferenceItem item : items) {
            Map<String, int[]> strict = new LinkedHashMap<>();
            Map<String, int[]> lenient = new LinkedHashMap<>();
            Map<String, int[]> uncredited = new LinkedHashMap<>();
            for (Metrics.ArmRun r : rows) {
                strict.computeIfAbsent(r.arm(), k -> new int[2]);
                lenient.computeIfAbsent(r.arm(), k -> new int[2]);
                strict.get(r.arm())[1]++;
                lenient.get(r.arm())[1]++;
                if (r.matchedStrict().containsKey(item.id())) {
                    strict.get(r.arm())[0]++;
                }
                if (r.matchedLenient().containsKey(item.id())) {
                    lenient.get(r.arm())[0]++;
                }
                uncredited.computeIfAbsent(r.arm(), k -> new int[2])[1]++;
                if (r.uncreditedStrict().contains(item.id())) {
                    uncredited.get(r.arm())[0]++;
                }
            }
            out.add(new Metrics.ItemRow(item.id(), item.title(), item.staticVisibility(), fraction(strict), fraction(lenient),
                    fraction(uncredited)));
        }
        return out;
    }

    /** Headline lift: mean recall per arm with its range; lift = with_lessons - baseline. */
    public static Metrics.Lift lift(Metrics.Mode mode) {
        Map<String, List<Double>> byArm = new LinkedHashMap<>();
        mode.armRuns().forEach(r -> byArm.computeIfAbsent(r.arm(), k -> new ArrayList<>()).add(r.recallStrict()));
        Map<String, Metrics.Stat> stats = new LinkedHashMap<>();
        byArm.forEach((arm, v) -> stats.put(arm, new Metrics.Stat(v.stream().mapToDouble(x -> x).average().orElse(0),
                v.stream().mapToDouble(x -> x).min().orElse(0), v.stream().mapToDouble(x -> x).max().orElse(0), v.size())));
        Metrics.Stat base = stats.get("student_baseline");
        Metrics.Stat with = stats.get("student_with_lessons");
        return new Metrics.Lift(mode.mode(), stats, base == null || with == null ? null : with.mean() - base.mean());
    }

    private static Map<String, String> fraction(Map<String, int[]> counts) {
        Map<String, String> out = new LinkedHashMap<>();
        counts.forEach((arm, c) -> out.put(arm, c[0] + "/" + c[1]));
        return out;
    }

    private static double ratio(int a, int b) {
        return b == 0 ? 0 : a / (double) b;
    }
}
