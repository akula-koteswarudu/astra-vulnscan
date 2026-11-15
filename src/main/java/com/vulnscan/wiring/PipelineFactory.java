package com.vulnscan.wiring;

import com.vulnscan.common.TokenEstimator;
import com.vulnscan.config.AppConfig;
import com.vulnscan.config.LoadedConfig;
import com.vulnscan.ingest.DependencyInventory;
import com.vulnscan.ingest.Ingestor;
import com.vulnscan.ingest.InputResolver;
import com.vulnscan.ingest.JadxDecompiler;
import com.vulnscan.ingest.LayoutDetector;
import com.vulnscan.ingest.ManifestParser;
import com.vulnscan.ingest.ResourceExtractor;
import com.vulnscan.ingest.SecretPatterns;
import com.vulnscan.ingest.SystemProcessRunner;
import com.vulnscan.pipeline.AppPreparer;
import com.vulnscan.planning.DependencyGraphBuilder;
import com.vulnscan.planning.Prioritizer;
import com.vulnscan.planning.ScanPlanner;
import com.vulnscan.planning.TextReferenceGraphBuilder;
import com.vulnscan.planning.UnitContextBuilder;
import com.vulnscan.prompts.PromptLibrary;
import com.vulnscan.common.Ticker;
import com.vulnscan.config.Secrets;
import com.vulnscan.ingest.ProcessRunner;
import com.vulnscan.models.ModelClientFactory;
import com.vulnscan.models.ProviderRegistry;
import com.vulnscan.pipeline.Experiment;
import com.vulnscan.pipeline.LaterStages;
import com.vulnscan.pipeline.RunManifest;
import com.vulnscan.pipeline.RunStore;
import com.vulnscan.scan.ArmScanner;
import com.vulnscan.scan.Deduplicator;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Composition root (plan section 3 / 13.1). The object graph depends on the per-app config chosen on the command
 * line, so it is built here from the loaded config rather than as Spring beans. Domain classes stay framework-free
 * and receive their collaborators through constructors.
 */
@Component
public class PipelineFactory {

    private final Path baseDir;

    public PipelineFactory(Path baseDir) {
        this.baseDir = baseDir;
    }

    public Path resolve(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p : baseDir.resolve(p).normalize();
    }

    public TokenEstimator tokenEstimator(LoadedConfig cfg) {
        AppConfig.Planning p = cfg.app().planning();
        return new TokenEstimator(p.tokenEstimateCharsPerToken(), p.tokenEstimateMargin());
    }

    public PromptLibrary prompts() {
        return new PromptLibrary(baseDir.resolve("prompts"));
    }

    public AppPreparer appPreparer(LoadedConfig cfg) {
        AppConfig app = cfg.app();
        LayoutDetector layout = new LayoutDetector();
        JadxDecompiler jadx = new JadxDecompiler(resolve(app.decompile().jadxPath()), app.decompile().showBadCode(),
                Duration.ofSeconds(app.decompile().timeoutS()), app.decompile().javaOpts(), resolve(app.cache().dir()),
                new SystemProcessRunner(), layout);
        AppConfig.StringsFilter sf = app.ingest().stringsFilter();
        Ingestor ingestor = new Ingestor(app.ingest(), new ManifestParser(),
                new ResourceExtractor(sf.namePatterns(), new SecretPatterns(sf.extraSecretRegexes()), sf.minEntropy(), sf.minLength()),
                new DependencyInventory());
        return new AppPreparer(new InputResolver(jadx, layout), ingestor);
    }

    public ModelClientFactory modelClients(LoadedConfig cfg) {
        return new ModelClientFactory(cfg.models(), new ProviderRegistry(), Secrets.load(baseDir.resolve(".env")),
                tokenEstimator(cfg), resolve(cfg.app().cache().dir()), cfg.app().cache().llm(), Ticker.SYSTEM);
    }

    /** A new run (resumeDir == null) or a resumed one. */
    public Experiment experiment(LoadedConfig cfg, Path resumeDir, LaterStages later, Consumer<String> progress) {
        RunStore store;
        RunManifest manifest;
        if (resumeDir == null) {
            store = RunStore.create(resolve(cfg.app().output().runsDir()), cfg.app().app().name(), cfg.configHash(), Instant.now());
            manifest = newManifest(cfg);
            store.writeJson("manifest.json", manifest.snapshot());
        } else {
            store = RunStore.resume(resumeDir, cfg.configHash());
            @SuppressWarnings("unchecked")
            Map<String, Object> existing = store.readJson("manifest.json", Map.class);
            manifest = RunManifest.from(existing);
        }
        PromptLibrary prompts = prompts();
        return new Experiment(cfg, store, manifest, appPreparer(cfg), resolve(cfg.app().app().input()), prompts,
                planner(cfg, prompts), modelClients(cfg), new ArmScanner(new Deduplicator(cfg.matching().cweGroups())), later, progress);
    }

    /** An existing run opened for offline re-evaluation (no config-hash check; see RunStore.openForEvaluation). */
    public Experiment experimentForEvaluation(LoadedConfig cfg, Path runDir, LaterStages later, Consumer<String> progress) {
        RunStore store = RunStore.openForEvaluation(runDir);
        @SuppressWarnings("unchecked")
        Map<String, Object> existing = store.readJson("manifest.json", Map.class);
        PromptLibrary prompts = prompts();
        return new Experiment(cfg, store, RunManifest.from(existing), appPreparer(cfg), resolve(cfg.app().app().input()), prompts,
                planner(cfg, prompts), modelClients(cfg), new ArmScanner(new Deduplicator(cfg.matching().cweGroups())), later, progress);
    }

    private RunManifest newManifest(LoadedConfig cfg) {
        RunManifest m = new RunManifest();
        m.put("schema_version", 1);
        m.put("created_at", Instant.now().toString());
        m.put("config_hash", cfg.configHash());
        m.put("config_file", baseDir.relativize(cfg.appFile()).toString());
        m.put("profile", cfg.app().profile());
        Map<String, Object> requested = new LinkedHashMap<>();
        var roles = cfg.models().roles();
        for (String role : List.of(roles.teacher(), roles.student(), roles.judge())) {
            requested.put(role, cfg.models().spec(role).provider() + ":" + cfg.models().spec(role).model());
        }
        m.put("models_requested", requested);
        m.put("git", git());
        // Resolved configuration. Secrets are never part of it (configs only name the env variables).
        m.put("config", Map.of("app", cfg.app(), "models", cfg.models(), "matching", cfg.matching()));
        return m;
    }

    private Map<String, Object> git() {
        ProcessRunner runner = new SystemProcessRunner();
        Map<String, Object> g = new LinkedHashMap<>();
        try {
            ProcessRunner.Result head = runner.run(List.of("git", "rev-parse", "HEAD"), Map.of(), baseDir, Duration.ofSeconds(10));
            ProcessRunner.Result status = runner.run(List.of("git", "status", "--porcelain"), Map.of(), baseDir, Duration.ofSeconds(10));
            g.put("commit", head.exitCode() == 0 ? head.output().strip() : null);
            g.put("dirty", status.exitCode() == 0 && !status.output().isBlank());
        } catch (RuntimeException e) {
            g.put("commit", null);
        }
        return g;
    }

    public ScanPlanner planner(LoadedConfig cfg, PromptLibrary prompts) {
        TokenEstimator estimator = tokenEstimator(cfg);
        int systemTokens = estimator.estimate(prompts.get("scan", cfg.app().scan().promptVersion()).system());
        DependencyGraphBuilder graph = new TextReferenceGraphBuilder(); // androguard is rejected by the config validator
        return new ScanPlanner(cfg.app().planning(), estimator, new Prioritizer(), graph, new UnitContextBuilder(), systemTokens);
    }
}
