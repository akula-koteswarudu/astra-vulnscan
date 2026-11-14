package com.vulnscan.cli;

import com.vulnscan.config.ConfigLoader;
import com.vulnscan.config.LoadedConfig;
import com.vulnscan.pipeline.Arm;
import com.vulnscan.pipeline.Experiment;
import com.vulnscan.wiring.PipelineFactory;
import com.vulnscan.wiring.Stages;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** vulnscan scan --config X --arm teacher [--run runs/&lt;dir&gt;]: one arm (plus decompile/ingest/plan if not done). */
@Component
@Command(name = "scan", description = "Run one arm (teacher | student_baseline | student_with_lessons) in a new or existing run folder.")
public class ScanCommand implements Callable<Integer> {

    @Option(names = "--config", required = true, description = "per-app config file")
    Path config;

    @Option(names = "--arm", required = true, description = "teacher | student_baseline | student_with_lessons")
    String arm;

    @Option(names = "--run", description = "existing run folder (default: start a new one)")
    Path run;

    private final ConfigLoader loader;
    private final PipelineFactory factory;
    private final Stages stages;

    public ScanCommand(ConfigLoader loader, PipelineFactory factory, Stages stages) {
        this.loader = loader;
        this.factory = factory;
        this.stages = stages;
    }

    @Override
    public Integer call() {
        Arm which = Arm.of(arm);
        LoadedConfig cfg = loader.load(config);
        Experiment e = factory.experiment(cfg, run == null ? null : factory.resolve(run.toString()), stages.later(cfg), System.out::println);
        System.out.println("run folder: " + e.store().dir());
        e.scanArm(which);
        return 0;
    }
}
