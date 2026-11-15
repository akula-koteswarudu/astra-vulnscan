package com.vulnscan.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.vulnscan.common.Json;
import com.vulnscan.config.AppConfig;
import com.vulnscan.config.ModelsConfig.ModelSpec;
import com.vulnscan.ingest.AppModel;
import com.vulnscan.planning.ScanPlanner;
import com.vulnscan.planning.ScanUnit;
import com.vulnscan.planning.UnitContextBuilder;
import com.vulnscan.planning.UnitPlan;
import com.vulnscan.scan.Deduplicator;
import com.vulnscan.scan.EvidenceVerifier;
import com.vulnscan.scan.Finding;
import com.vulnscan.scan.FindingParser;
import com.vulnscan.scan.Normalizer;
import com.vulnscan.scan.PromptBuilder;
import com.vulnscan.scan.ScanRun;
import com.vulnscan.scan.Scanner;
import com.vulnscan.scan.Splitter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Rebuilds every arm run's findings from the saved raw model answers with the current post-processing (parse,
 * normalise, split, verify evidence, merge duplicates). No model calls. Used before re-evaluation so that fixes to
 * post-processing (e.g. the evidence verifier or the merge rule) apply to existing runs. The scan-time file is kept
 * as findings.scan.json. For each unit the answer the scanner used is taken: the last saved answer that parses
 * (after a repair or a larger-output retry); re-planned units use their sub-units' answers.
 */
public class Reprocessor {

    public void reprocess(Experiment e, ScanPlanner planner) {
        AppConfig.Evidence ev = e.config().app().scan().evidence();
        PreparedApp app = e.app();
        EvidenceVerifier verifier = new EvidenceVerifier(app.code(), ev.fuzzyThreshold(), ev.minEvidenceChars());
        String promptVersion = runPromptVersion(e);
        Deduplicator dedup = new Deduplicator(e.config().matching().cweGroups());
        ScanUnit wholeApp = wholeApp(app.model());
        for (Arm arm : Arm.values()) {
            List<Integer> runs = e.runsOf(arm);
            if (runs.isEmpty()) {
                continue;
            }
            String modelName = arm.isStudent() ? e.config().models().roles().student() : e.config().models().roles().teacher();
            ModelSpec spec = e.spec(modelName);
            Scanner scanner = new Scanner(null, spec, modelName, new PromptBuilder(e.prompts().get("scan", promptVersion), app.model(),
                    app.code()), new FindingParser(), new Normalizer(), new Splitter(), verifier, u -> List.of());
            Map<String, ScanUnit> units = new HashMap<>();
            String planFile = "03_plan/units_" + (arm.isStudent() ? "student" : "teacher") + ".json";
            if (e.store().exists(planFile)) {
                for (ScanUnit u : e.store().readJson(planFile, UnitPlan.class).units()) {
                    units.put(u.unitId(), u);
                }
            }
            for (int run : runs) {
                String dir = Experiment.armDir(arm, run);
                ArmOutput saved = e.store().readJson(dir + "findings.json", ArmOutput.class);
                if (!e.store().exists(dir + "findings.scan.json")) {
                    e.store().writeJson(dir + "findings.scan.json", saved);
                }
                ScanRun scanRun = new ScanRun(arm.id, e.store().runId(), run - 1);
                List<Finding> all = new ArrayList<>();
                for (Map<String, Object> raw : rawRecords(e.store().path(dir + "raw"))) {
                    collect(raw, null, units, wholeApp, scanner, scanRun, e, planner, modelName, spec, arm, all);
                }
                List<Finding> merged = dedup.dedup(all);
                e.store().writeJson(dir + "findings.json", new ArmOutput(saved.schemaVersion(), saved.arm(), saved.run(), saved.model(), merged));
                long verified = merged.stream().filter(Finding::isVerified).count();
                e.progress().accept("reprocess " + arm.id + " run " + run + ": " + merged.size() + " findings (" + verified
                        + " verified); at scan time " + saved.findings().size() + " (" + saved.findings().stream().filter(Finding::isVerified).count() + ")");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collect(Map<String, Object> raw, ScanUnit parent, Map<String, ScanUnit> units, ScanUnit wholeApp, Scanner scanner,
                         ScanRun run, Experiment e, ScanPlanner planner, String modelName, ModelSpec spec, Arm arm, List<Finding> out) {
        String unitId = String.valueOf(raw.get("unit_id"));
        ScanUnit unit = units.get(unitId);
        List<Map<String, Object>> subs = (List<Map<String, Object>>) raw.getOrDefault("sub_units", List.of());
        if (!subs.isEmpty()) {
            // A re-planned unit: its sub-units are regenerated (splitting is deterministic) and matched by id.
            if (unit != null) {
                for (ScanUnit s : planner.split(unit, modelName, spec, arm.isStudent(), e.app().model(), e.app().code())) {
                    units.put(s.unitId(), s);
                }
            }
            for (Map<String, Object> sub : subs) {
                collect(sub, unit, units, wholeApp, scanner, run, e, planner, modelName, spec, arm, out);
            }
            return;
        }
        List<Map<String, Object>> exchanges = (List<Map<String, Object>>) raw.getOrDefault("exchanges", List.of());
        for (int i = exchanges.size() - 1; i >= 0; i--) {
            Map<String, Object> x = exchanges.get(i);
            if (!"END".equals(x.get("stop_reason"))) {
                continue;
            }
            try {
                out.addAll(scanner.fromSavedAnswer(String.valueOf(x.get("text")), unit != null ? unit : wholeApp, run,
                        x.get("model_used") == null ? null : String.valueOf(x.get("model_used"))));
                return;
            } catch (FindingParser.InvalidResponseException ignored) {
                // an earlier answer (before a repair) did not parse; try the previous exchange
            }
        }
    }

    private static List<Map<String, Object>> rawRecords(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                out.add(Json.json().readValue(p.toFile(), new TypeReference<Map<String, Object>>() { }));
            }
            return out;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** The scan prompt version this run was made with (from its manifest). */
    private static String runPromptVersion(Experiment e) {
        Object config = e.manifest().snapshot().get("config");
        if (config instanceof Map<?, ?> c && c.get("app") instanceof Map<?, ?> app && app.get("scan") instanceof Map<?, ?> scan) {
            return String.valueOf(scan.get("prompt_version"));
        }
        return e.config().app().scan().promptVersion();
    }

    private static ScanUnit wholeApp(AppModel app) {
        UnitContextBuilder ctx = new UnitContextBuilder();
        List<String> files = app.firstPartySources().stream().map(AppModel.SourceFile::path).sorted().toList();
        return new ScanUnit("reprocess-whole-app", "whole", "app", files, ctx.fullManifestSlice(app), ctx.resources(app),
                ctx.dependencies(app), 0, 0);
    }
}
