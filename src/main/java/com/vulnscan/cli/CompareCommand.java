package com.vulnscan.cli;

import com.vulnscan.common.Files2;
import com.vulnscan.common.Json;
import com.vulnscan.evaluate.Metrics;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** vulnscan compare RUN... [--out file]: one markdown table of headline numbers across run folders (read-only). */
@Component
@Command(name = "compare", description = "Compare the headline metrics of several run folders in one markdown table (no model calls).")
public class CompareCommand implements Callable<Integer> {

    @Parameters(arity = "1..*", description = "run folders")
    List<Path> runs;

    @Option(names = "--out", description = "also write the table to this file")
    Path out;

    @Option(names = "--html", description = "also write an HTML page with the table and recall bars")
    Path html;

    /** One row of the comparison (one reference mode of one run). */
    record Row(String run, String prompt, String student, String judge, String mode, int items, List<Integer> teacher,
               List<Integer> baseline, List<Integer> lessons) {
    }

    @Override
    public Integer call() {
        List<Row> rows = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        sb.append("| Run | Scan prompt | Student | Judge | Mode | Teacher | Student baseline | Student with lessons | Lift (mean) |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Path run : runs) {
            @SuppressWarnings("unchecked")
            Map<String, Object> manifest = Files2.readJson(run.resolve("manifest.json"), Map.class);
            Metrics m = Files2.readJson(run.resolve("07_evaluate/metrics.json"), Metrics.class);
            Map<?, ?> config = (Map<?, ?>) manifest.get("config");
            Map<?, ?> app = (Map<?, ?>) config.get("app");
            Map<?, ?> models = (Map<?, ?>) config.get("models");
            Map<?, ?> roles = (Map<?, ?>) models.get("roles");
            Map<?, ?> specs = (Map<?, ?>) models.get("models");
            String student = describe((Map<?, ?>) specs.get(roles.get("student")));
            String judge = String.valueOf(((Map<?, ?>) specs.get(roles.get("judge"))).get("model"));
            String reevaluated = latestJudge(manifest);
            if (reevaluated != null) {
                judge = reevaluated;
            }
            String prompt = String.valueOf(((Map<?, ?>) app.get("scan")).get("prompt_version"));
            for (Metrics.Mode mode : m.modes()) {
                Map<String, List<Integer>> byArm = new LinkedHashMap<>();
                mode.armRuns().forEach(r -> byArm.computeIfAbsent(r.arm(), k -> new ArrayList<>()).add((int) Math.round(r.recallStrict() * mode.items())));
                rows.add(new Row(String.valueOf(run.getFileName()), prompt, student, judge, mode.mode(), mode.items(), byArm.get("teacher"),
                        byArm.get("student_baseline"), byArm.get("student_with_lessons")));
                double base = mean(byArm.get("student_baseline"));
                double with = mean(byArm.get("student_with_lessons"));
                sb.append("| ").append(run.getFileName()).append(" | ").append(prompt).append(" | ").append(student).append(" | ")
                        .append(judge).append(" | ").append(mode.mode()).append(" | ")
                        .append(cell(byArm.get("teacher"), mode.items())).append(" | ").append(cell(byArm.get("student_baseline"), mode.items()))
                        .append(" | ").append(cell(byArm.get("student_with_lessons"), mode.items())).append(" | ")
                        .append(String.format("%+.1f items", with - base)).append(" |\n");
            }
        }
        System.out.print(sb);
        if (out != null) {
            Files2.writeAtomic(out, sb.toString());
        }
        if (html != null) {
            Files2.writeAtomic(html, html(rows));
        }
        return 0;
    }

    static String html(List<Row> rows) {
        StringBuilder h = new StringBuilder("""
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Experiment comparison</title><style>
                :root{--bg:#f7f7f5;--panel:#fff;--text:#1d1d1b;--muted:#6b6b66;--line:#e3e2dd;--t:#868e96;--b:#adb5bd;--l:#3b5bdb;--track:#f1f3f5}
                @media (prefers-color-scheme:dark){:root{--bg:#161615;--panel:#1f1f1d;--text:#ecebe6;--muted:#a3a29b;--line:#34332f;--t:#a5adb5;--b:#5c636a;--l:#7c95f5;--track:#2a2a28}}
                *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
                main{max-width:1040px;margin:0 auto;padding:24px 16px 64px}h1{font-size:24px;margin:0 0 4px}.muted{color:var(--muted)}
                .panel{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:14px 16px;margin:12px 0}
                .row{display:grid;grid-template-columns:150px 1fr 90px;gap:10px;align-items:center;margin:5px 0;font-size:13px}
                .track{height:14px;background:var(--track);border-radius:4px;overflow:hidden}.fill{height:100%}
                .teacher .fill{background:var(--t)}.baseline .fill{background:var(--b)}.lessons .fill{background:var(--l)}
                .scroll{overflow-x:auto}table{border-collapse:collapse;width:100%;font-size:13px}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line)}
                th{color:var(--muted)}code{font-family:ui-monospace,Menlo,monospace;font-size:12px}
                </style></head><body><main><h1>Experiment comparison</h1>
                <div class="muted">Strict recall on verified findings. With several runs, the bar shows the mean and the label lists every run.</div>
                """);
        for (Row r : rows) {
            h.append("<div class=\"panel\"><strong>").append(esc(r.run())).append("</strong> · scan ").append(esc(r.prompt()))
                    .append(" · ").append(esc(r.mode())).append(" reference<div class=\"muted\" style=\"font-size:12px\">student ")
                    .append(esc(r.student())).append(" · judge ").append(esc(r.judge())).append("</div>");
            bar(h, "teacher", "teacher", r.teacher(), r.items());
            bar(h, "baseline", "student baseline", r.baseline(), r.items());
            bar(h, "lessons", "student + lessons", r.lessons(), r.items());
            h.append(String.format("<div class=\"muted\" style=\"font-size:13px\">Lift %+.1f items (mean)</div></div>", mean(r.lessons()) - mean(r.baseline())));
        }
        return h.append("</main></body></html>\n").toString();
    }

    private static void bar(StringBuilder h, String cls, String label, List<Integer> v, int n) {
        if (v == null) {
            return;
        }
        int pct = (int) Math.round(100 * mean(v) / n);
        h.append("<div class=\"row ").append(cls).append("\"><div>").append(label).append("</div><div class=\"track\"><div class=\"fill\" style=\"width:")
                .append(pct).append("%\"></div></div><div>").append(esc(cell(v, n))).append("</div></div>");
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String describe(Map<?, ?> spec) {
        Object t = spec.get("temperature");
        Object base = spec.get("base_url");
        String host = base == null ? String.valueOf(spec.get("provider")) : java.net.URI.create(String.valueOf(base)).getHost();
        return spec.get("model") + " @ " + host + ", T=" + (t == null ? "default" : t);
    }

    @SuppressWarnings("unchecked")
    private static String latestJudge(Map<String, Object> manifest) {
        Object r = manifest.get("reevaluations");
        if (!(r instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        Object last = new ArrayList<>(map.values()).get(map.size() - 1);
        return String.valueOf(((Map<String, Object>) last).get("judge"));
    }

    private static String cell(List<Integer> v, int n) {
        if (v == null) {
            return "n/a";
        }
        return v.size() == 1 ? v.get(0) + "/" + n
                : v.stream().map(String::valueOf).collect(Collectors.joining(", ")) + " (mean " + String.format("%.1f", mean(v)) + ")/" + n;
    }

    private static double mean(List<Integer> v) {
        return v == null ? 0 : v.stream().mapToInt(Integer::intValue).average().orElse(0);
    }
}
