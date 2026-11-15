package com.vulnscan.cli;

import com.vulnscan.config.ConfigLoader;
import com.vulnscan.config.LoadedConfig;
import com.vulnscan.pipeline.Experiment;
import com.vulnscan.wiring.PipelineFactory;
import com.vulnscan.wiring.Stages;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** vulnscan evaluate --config X --run runs/&lt;dir&gt;: re-run evaluation + report offline from saved findings. */
@Component
@Command(name = "evaluate", description = "Re-verify evidence and re-run evaluation and the report from a run folder's saved findings "
        + "(no rescans). The judge/matching config may differ from the run's; it is recorded in the manifest.")
public class EvaluateCommand implements Callable<Integer> {

    @Option(names = "--config", required = true, description = "per-app config file (must match the run)")
    Path config;

    @Option(names = "--run", required = true, description = "run folder")
    Path run;

    @Option(names = "--screen-orders", description = "override evaluate.screen_orders (quick look in content order + n-1 shuffles)")
    Integer screenOrders;

    private final ConfigLoader loader;
    private final PipelineFactory factory;
    private final Stages stages;

    public EvaluateCommand(ConfigLoader loader, PipelineFactory factory, Stages stages) {
        this.loader = loader;
        this.factory = factory;
        this.stages = stages;
    }

    @Override
    public Integer call() {
        LoadedConfig loaded = loader.load(config);
        LoadedConfig cfg = screenOrders == null ? loaded : new LoadedConfig(loaded.app().withScreenOrders(screenOrders), loaded.models(),
                loaded.matching(), loaded.appFile(), loaded.configHash());
        Experiment e = factory.experimentForEvaluation(cfg, factory.resolve(run.toString()), stages.later(cfg), System.out::println);
        e.reevaluate();
        return 0;
    }
}
