package com.vulnscan.report;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import com.vulnscan.evaluate.Metrics;
import com.vulnscan.learn.LessonFile;
import com.vulnscan.learn.Violation;
import com.vulnscan.models.UsageTracker;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders report.md from run data (plan 6.10 order). The layout is a Mustache template
 * (src/main/resources/report/report.md.mustache); this class only turns run data into strings for it.
 */
public class MarkdownReport {

    /** Everything the report needs, already read from the run folder. */
    public record Inputs(String app, String runId, String profile, Metrics metrics, LessonFile lessons,
                         List<Violation> violations, String sanitiserPolicy, int droppedBySanitiser, int droppedByCaps,
                         List<UsageTracker.Line> usage, Map<String, Coverage> coverage, Decompile decompile,
                         Map<String, String> prompts, String teacherMode, int teacherUnits, String studentMode,
                         int studentUnits, String auditSentence, List<String> limitations, String promptsSentence) {
    }

    public record Coverage(int units, Map<String, Long> unitStatus, Map<String, Long> evidenceStatus, int badFindings) {
    }

    public record Decompile(String jadxVersion, int exitCode, boolean partial, int failedAll, int failedFirstParty, int firstPartyFiles,
                            int allFiles, List<String> prefixes, int generatedExcluded, String topExcluded) {
    }

    private static final List<String> ARM_ORDER = List.of("teacher", "student_baseline", "student_with_lessons");
    private static final Map<String, String> ARM_LABEL = Map.of("teacher", "teacher (ceiling)", "student_baseline", "student baseline",
            "student_with_lessons", "student with lessons");

    private final Template template;

    public MarkdownReport() {
        try (InputStream in = MarkdownReport.class.getResourceAsStream("/report/report.md.mustache")) {
            template = Mustache.compiler().escapeHTML(false).defaultValue("")
                    .compile(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String render(Inputs in) {
        return template.execute(context(in)).replaceAll("\n{3,}", "\n\n");
    }

    /** Everything the templates need, as plain values (shared by the Markdown and the HTML report). */
    public static Map<String, Object> context(Inputs in) {
        Metrics m = in.metrics();
        Metrics.Mode head = m.modes().get(0);
        boolean gt = m.groundTruth();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("app", in.app());
        c.put("run_id", in.runId());
        c.put("profile", in.profile());
        c.put("teacher_reference", !gt);
        c.put("ground_truth", gt);
        c.put("metric_label", gt ? "Recall" : "Agreement with teacher");
        c.put("reference_name", gt ? (head.mode().equals("readme") ? "README ground truth, " + head.items() + " items"
                : "enriched ground truth, " + head.items() + " items") : "teacher findings, " + head.items() + " items");

        List<Map<String, Object>> core = new ArrayList<>();
        for (Metrics.ArmRun r : ordered(head.armRuns())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("arm", ARM_LABEL.getOrDefault(r.arm(), r.arm()) + (r.run() > 1 ? " (run " + r.run() + ")" : ""));
            row.put("model", r.model());
            row.put("recall_strict", frac(r.recallStrict(), head.items()));
            row.put("recall_lenient", frac(r.recallLenient(), head.items()));
            row.put("recall_unverified", frac(r.recallStrictInclUnverified(), head.items()));
            row.put("precision", r.precisionStrict() == null ? "n/a" : pct(r.precisionStrict()));
            row.put("findings", r.findingsVerified() + " / " + r.findingsTotal());
            core.add(row);
        }
        c.put("core_rows", core);
        c.put("headline_sentence", gt
                ? "Headline: the student's recall against the " + (head.mode().equals("readme") ? "README list" : "enriched list")
                + " before and after the lessons, judged strictly, counting verified findings only. The teacher row is the ceiling."
                : "Headline: how many of the teacher's verified findings the student also reports, before and after the lessons.");
        c.put("lift_sentence", liftSentence(m, head));

        List<String> armHeaders = new ArrayList<>();
        ARM_ORDER.stream().filter(a -> head.armRuns().stream().anyMatch(r -> r.arm().equals(a))).forEach(a -> armHeaders.add(ARM_LABEL.get(a)));
        c.put("arm_headers", armHeaders);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Metrics.ItemRow row : head.perItem()) {
            List<String> cells = new ArrayList<>();
            for (String arm : ARM_ORDER) {
                if (row.hitsStrict().containsKey(arm)) {
                    cells.add(cell(row.hitsStrict().get(arm), row.hitsLenient().get(arm),
                            row.uncredited() == null ? null : row.uncredited().get(arm)));
                }
            }
            items.add(Map.of("id", row.itemId(), "title", row.title().replace("|", "/"),
                    "visibility", row.staticVisibility() == null ? "" : row.staticVisibility(), "cells", cells));
        }
        c.put("item_rows", items);

        LessonFile lf = in.lessons();
        c.put("lessons_sentence", lf.lessons().size() + " lessons kept after sanitising"
                + (lf.source().equals("distilled") ? ", distilled in one teacher call from " + lf.inputFindings() + " verified teacher findings"
                + (lf.inputCapFrom() == null ? "" : " (capped from " + lf.inputCapFrom() + ")") : " (" + lf.source() + ")")
                + "; " + lf.selectedIds().size() + " selected for the student (" + lf.guidanceTokens() + " tokens, within the reserved budget).");
        c.put("guidance", lf.guidance());
        c.put("sanitiser_policy", in.sanitiserPolicy());
        long redacted = in.violations().stream().filter(v -> "redacted".equals(v.action())).count();
        long examples = in.violations().stream().filter(v -> "example_removed".equals(v.action())).count();
        c.put("sanitiser_sentence", redacted + " term(s) redacted, " + examples + " example(s) removed by the answer-key check, "
                + in.droppedBySanitiser() + " lesson(s) dropped; " + in.droppedByCaps() + " lesson(s) left out by the count/token caps."
                + " Full records: 05_learn/rejected_lessons.json.");
        List<Map<String, Object>> viol = new ArrayList<>();
        for (Violation v : in.violations()) {
            viol.add(Map.of("lesson", v.lessonId(), "field", v.field(), "rule", v.rule(), "term", v.term().replace("|", "/").replace("`", "'"),
                    "action", v.action()));
        }
        c.put("violations", viol);
        c.put("has_violations", !viol.isEmpty());

        boolean robust = m.modes().size() > 1;
        c.put("has_robustness", robust);
        if (robust) {
            Metrics.Mode other = m.modes().get(1);
            c.put("robust_a", head.mode());
            c.put("robust_b", other.mode());
            List<Map<String, Object>> rr = new ArrayList<>();
            for (Metrics.ArmRun r : ordered(head.armRuns())) {
                Metrics.ArmRun o = other.armRuns().stream().filter(x -> x.arm().equals(r.arm()) && x.run() == r.run()).findFirst().orElse(null);
                rr.add(Map.of("arm", ARM_LABEL.getOrDefault(r.arm(), r.arm()), "a", frac(r.recallStrict(), head.items()),
                        "b", o == null ? "n/a" : frac(o.recallStrict(), other.items())));
            }
            c.put("robust_rows", rr);
            Metrics.Lift ol = com.vulnscan.evaluate.Evaluator.lift(other);
            c.put("robust_sentence", "Lift with the " + other.mode() + " reference: "
                    + (ol.lift() == null ? "n/a" : String.format("%+.1f points", 100 * ol.lift()))
                    + " (headline " + (m.lift().lift() == null ? "n/a" : String.format("%+.1f", 100 * m.lift().lift())) + ")."
                    + " A difference means the judge's verdicts depend on the extra detail (CWEs, places) rather than on the README wording.");
        }
        c.put("audit_sentence", in.auditSentence());

        List<Map<String, Object>> cov = new ArrayList<>();
        for (String arm : ARM_ORDER) {
            Coverage cv = in.coverage().get(arm);
            if (cv != null) {
                cov.add(Map.of("arm", ARM_LABEL.get(arm), "units", cv.units(), "status", counts(cv.unitStatus()),
                        "evidence", counts(cv.evidenceStatus()), "bad", cv.badFindings()));
            }
        }
        c.put("coverage_rows", cov);
        Decompile d = in.decompile();
        c.put("jadx_version", d.jadxVersion());
        c.put("jadx_exit", d.exitCode());
        c.put("jadx_partial", d.partial());
        c.put("jadx_failed", d.failedAll());
        c.put("fp_failed", d.failedFirstParty());
        c.put("fp_files", d.firstPartyFiles());
        c.put("all_files", d.allFiles());
        c.put("fp_prefixes", String.join(", ", d.prefixes()));
        c.put("generated", d.generatedExcluded());
        c.put("top_excluded", d.topExcluded());
        c.put("teacher_units", in.teacherUnits());
        c.put("teacher_mode", in.teacherMode());
        c.put("student_units", in.studentUnits());
        c.put("student_mode", in.studentMode());
        c.put("judge_calls", m.modes().stream().flatMap(x -> x.armRuns().stream()).mapToInt(Metrics.ArmRun::judgeCalls).sum());
        c.put("judge_errors", m.modes().stream().flatMap(x -> x.armRuns().stream()).mapToInt(Metrics.ArmRun::judgeErrors).sum());

        List<Map<String, Object>> usage = new ArrayList<>();
        double billed = 0;
        double original = 0;
        for (UsageTracker.Line l : in.usage()) {
            usage.add(Map.of("model", l.model, "purpose", l.purpose, "arm", l.arm, "calls", l.billedCalls + " / " + l.cachedCalls,
                    "input", l.inputTokens + l.cachedInputTokens, "output", l.outputTokens + l.cachedOutputTokens,
                    "cost", String.format("$%.4f", l.cost)));
            billed += l.cost;
            original += l.cost + l.cachedCost;
        }
        c.put("usage_rows", usage);
        c.put("usage_sentence", String.format("Billed in this run: $%.4f. Including calls served from the response cache at their "
                + "original price: $%.4f. Free-tier models are shown at $0 (tokens are still counted).", billed, original));

        c.put("prompt_rows", in.prompts().entrySet().stream()
                .map(e -> Map.of("name", e.getKey(), "sha", e.getValue().substring(0, 12))).toList());
        c.put("prompts_sentence", in.promptsSentence());
        c.put("limitations", in.limitations());
        htmlExtras(c, in, head);
        return c;
    }

    /** Structured values only the HTML report uses: recall bars, coloured grid cells, lesson cards. */
    private static void htmlExtras(Map<String, Object> c, Inputs in, Metrics.Mode head) {
        List<Map<String, Object>> bars = new ArrayList<>();
        for (Metrics.ArmRun r : ordered(head.armRuns())) {
            bars.add(Map.of("label", ARM_LABEL.getOrDefault(r.arm(), r.arm()) + (r.run() > 1 || head.armRuns().stream()
                            .filter(x -> x.arm().equals(r.arm())).count() > 1 ? " · run " + r.run() : ""),
                    "strict", (int) Math.round(r.recallStrict() * 100), "lenient", (int) Math.round(r.recallLenient() * 100),
                    "value", frac(r.recallStrict(), head.items()), "teacher", r.arm().equals("teacher")));
        }
        c.put("bars", bars);
        List<Map<String, Object>> grid = new ArrayList<>();
        for (Metrics.ItemRow row : head.perItem()) {
            List<Map<String, Object>> cells = new ArrayList<>();
            for (String arm : ARM_ORDER) {
                if (row.hitsStrict().containsKey(arm)) {
                    String text = cell(row.hitsStrict().get(arm), row.hitsLenient().get(arm), row.uncredited() == null ? null : row.uncredited().get(arm));
                    String cls = text.startsWith("✓") ? "hit" : text.startsWith("◐") ? "uncredited" : text.startsWith("~") ? "partial" : "miss";
                    cells.add(Map.of("text", text, "cls", cls));
                }
            }
            grid.add(Map.of("id", row.itemId(), "title", row.title(), "visibility", row.staticVisibility() == null ? "" : row.staticVisibility(),
                    "cells", cells));
        }
        c.put("grid", grid);
        List<Map<String, Object>> cards = new ArrayList<>();
        for (com.vulnscan.learn.Lesson l : in.lessons().lessons()) {
            if (!in.lessons().selectedIds().contains(l.id())) {
                continue;
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("id", l.id());
            card.put("title", l.title());
            card.put("tags", ((l.cwe() == null ? "" : l.cwe()) + " " + (l.owaspCategory() == null ? "" : l.owaspCategory())).strip());
            card.put("signals", l.signals() == null ? List.of() : l.signals());
            card.put("why", l.whyExploitable());
            card.put("guards", l.falsePositiveGuards() == null ? List.of() : l.falsePositiveGuards());
            card.put("example", l.abstractExample() == null || l.abstractExample().isBlank() ? null : l.abstractExample());
            cards.add(card);
        }
        c.put("lesson_cards", cards);
    }

    private static String liftSentence(Metrics m, Metrics.Mode head) {
        Metrics.Lift lift = m.lift();
        Metrics.Stat base = lift.recallByArm().get("student_baseline");
        Metrics.Stat with = lift.recallByArm().get("student_with_lessons");
        if (base == null || with == null || lift.lift() == null) {
            return "No lift computed (an arm is missing).";
        }
        String what = m.groundTruth() ? "recall" : "agreement with teacher";
        String s = String.format("Lift: %+.1f points of %s (%s → %s, strict, verified).", 100 * lift.lift(), what,
                frac(base.mean(), head.items()), frac(with.mean(), head.items()));
        if (base.runs() > 1 || with.runs() > 1) {
            s += String.format(" Mean of %d runs per arm; ranges %s–%s and %s–%s.", base.runs(), pct(base.min()), pct(base.max()),
                    pct(with.min()), pct(with.max()));
        } else {
            s += " One run per arm: LLM output varies between runs, so a lift of one or two items is within run-to-run noise; small N, not a significance test.";
        }
        return s;
    }

    private static List<Metrics.ArmRun> ordered(List<Metrics.ArmRun> runs) {
        return runs.stream().sorted((a, b) -> ARM_ORDER.indexOf(a.arm()) != ARM_ORDER.indexOf(b.arm())
                ? ARM_ORDER.indexOf(a.arm()) - ARM_ORDER.indexOf(b.arm()) : a.run() - b.run()).toList();
    }

    private static String cell(String strict, String lenient, String uncredited) {
        if (strict == null) {
            return "";
        }
        String[] s = strict.split("/");
        String[] l = lenient.split("/");
        boolean single = s[1].equals("1");
        if (!s[0].equals("0")) {
            return single ? "✓" : "✓ " + strict;
        }
        if (uncredited != null && !uncredited.startsWith("0/")) {
            return single ? "◐" : "◐ " + uncredited;
        }
        if (!l[0].equals("0")) {
            return single ? "~" : "~ " + lenient;
        }
        return "·";
    }

    private static String frac(double ratio, int n) {
        return Math.round(ratio * n) + "/" + n + " (" + pct(ratio) + ")";
    }

    private static String pct(double r) {
        return Math.round(r * 100) + "%";
    }

    private static String counts(Map<String, Long> m) {
        return m.isEmpty() ? "none" : m.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "));
    }
}
