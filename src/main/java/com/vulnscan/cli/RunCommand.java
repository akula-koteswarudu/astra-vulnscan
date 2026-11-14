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

/** vulnscan run --config configs/app.yaml [--resume runs/&lt;dir&gt;] */
@Component
@Command(name = "run", description = "Run all stages: decompile, ingest, plan, teacher scan, student scan, lessons, student rescan, evaluate, report.")
public class RunCommand implements Callable<Integer> {

    @Option(names = "--config", required = true, description = "per-app config file")
    Path config;

    @Option(names = "--resume", description = "continue an existing run folder (skips completed stages)")
    Path resume;

    private final ConfigLoader loader;
    private final PipelineFactory factory;
    private final Stages stages;

    public RunCommand(ConfigLoader loader, PipelineFactory factory, Stages stages) {
        this.loader = loader;
        this.factory = factory;
        this.stages = stages;
    }

    @Override
    public Integer call() {
        LoadedConfig cfg = loader.load(config);
        Experiment e = factory.experiment(cfg, resume == null ? null : factory.resolve(resume.toString()), stages.later(cfg), System.out::println);
        System.out.println("run folder: " + e.store().dir());
        e.run();
        return 0;
    }
}
