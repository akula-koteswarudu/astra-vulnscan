package com.vulnscan.pipeline;

import com.vulnscan.UserFacingException;
import com.vulnscan.config.AppConfig;
import com.vulnscan.config.LoadedConfig;
import com.vulnscan.config.ModelsConfig.ModelSpec;
import com.vulnscan.ingest.AppModel;
import com.vulnscan.models.ModelClientFactory;
import com.vulnscan.models.UsageTracker;
import com.vulnscan.planning.ScanPlanner;
import com.vulnscan.planning.UnitPlan;
import com.vulnscan.prompts.PromptLibrary;
import com.vulnscan.scan.ArmScanner;
import com.vulnscan.scan.EvidenceVerifier;
import com.vulnscan.scan.FindingParser;
import com.vulnscan.scan.Normalizer;
import com.vulnscan.scan.PromptBuilder;
import com.vulnscan.scan.ScanResult;
import com.vulnscan.scan.ScanRun;
import com.vulnscan.scan.Scanner;
import com.vulnscan.scan.Splitter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the stages in order (plan 6.9): decompile -> ingest -> plan -> scan:teacher -> scan:student_baseline ->
 * distill -> scan:student_with_lessons -> evaluate -> report. Teacher first, then student, as Astra will run it.
 * Each stage writes its artefacts and a _DONE marker; a resumed run skips completed stages.
 */
public class Experiment {

    private static final Logger log = LoggerFactory.getLogger(Experiment.class);

    private final LoadedConfig cfg;
    private final RunStore store;
    private final RunManifest manifest;
    private final AppPreparer preparer;
    private final Path input;
    private final PromptLibrary prompts;
    private final ScanPlanner planner;
    private final ModelClientFactory models;
    private final ArmScanner armScanner;
    private final LaterStages later;
    private final Consumer<String> progress;

    private PreparedApp app;

    /** Stages built after the scan stages (lessons, evaluation, report); see {@link LaterStages}. */
    public Experiment(LoadedConfig cfg, RunStore store, RunManifest manifest, AppPreparer preparer, Path input,
                      PromptLibrary prompts, ScanPlanner planner, ModelClientFactory models, ArmScanner armScanner,
                      LaterStages later, Consumer<String> progress) {
        this.cfg = cfg;
        this.store = store;
        this.manifest = manifest;
        this.preparer = preparer;
        this.input = input;
        this.prompts = prompts;
        this.planner = planner;
        this.models = models;
        this.armScanner = armScanner;
        this.later = later;
        this.progress = progress;
    }

    public RunStore store() {
        return store;
    }

    /** All stages. */
    public void run() {
        prepare();
        plan();
        scanArm(Arm.TEACHER);
        scanArm(Arm.STUDENT_BASELINE);
        stage("distill", () -> later.distill(this));
        scanArm(Arm.STUDENT_WITH_LESSONS);
        stage("evaluate", () -> later.evaluate(this));
        stage("report", () -> later.report(this));
        progress.accept("done: " + store.dir().resolve("report.md"));
    }

    /**
     * Re-runs evaluation and the report from saved findings (no rescans), e.g. after a matcher or judge-prompt change.
     * Overwrites 07_evaluate/ and report.md.
     */
    public void reevaluate() {
        app = savedApp();
        new Reprocessor().reprocess(this, planner);
        manifest.putIn("reevaluations", java.time.Instant.now().toString(), Map.of("config_hash", cfg.configHash(),
                "judge", cfg.models().spec(cfg.models().roles().judge()).model()));
        for (String stage : List.of("evaluate", "report")) {
            long start = System.currentTimeMillis();
            store.log("stage_start", Map.of("stage", stage, "rerun", true));
            if (stage.equals("evaluate")) {
                later.evaluate(this);
            } else {
                later.report(this);
            }
            finish(stage, start);
        }
    }

    /**
     * The app as this run saw it: the saved app model and the decompiled folder recorded in 01_decompile/stats.json.
     * Used for offline re-evaluation, so a run stays evaluable even if the ingest code changed since.
     */
    PreparedApp savedApp() {
        AppModel model = store.readJson("02_ingest/app_model.json", AppModel.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = store.readJson("01_decompile/stats.json", Map.class);
        Path root = Path.of(String.valueOf(stats.get("decompiled_dir")));
        if (!java.nio.file.Files.isDirectory(root)) {
            throw new UserFacingException("the decompiled folder of this run is gone: " + root + " (run decompile again with the same APK)");
        }
        return new PreparedApp(null, model, new com.vulnscan.ingest.CodeBase(root));
    }

    /** Arm runs present in this run folder (independent of the current config's run counts). */
    public List<Integer> runsOf(Arm arm) {
        List<Integer> out = new ArrayList<>();
        for (int k = 1; store.exists(armDir(arm, k) + "findings.json"); k++) {
            out.add(k);
        }
        return out;
    }

    /** Decompile + ingest (always re-prepared on resume: the decompile cache makes it cheap; the ingest hash must match). */
    public PreparedApp prepare() {
        if (app != null) {
            return app;
        }
        long start = System.currentTimeMillis();
        PreparedApp prepared = preparer.prepare(input, cfg.app().app().inputSha256());
        if (store.isDone("ingest")) {
            String saved = store.readJson("02_ingest/app_model.json", AppModel.class).stats().ingestHash();
            if (!saved.equals(prepared.model().stats().ingestHash())) {
                throw new UserFacingException("the app input changed since this run started (ingest hash differs); start a new run");
            }
        }
        app = prepared;
        if (!store.isDone("decompile")) {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("app_id", prepared.decompiled().appId());
            stats.put("tool", prepared.decompiled().tool());
            stats.put("tool_version", prepared.decompiled().toolVersion());
            stats.put("layout", prepared.decompiled().layout().kind());
            stats.put("decompiled_dir", prepared.decompiled().layout().root().toString());
            stats.put("stats", prepared.decompiled().stats());
            store.writeJson("01_decompile/stats.json", stats);
            manifest.put("jadx_version", prepared.decompiled().toolVersion());
            finish("decompile", start);
        }
        if (!store.isDone("ingest")) {
            store.writeJson("02_ingest/app_model.json", prepared.model());
            manifest.put("ingest_hash", prepared.model().stats().ingestHash());
            var s = prepared.model().stats();
            progress.accept("ingest: " + s.filesFirstParty() + " first-party files of " + s.filesTotal() + " (prefixes "
                    + s.firstPartyPrefixes() + ")");
            finish("ingest", start);
        }
        return app;
    }

    public void plan() {
        prepare();
        stage("plan", () -> {
            var roles = cfg.models().roles();
            UnitPlan teacher = planner.plan(roles.teacher(), spec(roles.teacher()), false, app.model(), app.code());
            UnitPlan student = planner.plan(roles.student(), spec(roles.student()), true, app.model(), app.code());
            store.writeJson("03_plan/units_teacher.json", teacher);
            store.writeJson("03_plan/units_student.json", student);
            progress.accept("plan: teacher " + teacher.units().size() + " unit(s) (" + teacher.mode() + "), student "
                    + student.units().size() + " unit(s) (" + student.mode() + ")");
        });
    }

    /** All runs of one arm (teacher_runs or student_runs from the profile). */
    public void scanArm(Arm arm) {
        prepare();
        plan();
        int runs = arm == Arm.TEACHER ? cfg.app().runs().teacherRuns() : cfg.app().runs().studentRuns();
        for (int k = 1; k <= runs; k++) {
            int run = k;
            stage(scanStage(arm, run), () -> scanArmRun(arm, run));
        }
    }

    public static String scanStage(Arm arm, int run) {
        return "scan:" + arm.id + ":run_" + run;
    }

    public static String armDir(Arm arm, int run) {
        return "04_scan/" + arm.id + "/run_" + run + "/";
    }

    private void scanArmRun(Arm arm, int run) {
        var roles = cfg.models().roles();
        String modelName = arm.isStudent() ? roles.student() : roles.teacher();
        ModelSpec spec = spec(modelName);
        UnitPlan plan = store.readJson("03_plan/units_" + (arm.isStudent() ? "student" : "teacher") + ".json", UnitPlan.class);
        String guidance = arm == Arm.STUDENT_WITH_LESSONS ? later.guidance(this) : null;

        UsageTracker usage = new UsageTracker();
        AppConfig.Evidence ev = cfg.app().scan().evidence();
        Scanner scanner = new Scanner(models.forModel(modelName, usage), spec, modelName,
                new PromptBuilder(prompts.get("scan", cfg.app().scan().promptVersion()), app.model(), app.code()),
                new FindingParser(), new Normalizer(), new Splitter(),
                new EvidenceVerifier(app.code(), ev.fuzzyThreshold(), ev.minEvidenceChars()),
                unit -> planner.split(unit, modelName, spec, arm.isStudent(), app.model(), app.code()))
                .withPerturbOrder(arm.isStudent() && cfg.app().scan().perturbOrder());
        String dir = armDir(arm, run);
        int total = plan.units().size();
        int[] done = {0};
        progress.accept("scan " + arm.id + " run " + run + ": " + total + " unit(s) with " + spec.model());
        ArmScanner.ArmResult result = armScanner.scanAll(scanner, plan.units(), guidance,
                new ScanRun(arm.id, store.runId(), run - 1), r -> {
                    store.writeJson(dir + "raw/" + r.unitId() + ".json", rawRecord(r));
                    synchronized (done) {
                        done[0]++;
                        progress.accept(String.format("  [%d/%d] unit %s: %s, %d finding(s)%s", done[0], total, r.unitId(),
                                r.status(), r.findings().size(), r.anyCached() ? " (cached)" : ""));
                    }
                    store.log("unit_done", Map.of("arm", arm.id, "run", run, "unit", r.unitId(), "status", r.status()));
                });
        store.writeJson(dir + "findings.json", new ArmOutput(1, arm.id, run, spec.model(), result.findings()));
        store.writeJson(dir + "results.json", Map.of("schema_version", 1, "units", result.results().stream().map(UnitSummary::of).toList()));
        store.writeJson(dir + "usage.json", usage.snapshot());
        long verified = result.findings().stream().filter(f -> f.isVerified()).count();
        progress.accept("scan " + arm.id + " run " + run + ": " + result.findings().size() + " findings (" + verified
                + " verified), cost $" + String.format("%.4f", usage.snapshot().stream().mapToDouble(l -> l.cost).sum()));
    }

    private static Map<String, Object> rawRecord(ScanResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unit_id", r.unitId());
        m.put("status", r.status());
        m.put("errors", r.errors());
        m.put("exchanges", r.exchanges());
        List<Object> subs = new ArrayList<>();
        r.subUnits().forEach(s -> subs.add(rawRecord(s)));
        m.put("sub_units", subs);
        return m;
    }

    private void stage(String name, Runnable body) {
        if (store.isDone(name)) {
            log.info("stage {} already done, skipping", name);
            return;
        }
        long start = System.currentTimeMillis();
        store.log("stage_start", Map.of("stage", name));
        body.run();
        finish(name, start);
    }

    private void finish(String name, long start) {
        long ms = System.currentTimeMillis() - start;
        store.markDone(name);
        store.log("stage_done", Map.of("stage", name, "ms", ms));
        manifest.putIn("stage_ms", name, ms);
        prompts.used().forEach((k, v) -> manifest.putIn("prompts", k, v)); // merge: a resumed run keeps earlier prompts
        store.writeJson("manifest.json", manifest.snapshot());
    }

    public ModelSpec spec(String name) {
        return cfg.models().spec(name);
    }

    public LoadedConfig config() {
        return cfg;
    }

    public PreparedApp app() {
        return prepare();
    }

    public PromptLibrary prompts() {
        return prompts;
    }

    public ModelClientFactory models() {
        return models;
    }

    public RunManifest manifest() {
        return manifest;
    }

    public Consumer<String> progress() {
        return progress;
    }
}
