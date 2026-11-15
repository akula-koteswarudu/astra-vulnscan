package com.vulnscan.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.vulnscan.common.Json;
import com.vulnscan.config.AppConfig;
import com.vulnscan.evaluate.Metrics;
import com.vulnscan.ingest.AppModel;
import com.vulnscan.learn.LessonFile;
import com.vulnscan.learn.Violation;
import com.vulnscan.models.UsageTracker;
import com.vulnscan.planning.UnitPlan;
import com.vulnscan.report.MarkdownReport;
import com.vulnscan.scan.Finding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Stage "report": reads the run folder and writes report.md (can be re-run offline from saved data). */
public class ReportStage {

    static final List<String> LIMITATIONS = List.of(
            "The lift is in-sample: lessons are distilled from the same app the student is rescanned on. One app cannot show that the lessons generalise (cross-app transfer is the partial answer).",
            "Contamination: the evaluation app and its vulnerability list are public; the models may have memorised them.",
            "Some ingest features (dependency inventory, cloud-identifier patterns) were added after reading the ground-truth list; they are generic, but not chosen blind.",
            "Prompts were developed on this app (versions and scores are recorded in section 8).",
            "Ground-truth enrichment is the author's own labelling (mitigated: written and committed before any scan, README-only headline, robustness check).",
            "Some items are only partly visible statically (server-side cloud configuration; known-vulnerable components rely on the model's advisory knowledge).",
            "The teacher's temperature cannot be set (Claude Sonnet 5.5 rejects non-default sampling); LLM output varies between runs; small N; no significance test.",
            "Teacher refusals can reduce coverage (counted; the model that answered is recorded when a fallback was used).",
            "Teacher and student may scan different units because of different budgets; the student's free tier (1,000 output tokens per minute) forces small units, and units whose answer does not fit are re-planned into smaller ones.",
            "The judge is an LLM; the hand audit measures its agreement with the author, it does not remove bias.",
            "One-to-one matching is conservative.",
            "Unmatched findings are not automatically false positives (the reference list may be incomplete).",
            "Teacher-as-reference (apps without ground truth) inherits the teacher's misses and mistakes and is circular with the lessons.",
            "Decompilation is lossy (jadx failures are counted); the smali fallback is not built in v0.1.",
            "Obfuscated apps: first-party code is derived from the manifest, but library detection is not fingerprint-based.",
            "Native code, assets and split APKs / app bundles are not analysed.",
            "Text-based one-hop graph: reflection, framework callbacks and string-action intents are missed; oversized files are skipped; no hub/skeleton handling in v0.1.",
            "strings.xml filtering can miss innocently named, low-entropy secrets.",
            "Prompt injection via app code or strings can still influence the model.",
            "Model confidence is uncalibrated (used for ranking only).",
            "The sanitiser is lexical plus a similarity check; paraphrased references can pass.",
            "Lessons cover only the vulnerability classes the teacher found in the source app.",
            "Line numbers refer to decompiled code.",
            "Static only: runtime-only issues are out of scope (see the dynamic-analysis write-up).");

    public void report(Experiment e) {
        RunStore s = e.store();
        AppConfig cfg = e.config().app();
        Metrics metrics = s.readJson("07_evaluate/metrics.json", Metrics.class);
        LessonFile lessons = s.readJson("05_learn/lessons.json", LessonFile.class);
        List<Violation> violations = new ArrayList<>();
        int droppedSanitiser = 0;
        int droppedCaps = 0;
        String policy = cfg.distill().sanitizer().onViolation().name().toLowerCase();
        if (s.exists("05_learn/rejected_lessons.json")) {
            Map<String, Object> rejected = read(s.path("05_learn/rejected_lessons.json"), new TypeReference<>() { });
            violations = Json.json().convertValue(rejected.get("violations"), new TypeReference<List<Violation>>() { });
            droppedSanitiser = ((List<?>) rejected.get("dropped_by_sanitiser")).size();
            droppedCaps = ((List<?>) rejected.get("dropped_by_caps")).size();
        }

        Map<String, MarkdownReport.Coverage> coverage = new LinkedHashMap<>();
        for (Arm arm : Arm.values()) {
            String dir = Experiment.armDir(arm, 1);
            if (!s.exists(dir + "results.json")) {
                continue;
            }
            Map<String, Object> results = read(s.path(dir + "results.json"), new TypeReference<>() { });
            Map<String, Long> status = new TreeMap<>();
            int bad = 0;
            List<?> units = (List<?>) results.get("units");
            for (Object u : units) {
                Map<?, ?> m = (Map<?, ?>) u;
                status.merge(String.valueOf(m.get("status")), 1L, Long::sum);
                bad += ((Number) m.get("bad_findings")).intValue();
            }
            List<Finding> findings = s.readJson(dir + "findings.json", ArmOutput.class).findings();
            Map<String, Long> evidence = findings.stream().collect(Collectors.groupingBy(Finding::evidenceStatus, TreeMap::new, Collectors.counting()));
            coverage.put(arm.id, new MarkdownReport.Coverage(units.size(), status, evidence, bad));
        }

        AppModel model = s.readJson("02_ingest/app_model.json", AppModel.class);
        Map<String, Object> dec = read(s.path("01_decompile/stats.json"), new TypeReference<>() { });
        Map<?, ?> st = (Map<?, ?>) dec.get("stats");
        AppModel.Stats ms = model.stats();
        String topExcluded = ms.topExcludedPackages().stream().limit(5).map(p -> p.packageName() + " (" + p.files() + ")")
                .collect(Collectors.joining(", "));
        MarkdownReport.Decompile decompile = new MarkdownReport.Decompile(String.valueOf(dec.get("tool_version")),
                ((Number) st.get("exit_code")).intValue(), Boolean.TRUE.equals(st.get("partial")), ((Number) st.get("methods_failed")).intValue(),
                ms.methodsFailed(), ms.filesFirstParty(), ms.filesTotal(), ms.firstPartyPrefixes(), ms.filesGeneratedExcluded(), topExcluded);

        UnitPlan teacherPlan = s.readJson("03_plan/units_teacher.json", UnitPlan.class);
        UnitPlan studentPlan = s.readJson("03_plan/units_student.json", UnitPlan.class);
        Map<String, Object> manifest = read(s.path("manifest.json"), new TypeReference<>() { });
        @SuppressWarnings("unchecked")
        Map<String, String> prompts = new TreeMap<>((Map<String, String>) manifest.getOrDefault("prompts", Map.of()));

        String audit = auditSentence(s);
        MarkdownReport.Inputs in = new MarkdownReport.Inputs(cfg.app().name(), s.runId(), cfg.profile().name().toLowerCase(), metrics,
                lessons, violations, policy, droppedSanitiser, droppedCaps, usage(s.dir()), coverage, decompile, prompts,
                teacherPlan.mode(), teacherPlan.units().size(), studentPlan.mode(), studentPlan.units().size(), audit, LIMITATIONS,
                "Prompts were written from the OWASP MASVS categories, not from the ground-truth list, but they were developed and debugged on this app.");
        s.writeText("report.md", new MarkdownReport().render(in));
        s.writeText("report.html", new com.vulnscan.report.HtmlReport().render(in));
        e.progress().accept("report: " + s.path("report.html") + " (and report.md)");
    }

    static String auditSentence(RunStore s) {
        if (!s.exists("07_evaluate/audit/score.json")) {
            return "Not run yet for this run: `vulnscan audit export` writes blind rows to label, `vulnscan audit score` measures agreement.";
        }
        Map<String, Object> f = read(s.path("07_evaluate/audit/score.json"), new TypeReference<>() { });
        Map<?, ?> sc = (Map<?, ?>) f.get("score");
        Object kappa = sc.get("cohens_kappa");
        return String.format("%s labelled rows (README mode): raw agreement with the judge %.0f%%, %.0f%% when partial counts as match, "
                        + "Cohen's kappa %s; %s screened-out pair(s) labelled match (matches the quick look missed); %s disagreement(s), listed in "
                        + "07_evaluate/audit/score.json. Labels file: %s.",
                sc.get("labelled"), 100 * ((Number) sc.get("raw_agreement")).doubleValue(), 100 * ((Number) sc.get("collapsed_agreement")).doubleValue(),
                kappa == null ? "n/a" : String.format("%.2f", ((Number) kappa).doubleValue()), sc.get("screened_out_labelled_match"),
                ((List<?>) sc.get("disagreements")).size(), f.get("labels_file"));
    }

    /** Every usage.json in the run folder, in path order (scan arms, lessons, evaluation). */
    static List<UsageTracker.Line> usage(Path runDir) {
        List<UsageTracker.Line> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(runDir)) {
            for (Path p : walk.filter(x -> x.getFileName().toString().equals("usage.json")).sorted().toList()) {
                out.addAll(read(p, new TypeReference<List<UsageTracker.Line>>() { }));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out;
    }

    private static <T> T read(Path p, TypeReference<T> type) {
        try {
            return Json.json().readValue(p.toFile(), type);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read " + p, ex);
        }
    }
}
