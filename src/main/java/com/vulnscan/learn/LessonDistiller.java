package com.vulnscan.learn;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vulnscan.config.ModelsConfig.ModelSpec;
import com.vulnscan.models.LlmClient;
import com.vulnscan.models.LlmException;
import com.vulnscan.models.LlmRequest;
import com.vulnscan.models.LlmResponse;
import com.vulnscan.models.RequestFactory;
import com.vulnscan.prompts.Prompt;
import com.vulnscan.scan.Finding;
import com.vulnscan.scan.ScanResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ONE teacher call (single pass, as the brief asks) that turns the teacher's verified findings into general lessons.
 * Never sees ground truth. More than max_input_findings: deduplicated by (cwe, vuln_class) pattern keeping the
 * highest-confidence representative, then capped; the cap is reported. A max_tokens stop gets one retry with a
 * doubled budget and invalid JSON one repair call (same as scanning); a refusal or a second failure means no lessons.
 */
public class LessonDistiller {

    public static final Map<String, Object> LESSONS_SCHEMA = schema();

    private final LlmClient teacher;
    private final ModelSpec spec;
    private final Prompt prompt;
    private final int maxInputFindings;
    private final int maxLessons;
    private final ObjectMapper lenient = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    public LessonDistiller(LlmClient teacher, ModelSpec spec, Prompt prompt, int maxInputFindings, int maxLessons) {
        this.teacher = teacher;
        this.spec = spec;
        this.prompt = prompt;
        this.maxInputFindings = maxInputFindings;
        this.maxLessons = maxLessons;
    }

    public record Result(List<Lesson> lessons, int inputFindings, Integer cappedFrom, List<ScanResult.Exchange> exchanges) {
    }

    public Result distill(List<Finding> verifiedTeacherFindings, String runId) {
        List<Finding> input = verifiedTeacherFindings;
        Integer cappedFrom = null;
        if (input.size() > maxInputFindings) {
            cappedFrom = input.size();
            input = capByPattern(input, maxInputFindings);
        }
        if (input.isEmpty()) {
            throw new NoLessonsException("the teacher has no verified findings to learn from");
        }
        // Stable labels: findings ordered by content and called F1..Fn, so that identical teacher findings in another run
        // (whose ids differ, because ids include the run) give a byte-identical prompt: same cache entry, same lessons.
        List<Finding> ordered = input.stream().sorted(Comparator.comparing((Finding f) -> String.valueOf(f.location().file()))
                .thenComparing(f -> String.valueOf(f.location().symbol())).thenComparing(Finding::title)
                .thenComparing(Finding::evidence).thenComparing(f -> String.valueOf(f.cwe()))
                .thenComparing(f -> String.valueOf(f.vulnClass())).thenComparing(f -> String.valueOf(f.rationale()))).toList();
        Map<String, String> labelToId = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            labelToId.put("F" + (i + 1), ordered.get(i).id());
        }
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("max_lessons", maxLessons);
        ctx.put("count", input.size());
        ctx.put("capped", cappedFrom != null);
        ctx.put("total", cappedFrom);
        ctx.put("findings", render(ordered));
        LlmRequest request = RequestFactory.forModel(spec, prompt.system(), prompt.user(ctx), LESSONS_SCHEMA,
                new LlmRequest.Metadata("distill", "distill", runId, null, 0));

        List<ScanResult.Exchange> exchanges = new ArrayList<>();
        LlmResponse r;
        try {
            r = call(request, "distill", exchanges);
            if (r.stopReason() == LlmResponse.StopReason.MAX_TOKENS) {
                r = call(request.withMaxTokens(request.maxTokens() * 2), "distill_more_tokens", exchanges);
            }
        } catch (LlmException e) {
            throw new NoLessonsException("the distill call failed: " + e.getMessage());
        }
        if (r.stopReason() == LlmResponse.StopReason.REFUSAL) {
            throw new NoLessonsException("the teacher refused the distill call (category " + r.refusalCategory() + ")");
        }
        if (r.stopReason() == LlmResponse.StopReason.MAX_TOKENS) {
            throw new NoLessonsException("the distill answer was truncated even with a doubled output budget");
        }
        List<Lesson> lessons;
        try {
            lessons = parse(r.text());
        } catch (IllegalArgumentException first) {
            LlmRequest repair = request.withMessages(List.of(request.messages().get(0), LlmRequest.Message.assistant(r.text()),
                    LlmRequest.Message.user("Your previous answer could not be used: " + first.getMessage()
                            + ". Reply again with only the corrected JSON object {\"lessons\": [...]}, where every lesson is complete"
                            + " (no empty or placeholder lessons).")))
                    .withMetadata(new LlmRequest.Metadata("repair", "distill", runId, null, 0));
            try {
                lessons = parse(call(repair, "repair", exchanges).text());
            } catch (IllegalArgumentException | LlmException second) {
                throw new NoLessonsException("the distill answer could not be parsed after one repair call: " + second.getMessage());
            }
        }
        List<Lesson> numbered = new ArrayList<>();
        for (int i = 0; i < lessons.size(); i++) {
            Lesson l = lessons.get(i);
            List<String> sources = l.sourceFindingIds() == null ? List.of()
                    : l.sourceFindingIds().stream().map(labelToId::get).filter(java.util.Objects::nonNull).toList();
            numbered.add(new Lesson("L%02d".formatted(i + 1), l.title(), l.appliesTo(), l.signals(), l.whyExploitable(),
                    l.falsePositiveGuards(), l.cwe(), l.owaspCategory(), l.severityGuidance(), l.abstractExample(), sources));
        }
        return new Result(numbered, input.size(), cappedFrom, exchanges);
    }

    private LlmResponse call(LlmRequest request, String purpose, List<ScanResult.Exchange> exchanges) {
        LlmResponse r = teacher.complete(request);
        exchanges.add(new ScanResult.Exchange(purpose, request.maxTokens(), r.stopReason().name(), r.modelUsed(),
                r.inputTokens(), r.outputTokens(), r.cached(), r.text()));
        return r;
    }

    List<Lesson> parse(String text) {
        try {
            String s = text.strip();
            int start = s.indexOf('{');
            int end = s.lastIndexOf('}');
            JsonNode root = lenient.readTree(s.substring(start, end + 1));
            JsonNode arr = root.get("lessons");
            if (arr == null || !arr.isArray()) {
                throw new IllegalArgumentException("expected an object with a \"lessons\" array");
            }
            List<Lesson> out = new ArrayList<>();
            int empty = 0;
            for (JsonNode n : arr) {
                Lesson l = lenient.treeToValue(n, Lesson.class);
                boolean blank = l.title() == null || l.title().isBlank() || l.whyExploitable() == null || l.whyExploitable().isBlank();
                if (blank) {
                    empty++;
                }
                out.add(l);
            }
            if (out.isEmpty() || empty > 0) {
                // A degenerate answer (e.g. a "placeholder" lesson with empty fields) is treated like invalid JSON:
                // it gets the one repair call instead of silently yielding fewer lessons.
                throw new IllegalArgumentException(out.isEmpty() ? "no lessons" : empty + " lesson(s) have an empty title or why_exploitable");
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON: " + String.valueOf(e.getMessage()).split("\n")[0]);
        }
    }

    /** One representative per (cwe, vuln_class): the highest confidence; then the most confident up to the cap. */
    static List<Finding> capByPattern(List<Finding> findings, int cap) {
        Map<String, Finding> best = new LinkedHashMap<>();
        for (Finding f : findings) {
            String key = f.cwe() + "|" + f.vulnClass().toLowerCase(Locale.ROOT);
            Finding cur = best.get(key);
            if (cur == null || f.confidence() > cur.confidence()) {
                best.put(key, f);
            }
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(Finding::confidence).reversed().thenComparing(Finding::id))
                .limit(cap)
                .toList();
    }

    /** Findings in the given order, labelled F1..Fn (no run-dependent ids). */
    private static String render(List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            sb.append("- id: F").append(i + 1).append('\n')
                    .append("  title: ").append(f.title()).append('\n')
                    .append("  class: ").append(f.vulnClass()).append(" | ").append(f.cwe()).append(" | ").append(f.owaspCategory())
                    .append(" | severity ").append(f.severity()).append(" | kind ").append(f.kind()).append('\n')
                    .append("  where: ").append(f.location().component() == null ? "" : "component " + f.location().component() + ", ")
                    .append(f.location().symbol() == null ? f.location().file() : f.location().symbol()).append('\n')
                    .append("  evidence: ").append(f.evidence().strip().replace("\n", "\n            ")).append('\n')
                    .append("  rationale: ").append(f.rationale()).append("\n\n");
        }
        return sb.toString().strip();
    }

    private static Map<String, Object> schema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> strList = Map.of("type", "array", "items", str);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", str);
        props.put("applies_to", Map.of("type", "object", "properties", Map.of("component_types", strList, "apis", strList),
                "required", List.of("component_types", "apis"), "additionalProperties", false));
        props.put("signals", strList);
        props.put("why_exploitable", str);
        props.put("false_positive_guards", strList);
        props.put("cwe", str);
        props.put("owasp_category", str);
        props.put("severity_guidance", str);
        props.put("abstract_example", str);
        props.put("source_finding_ids", strList);
        Map<String, Object> lesson = Map.of("type", "object", "properties", props, "required", List.copyOf(props.keySet()),
                "additionalProperties", false);
        return Map.of("type", "object", "properties", Map.of("lessons", Map.of("type", "array", "items", lesson)),
                "required", List.of("lessons"), "additionalProperties", false);
    }
}
