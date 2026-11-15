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

    @Override
    public Integer call() {
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
        return 0;
    }

    private static String describe(Map<?, ?> spec) {
        Object t = spec.get("temperature");
        return spec.get("model") + " (" + spec.get("provider") + ", T=" + (t == null ? "default" : t) + ")";
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
