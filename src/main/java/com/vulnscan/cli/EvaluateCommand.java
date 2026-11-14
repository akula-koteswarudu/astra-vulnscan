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
@Command(name = "evaluate", description = "Re-run evaluation and the report from a run folder's saved findings (no rescans).")
public class EvaluateCommand implements Callable<Integer> {

    @Option(names = "--config", required = true, description = "per-app config file (must match the run)")
    Path config;

    @Option(names = "--run", required = true, description = "run folder")
    Path run;

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
        LoadedConfig cfg = loader.load(config);
        Experiment e = factory.experiment(cfg, factory.resolve(run.toString()), stages.later(cfg), System.out::println);
        e.reevaluate();
        return 0;
    }
}
