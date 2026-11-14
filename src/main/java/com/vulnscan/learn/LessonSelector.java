package com.vulnscan.learn;

import com.vulnscan.common.TokenEstimator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Picks the lessons shown to the student within max_lessons and max_lesson_tokens: priority = severity of the
 * findings a lesson came from, then how many findings support it. The chosen lessons are rendered in lesson-id order,
 * so the guidance block is byte-identical in every unit (and cacheable by the provider).
 */
public class LessonSelector {

    private static final List<String> SEVERITY_ORDER = List.of("critical", "high", "medium", "low", "info");

    private final int maxLessons;
    private final int maxTokens;
    private final TokenEstimator estimator;

    public LessonSelector(int maxLessons, int maxTokens, TokenEstimator estimator) {
        this.maxLessons = maxLessons;
        this.maxTokens = maxTokens;
        this.estimator = estimator;
    }

    /** dropped: lessons left out by the caps (reported, not hidden). */
    public record Selection(List<Lesson> selected, List<Lesson> dropped, String guidance, int tokens) {
    }

    /** severity: lesson id -> severity of its strongest source finding; support: lesson id -> number of source findings. */
    public Selection select(List<Lesson> lessons, Map<String, String> severity, Map<String, Integer> support) {
        List<Lesson> byPriority = lessons.stream()
                .sorted(Comparator.comparingInt((Lesson l) -> rank(severity.get(l.id())))
                        .thenComparing(Comparator.comparingInt((Lesson l) -> support.getOrDefault(l.id(), 0)).reversed())
                        .thenComparing(Lesson::id))
                .toList();
        List<Lesson> chosen = new ArrayList<>();
        List<Lesson> dropped = new ArrayList<>();
        int used = 0;
        for (Lesson l : byPriority) {
            int t = estimator.estimate(render(l));
            if (chosen.size() < maxLessons && used + t <= maxTokens) {
                chosen.add(l);
                used += t;
            } else {
                dropped.add(l);
            }
        }
        chosen.sort(Comparator.comparing(Lesson::id));
        StringBuilder sb = new StringBuilder();
        chosen.forEach(l -> sb.append(render(l)).append('\n'));
        String guidance = sb.toString().strip();
        return new Selection(chosen, dropped, guidance, estimator.estimate(guidance));
    }

    public static int rank(String severity) {
        int i = severity == null ? -1 : SEVERITY_ORDER.indexOf(severity);
        return i < 0 ? SEVERITY_ORDER.size() : i;
    }

    /** Compact markdown for one lesson (source finding ids are never included). */
    static String render(Lesson l) {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(l.id()).append(" ").append(l.title());
        if (l.cwe() != null || l.owaspCategory() != null) {
            sb.append(" (").append(l.cwe() == null ? "" : l.cwe()).append(l.cwe() != null && l.owaspCategory() != null ? ", " : "")
                    .append(l.owaspCategory() == null ? "" : l.owaspCategory()).append(')');
        }
        sb.append('\n');
        if (l.appliesTo() != null) {
            List<String> where = new ArrayList<>();
            if (l.appliesTo().componentTypes() != null && !l.appliesTo().componentTypes().isEmpty()) {
                where.add(String.join(", ", l.appliesTo().componentTypes()));
            }
            if (l.appliesTo().apis() != null && !l.appliesTo().apis().isEmpty()) {
                where.add("APIs: " + String.join(", ", l.appliesTo().apis()));
            }
            if (!where.isEmpty()) {
                sb.append("  Applies to: ").append(String.join("; ", where)).append('\n');
            }
        }
        if (l.signals() != null && !l.signals().isEmpty()) {
            sb.append("  Look for: ").append(String.join("; ", l.signals())).append('\n');
        }
        sb.append("  Why: ").append(l.whyExploitable()).append('\n');
        if (l.falsePositiveGuards() != null && !l.falsePositiveGuards().isEmpty()) {
            sb.append("  Not a finding when: ").append(String.join("; ", l.falsePositiveGuards())).append('\n');
        }
        if (l.severityGuidance() != null && !l.severityGuidance().isBlank()) {
            sb.append("  Severity: ").append(l.severityGuidance()).append('\n');
        }
        if (l.abstractExample() != null && !l.abstractExample().isBlank()) {
            sb.append("  Example: ").append(l.abstractExample().strip().replace("\n", " ")).append('\n');
        }
        return sb.toString();
    }
}
