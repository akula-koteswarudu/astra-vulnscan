package com.vulnscan.models;

import com.vulnscan.config.ConfigLoader;
import com.vulnscan.config.LoadedConfig;
import com.vulnscan.config.ModelsConfig.ModelSpec;
import com.vulnscan.config.Secrets;
import com.vulnscan.pipeline.PreparedApp;
import com.vulnscan.planning.ScanUnit;
import com.vulnscan.planning.UnitPlan;
import com.vulnscan.prompts.PromptLibrary;
import com.vulnscan.scan.PromptBuilder;
import com.vulnscan.wiring.PipelineFactory;
import jakarta.validation.Validation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opt-in: measures the student's real chars-per-token on a few real units (max_tokens 1, so almost free),
 * to calibrate planning.token_estimate_chars_per_token against a hard per-minute limit.
 */
@Tag("integration")
class TokenCalibrationTest {

    @Test
    void measureStudentCharsPerToken() {
        LoadedConfig cfg = new ConfigLoader(Validation.buildDefaultValidatorFactory().getValidator(), Path.of(""))
                .load(Path.of("configs/insecureshop.yaml"));
        PipelineFactory f = new PipelineFactory(Path.of("").toAbsolutePath());
        PreparedApp app = f.appPreparer(cfg).prepare(f.resolve(cfg.app().app().input()), null);
        PromptLibrary prompts = f.prompts();
        String student = cfg.models().roles().student();
        ModelSpec spec = cfg.models().spec(student);
        UnitPlan plan = f.planner(cfg, prompts).plan(student, spec, true, app.model(), app.code());
        PromptBuilder pb = new PromptBuilder(prompts.get("scan", "v1"), app.model(), app.code());
        LlmClient client = new ProviderRegistry().create(spec, Secrets.load(Path.of(".env")).require(spec.apiKeyEnv()));
        for (ScanUnit u : List.of(plan.units().get(0), plan.units().get(1), plan.units().get(plan.units().size() - 1))) {
            String user = pb.user(u, null);
            int chars = pb.system().length() + user.length();
            LlmResponse r = client.complete(new LlmRequest(pb.system(), List.of(LlmRequest.Message.user(user)), null, 1,
                    spec.temperature(), spec.paramsOrEmpty(), LlmRequest.Metadata.of("calibration")));
            System.out.printf("CALIB unit %s est=%d chars=%d real_input=%d chars/token=%.2f%n", u.root(), u.estTokens(), chars,
                    r.inputTokens(), chars / (double) r.inputTokens());
        }
    }
}
