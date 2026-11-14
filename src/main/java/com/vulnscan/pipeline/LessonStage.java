package com.vulnscan.pipeline;

import com.vulnscan.common.Files2;
import com.vulnscan.common.TokenEstimator;
import com.vulnscan.config.AppConfig;
import com.vulnscan.learn.CommonWords;
import com.vulnscan.learn.Lesson;
import com.vulnscan.learn.LessonDistiller;
import com.vulnscan.learn.LessonFile;
import com.vulnscan.learn.LessonSanitizer;
import com.vulnscan.learn.LessonSelector;
import com.vulnscan.learn.NoLessonsException;
import com.vulnscan.models.UsageTracker;
import com.vulnscan.scan.Deduplicator;
import com.vulnscan.scan.Finding;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage "distill": verified teacher findings (union across teacher runs, deduplicated) -> one distill call ->
 * sanitiser -> selector -> 05_learn/lessons.json and rejected_lessons.json. With distill.lessons_from set, lessons are
 * loaded from that file instead (cross-app transfer) and no call is made.
 */
public class LessonStage {

    static final String LESSONS = "05_learn/lessons.json";

    public void distill(Experiment e) {
        AppConfig cfg = e.config().app();
        AppConfig.Distill d = cfg.distill();
        TokenEstimator estimator = new TokenEstimator(cfg.planning().tokenEstimateCharsPerToken(), cfg.planning().tokenEstimateMargin());
        LessonSelector selector = new LessonSelector(d.maxLessons(), d.maxLessonTokens(), estimator);

        if (d.lessonsFrom() != null) {
            Path from = Path.of(d.lessonsFrom()).toAbsolutePath().normalize(); // repo-relative, like every configured path
            LessonFile source = Files2.readJson(from, LessonFile.class);
            Map<String, Integer> support = new HashMap<>();
            source.lessons().forEach(l -> support.put(l.id(), l.sourceFindingIds() == null ? 0 : l.sourceFindingIds().size()));
            LessonSelector.Selection sel = selector.select(source.lessons(), Map.of(), support);
            requireLessons(sel);
            e.store().writeJson(LESSONS, new LessonFile(1, "lessons_from:" + d.lessonsFrom(), source.promptVersion(), 0, null,
                    source.lessons(), ids(sel.selected()), sel.guidance(), sel.tokens()));
            e.progress().accept("lessons: " + sel.selected().size() + " loaded from " + d.lessonsFrom() + " (no distill call)");
            return;
        }

        List<Finding> teacher = new ArrayList<>();
        for (int k = 1; k <= cfg.runs().teacherRuns(); k++) {
            ArmOutput out = e.store().readJson(Experiment.armDir(Arm.TEACHER, k) + "findings.json", ArmOutput.class);
            out.findings().stream().filter(Finding::isVerified).forEach(teacher::add);
        }
        List<Finding> union = new Deduplicator(e.config().matching().cweGroups()).dedup(teacher);

        UsageTracker usage = new UsageTracker();
        String teacherName = e.config().models().roles().teacher();
        LessonDistiller distiller = new LessonDistiller(e.models().forModel(teacherName, usage), e.spec(teacherName),
                e.prompts().get("distill", d.promptVersion()), d.maxInputFindings(), d.maxLessons());
        LessonDistiller.Result distilled;
        try {
            distilled = distiller.distill(union, e.store().runId());
        } finally {
            e.store().writeJson("05_learn/usage.json", usage.snapshot());
        }
        e.store().writeJson("05_learn/raw_distill.json", distilled.exchanges());

        PreparedApp app = e.app();
        LessonSanitizer sanitizer = new LessonSanitizer(app.model(), app.code(), CommonWords.load(),
                cfg.ingest().stringsFilter().namePatterns(), d.sanitizer().onViolation(), d.sanitizer().exampleSimilarityMax());
        Map<String, Finding> byId = new HashMap<>();
        union.forEach(f -> byId.put(f.id(), f));
        LessonSanitizer.Result clean = sanitizer.sanitize(distilled.lessons(), byId);

        Map<String, String> severity = new HashMap<>();
        Map<String, Integer> support = new HashMap<>();
        for (Lesson l : clean.lessons()) {
            List<Finding> src = l.sourceFindingIds() == null ? List.of()
                    : l.sourceFindingIds().stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
            support.put(l.id(), src.size());
            src.stream().map(Finding::severity).min(java.util.Comparator.comparingInt(LessonSelector::rank))
                    .ifPresent(s -> severity.put(l.id(), s));
        }
        LessonSelector.Selection sel = selector.select(clean.lessons(), severity, support);

        Map<String, Object> rejected = new LinkedHashMap<>();
        rejected.put("schema_version", 1);
        rejected.put("policy", d.sanitizer().onViolation());
        rejected.put("deny_list_sizes", sanitizer.denyListSizes());
        rejected.put("violations", clean.violations());
        rejected.put("dropped_by_sanitiser", clean.dropped());
        rejected.put("dropped_by_caps", sel.dropped());
        rejected.put("original_lessons", distilled.lessons());
        e.store().writeJson("05_learn/rejected_lessons.json", rejected);
        requireLessons(sel);
        e.store().writeJson(LESSONS, new LessonFile(1, "distilled", d.promptVersion(), distilled.inputFindings(), distilled.cappedFrom(),
                clean.lessons(), ids(sel.selected()), sel.guidance(), sel.tokens()));
        e.progress().accept("lessons: " + distilled.lessons().size() + " distilled from " + distilled.inputFindings()
                + " verified teacher findings; " + clean.dropped().size() + " dropped and "
                + clean.violations().stream().filter(v -> "redacted".equals(v.action())).count() + " term(s) redacted by the sanitiser; "
                + sel.selected().size() + " selected (" + sel.tokens() + " tokens)");
    }

    public String guidance(Experiment e) {
        return e.store().readJson(LESSONS, LessonFile.class).guidance();
    }

    private static void requireLessons(LessonSelector.Selection sel) {
        if (sel.selected().isEmpty()) {
            throw new NoLessonsException("every lesson was dropped (see 05_learn/rejected_lessons.json); the with-lessons arm is not run with an empty guidance block");
        }
    }

    private static List<String> ids(List<Lesson> lessons) {
        return lessons.stream().map(Lesson::id).toList();
    }

}
