package com.vulnscan.cli;

import com.vulnscan.config.ConfigLoader;
import com.vulnscan.config.LoadedConfig;
import com.vulnscan.pipeline.PreparedApp;
import com.vulnscan.planning.ScanPlanner;
import com.vulnscan.planning.ScanUnit;
import com.vulnscan.planning.UnitPlan;
import com.vulnscan.prompts.PromptLibrary;
import com.vulnscan.wiring.PipelineFactory;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** vulnscan tokens --config configs/app.yaml : per-model unit plans and token estimates, no LLM calls. */
@Component
@Command(name = "tokens", description = "Print per-model scan unit plans and token estimates (no LLM calls).")
public class TokensCommand implements Callable<Integer> {

    @Option(names = "--config", required = true, description = "per-app config file")
    Path config;

    private final ConfigLoader loader;
    private final PipelineFactory factory;

    public TokensCommand(ConfigLoader loader, PipelineFactory factory) {
        this.loader = loader;
        this.factory = factory;
    }

    @Override
    public Integer call() {
        LoadedConfig cfg = loader.load(config);
        PreparedApp app = factory.appPreparer(cfg).prepare(factory.resolve(cfg.app().app().input()), cfg.app().app().inputSha256());
        PromptLibrary prompts = factory.prompts();
        ScanPlanner planner = factory.planner(cfg, prompts);
        var s = app.model().stats();
        System.out.printf("app %s: %d files, %d first-party (prefixes %s), %d excluded%n", app.model().packageName(),
                s.filesTotal(), s.filesFirstParty(), s.firstPartyPrefixes(), s.filesExcluded());
        var roles = cfg.models().roles();
        print(planner.plan(roles.teacher(), cfg.models().spec(roles.teacher()), false, app.model(), app.code()));
        print(planner.plan(roles.student(), cfg.models().spec(roles.student()), true, app.model(), app.code()));
        return 0;
    }

    static void print(UnitPlan plan) {
        UnitPlan.Budget b = plan.budget();
        System.out.printf("%nmodel %s: mode %s, %d units, effective budget %d tokens/request%n", plan.model(), plan.mode(),
                plan.units().size(), b.effective());
        System.out.printf("  per-request cap = min(%d%s%s) = %d; minus overhead %d%s%n", b.contextOrPlanningBudget(),
                b.itpm() != null ? ", 0.8 x itpm " + b.itpm() : "",
                b.tpm() != null ? ", 0.8 x tpm " + b.tpm() + " - max_output " + b.maxOutput() : "", b.perRequestCap(),
                b.promptOverhead(), b.reservedGuidance() > 0 ? ", minus reserved guidance " + b.reservedGuidance() : "");
        int total = 0;
        for (ScanUnit u : plan.units()) {
            total += u.estTokens();
            System.out.printf("  %-11s %-55s %5d tok  prio %3d  %s%n", u.kind(), u.root(), u.estTokens(), u.priority(),
                    u.files().stream().map(f -> f.substring(f.lastIndexOf('/') + 1)).toList());
        }
        System.out.printf("  total %d tokens across units%n", total);
        plan.skipped().forEach(sk -> System.out.printf("  SKIPPED %s (%d tok): %s%n", sk.what(), sk.estTokens(), sk.reason()));
    }
}
