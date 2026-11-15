package com.vulnscan.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.vulnscan.common.Files2;
import com.vulnscan.common.Json;
import com.vulnscan.config.ConfigLoader;
import com.vulnscan.config.LoadedConfig;
import com.vulnscan.evaluate.GroundTruthLoader;
import com.vulnscan.evaluate.GroundTruthReference;
import com.vulnscan.evaluate.JudgeAudit;
import com.vulnscan.evaluate.Metrics;
import com.vulnscan.matching.ReferenceItem;
import com.vulnscan.scan.Finding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * vulnscan audit export --config X --run DIR   -> 07_evaluate/audit/audit.csv (blind, to label) + audit_key.json
 * vulnscan audit score  --run DIR [--labels F] -> 07_evaluate/audit/score.json (agreement with the judge)
 */
@Component
@Command(name = "audit", description = "Hand audit of the judge: export blind rows to label, then score agreement.")
public class AuditCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "export | score")
    String action;

    @Option(names = "--config", description = "per-app config (export: locates the ground truth)")
    Path config;

    @Option(names = "--run", required = true, description = "run folder")
    Path run;

    @Option(names = "--labels", description = "labelled CSV (default: the exported audit.csv)")
    Path labels;

    @Option(names = "--mode", defaultValue = "readme", description = "reference mode to audit (default readme, the headline)")
    String mode;

    private final ConfigLoader loader;

    public AuditCommand(ConfigLoader loader) {
        this.loader = loader;
    }

    @Override
    public Integer call() throws IOException {
        Path dir = run.resolve("07_evaluate/audit");
        return switch (action) {
            case "export" -> export(dir);
            case "score" -> score(dir);
            default -> {
                System.err.println("error: action must be export or score");
                yield 2;
            }
        };
    }

    private int export(Path dir) throws IOException {
        if (config == null) {
            System.err.println("error: export needs --config (to find the ground truth)");
            return 2;
        }
        LoadedConfig cfg = loader.load(config);
        var gt = new GroundTruthLoader().load(Path.of(cfg.app().app().groundTruth()).toAbsolutePath());
        List<ReferenceItem> items = new GroundTruthReference(gt, mode.equals("enriched")).items();
        Map<String, Object> decisions = Json.json().readValue(run.resolve("07_evaluate/judge_decisions.json").toFile(), new TypeReference<>() { });
        Metrics metrics = Files2.readJson(run.resolve("07_evaluate/metrics.json"), Metrics.class);
        Metrics.Mode m = metrics.modes().stream().filter(x -> x.mode().equals(mode)).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> byArmRun = (Map<String, Map<String, Object>>) decisions.get(mode);
        List<JudgeAudit.ArmInput> arms = new ArrayList<>();
        for (Metrics.ArmRun ar : m.armRuns()) {
            String key = ar.arm() + "/run_" + ar.run();
            Map<String, Object> d = byArmRun.get(key);
            @SuppressWarnings("unchecked")
            Map<String, List<String>> screened = (Map<String, List<String>>) d.get("screened");
            Map<String, String> verdicts = new HashMap<>();
            for (Object v : (List<?>) d.get("verdicts")) {
                Map<?, ?> vm = (Map<?, ?>) v;
                verdicts.put(vm.get("item_id") + "|" + vm.get("finding_id"), String.valueOf(vm.get("verdict")));
            }
            List<Finding> findings = Json.json().convertValue(Json.json().readTree(
                    run.resolve("04_scan/" + ar.arm() + "/run_" + ar.run() + "/findings.json").toFile()).get("findings"),
                    new TypeReference<List<Finding>>() { });
            arms.add(new JudgeAudit.ArmInput(key, findings, screened, verdicts, ar.matchedStrict()));
        }
        JudgeAudit.Export ex = JudgeAudit.export(items, arms, 42);
        Files.createDirectories(dir);
        StringBuilder csv = new StringBuilder("row_id,item_id,item_title,item_description,finding_title,finding_cwe,finding_location,"
                + "finding_evidence,finding_rationale,label\n");
        for (JudgeAudit.Row r : ex.rows()) {
            csv.append(String.join(",", q(r.rowId()), q(r.itemId()), q(r.itemTitle()), q(r.itemDescription()), q(r.findingTitle()),
                    q(r.findingCwe()), q(r.findingLocation()), q(r.findingEvidence()), q(r.findingRationale()), "")).append('\n');
        }
        Files2.writeAtomic(dir.resolve("audit.csv"), csv.toString());
        Files2.writeJson(dir.resolve("audit_key.json"), ex.key());
        Files2.writeJson(dir.resolve("audit_rows.json"), ex.rows());
        long screenedOut = ex.key().stream().filter(k -> k.source().equals("screened_out")).count();
        System.out.println("wrote " + ex.rows().size() + " rows to " + dir.resolve("audit.csv") + " (" + (ex.rows().size() - screenedOut)
                + " judge decisions, " + screenedOut + " screened-out pairs of unmatched items). Fill the label column: match | partial | no.");
        return 0;
    }

    private int score(Path dir) throws IOException {
        Path file = labels != null ? labels : dir.resolve("audit.csv");
        Map<String, String> labelled = new LinkedHashMap<>();
        for (List<String> rec : Csv.read(Files.readString(file))) {
            if (rec.size() >= 10 && !rec.get(0).equals("row_id")) {
                labelled.put(rec.get(0), rec.get(9));
            }
        }
        List<JudgeAudit.KeyEntry> key = Json.json().readValue(dir.resolve("audit_key.json").toFile(), new TypeReference<>() { });
        List<JudgeAudit.Row> rows = Json.json().readValue(dir.resolve("audit_rows.json").toFile(), new TypeReference<>() { });
        Map<String, JudgeAudit.Row> byId = new HashMap<>();
        rows.forEach(r -> byId.put(r.rowId(), r));
        JudgeAudit.Score s = JudgeAudit.score(labelled, key, byId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("labels_file", file.toString());
        out.put("score", s);
        Files2.writeJson(dir.resolve("score.json"), out);
        System.out.printf("labelled %d (unlabelled %d): raw agreement %.0f%%, with partial = match %.0f%%, Cohen's kappa %s; "
                        + "screened-out pairs labelled match: %d; disagreements: %d%n", s.labelled(), s.unlabelled(), 100 * s.rawAgreement(),
                100 * s.collapsedAgreement(), s.cohensKappa() == null ? "n/a" : String.format("%.2f", s.cohensKappa()),
                s.screenedOutLabelledMatch(), s.disagreements().size());
        return 0;
    }

    private static String q(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }

    /** Minimal RFC 4180 CSV reader (quoted fields, doubled quotes, newlines inside quotes). */
    static final class Csv {
        static List<List<String>> read(String text) {
            List<List<String>> out = new ArrayList<>();
            List<String> rec = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else if (c == '"') {
                        quoted = false;
                    } else {
                        field.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    rec.add(field.toString());
                    field.setLength(0);
                } else if (c == '\n') {
                    rec.add(field.toString());
                    field.setLength(0);
                    out.add(rec);
                    rec = new ArrayList<>();
                } else if (c != '\r') {
                    field.append(c);
                }
            }
            if (field.length() > 0 || !rec.isEmpty()) {
                rec.add(field.toString());
                out.add(rec);
            }
            return out;
        }
    }
}
