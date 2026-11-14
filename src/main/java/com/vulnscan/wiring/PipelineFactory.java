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
import java.nio.file.Path;
import java.time.Duration;
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

    public ScanPlanner planner(LoadedConfig cfg, PromptLibrary prompts) {
        TokenEstimator estimator = tokenEstimator(cfg);
        int systemTokens = estimator.estimate(prompts.get("scan", cfg.app().scan().promptVersion()).system());
        DependencyGraphBuilder graph = new TextReferenceGraphBuilder(); // androguard is rejected by the config validator
        return new ScanPlanner(cfg.app().planning(), estimator, new Prioritizer(), graph, new UnitContextBuilder(), systemTokens);
    }
}
