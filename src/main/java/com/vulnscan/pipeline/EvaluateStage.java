package com.vulnscan.pipeline;

import com.vulnscan.config.AppConfig;
import com.vulnscan.evaluate.Evaluator;
import com.vulnscan.evaluate.GroundTruth;
import com.vulnscan.evaluate.GroundTruthLoader;
import com.vulnscan.evaluate.GroundTruthReference;
import com.vulnscan.evaluate.Metrics;
import com.vulnscan.ingest.AppModel;
import com.vulnscan.matching.Judge;
import com.vulnscan.matching.MatchingPipeline;
import com.vulnscan.matching.ReferenceSource;
import com.vulnscan.matching.StructuredScorer;
import com.vulnscan.matching.TeacherFindingsReference;
import com.vulnscan.models.UsageTracker;
import com.vulnscan.scan.Deduplicator;
import com.vulnscan.scan.Finding;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Stage "evaluate" (offline, from saved findings). With ground truth: the headline mode (readme by default) and the
 * other mode as the robustness check; the teacher is evaluated too (ceiling). Without ground truth: the teacher's
 * verified findings are the reference and every number is "agreement with teacher".
 */
public class EvaluateStage {

    public void evaluate(Experiment e) {
        AppConfig cfg = e.config().app();
        UsageTracker usage = new UsageTracker();
        String judgeName = e.config().models().roles().judge();
        AppModel app = e.app().model();
        List<String> appNames = new ArrayList<>();
        app.firstPartySources().forEach(s -> appNames.add(s.className().substring(s.className().lastIndexOf('.') + 1)));
        app.manifest().components().forEach(c -> appNames.add(c.name().substring(c.name().lastIndexOf('.') + 1)));
        app.resources().strings().forEach(s -> appNames.add(s.name()));

        MatchingPipeline matching = new MatchingPipeline(new StructuredScorer(e.config().matching(), appNames),
                new Judge(e.models().forModel(judgeName, usage), e.spec(judgeName), e.prompts().get("judge_screen", "v1"),
                        e.prompts().get("judge_confirm", "v1")),
                e.config().matching().prefilterMinScore(), cfg.evaluate().screenBatchSize(), e.spec(judgeName).concurrency())
                .withScreenOrders(cfg.evaluate().screenOrders());
        Evaluator evaluator = new Evaluator(matching);

        List<Evaluator.ArmRunInput> students = new ArrayList<>();
        for (Arm arm : List.of(Arm.STUDENT_BASELINE, Arm.STUDENT_WITH_LESSONS)) {
            for (int k : e.runsOf(arm)) {
                students.add(input(e, arm, k));
            }
        }
        List<Evaluator.ArmRunInput> teacher = new ArrayList<>();
        for (int k : e.runsOf(Arm.TEACHER)) {
            teacher.add(input(e, Arm.TEACHER, k));
        }

        List<Evaluator.ModeResult> results = new ArrayList<>();
        String headline;
        boolean groundTruth = cfg.app().groundTruth() != null;
        try {
            if (groundTruth) {
                GroundTruth gt = new GroundTruthLoader().load(Path.of(cfg.app().groundTruth()).toAbsolutePath());
                headline = cfg.evaluate().headline() == AppConfig.Headline.ENRICHED ? "enriched" : "readme";
                boolean hasEnrichment = gt.items().stream().anyMatch(i -> i.enrichment() != null);
                List<String> modes = new ArrayList<>(List.of(headline));
                String other = headline.equals("readme") ? "enriched" : "readme";
                if (hasEnrichment) {
                    modes.add(other); // the robustness check
                }
                List<Evaluator.ArmRunInput> all = new ArrayList<>(teacher);
                all.addAll(students);
                for (String mode : modes) {
                    e.progress().accept("evaluate: " + mode + " reference, " + all.size() + " arm run(s), judge " + e.spec(judgeName).model());
                    boolean enriched = mode.equals("enriched");
                    results.add(evaluator.evaluate(new GroundTruthReference(gt, enriched), all, enriched));
                }
            } else {
                headline = "teacher_findings";
                List<Finding> teacherVerified = new ArrayList<>();
                teacher.forEach(t -> t.findings().stream().filter(Finding::isVerified).forEach(teacherVerified::add));
                ReferenceSource ref = new TeacherFindingsReference(new Deduplicator(e.config().matching().cweGroups()).dedup(teacherVerified));
                e.progress().accept("evaluate: no ground truth; teacher findings are the reference (agreement with teacher)");
                results.add(evaluator.evaluate(ref, students, false));
            }
        } finally {
            e.store().writeJson("07_evaluate/usage.json", usage.snapshot());
        }

        checkJudgeHealth(results);
        List<Metrics.Mode> modes = results.stream().map(Evaluator.ModeResult::metrics).toList();
        Metrics.Lift lift = Evaluator.lift(modes.get(0));
        Metrics metrics = new Metrics(1, headline, modes.get(0).label(), groundTruth, modes, lift);
        e.store().writeJson("07_evaluate/metrics.json", metrics);
        Map<String, Object> matches = new LinkedHashMap<>();
        Map<String, Object> decisions = new LinkedHashMap<>();
        for (Evaluator.ModeResult r : results) {
            matches.put(r.metrics().mode(), r.assignments());
            decisions.put(r.metrics().mode(), r.decisions());
        }
        e.store().writeJson("07_evaluate/matches.json", matches);
        e.store().writeJson("07_evaluate/judge_decisions.json", decisions);
        e.progress().accept(summary(metrics));
    }

    /** Share of judge calls that may fail before the evaluation is refused (a broken judge must not look like 0% recall). */
    static final double MAX_JUDGE_ERROR_RATE = 0.2;

    static void checkJudgeHealth(List<Evaluator.ModeResult> results) {
        int calls = 0;
        int errors = 0;
        String first = null;
        for (Evaluator.ModeResult r : results) {
            for (var d : r.decisions().values()) {
                int screens = d.screened().isEmpty() ? 0 : 1;
                calls += d.verdicts().size() + Math.max(screens, d.errors().isEmpty() ? 0 : 1);
                errors += d.errors().size();
                if (first == null && !d.errors().isEmpty()) {
                    first = d.errors().get(0);
                }
            }
        }
        if (errors > 0 && (calls == 0 || errors > MAX_JUDGE_ERROR_RATE * Math.max(calls, errors))) {
            throw new com.vulnscan.UserFacingException("the judge is failing (" + errors + " errors in about " + Math.max(calls, errors)
                    + " calls); not writing metrics. First error: " + first);
        }
    }

    static String summary(Metrics m) {
        Metrics.Mode head = m.modes().get(0);
        String perArm = head.armRuns().stream()
                .map(r -> r.arm() + " run " + r.run() + " " + Math.round(r.recallStrict() * head.items()) + "/" + head.items())
                .collect(Collectors.joining(", "));
        int extra = m.modes().stream().flatMap(x -> x.armRuns().stream()).mapToInt(Metrics.ArmRun::screenExtraPairs).sum();
        return "evaluate (" + head.mode() + ", " + head.label() + ", strict, verified): " + perArm
                + "; quick-look pairs found only by extra orders: " + extra
                + (m.lift().lift() == null ? "" : String.format("; lift %+.1f points", 100 * m.lift().lift()));
    }

    private static Evaluator.ArmRunInput input(Experiment e, Arm arm, int run) {
        String dir = Experiment.armDir(arm, run);
        ArmOutput out = e.store().readJson(dir + "findings.json", ArmOutput.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> results = e.store().readJson(dir + "results.json", Map.class);
        Map<String, Long> status = new LinkedHashMap<>();
        for (Object u : (List<?>) results.get("units")) {
            String s = String.valueOf(((Map<?, ?>) u).get("status"));
            status.merge(s, 1L, Long::sum);
        }
        return new Evaluator.ArmRunInput(arm.id, run, out.model(), out.findings(), status);
    }
}
