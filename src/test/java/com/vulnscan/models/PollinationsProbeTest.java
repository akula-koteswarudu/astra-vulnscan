package com.vulnscan.models;

import com.vulnscan.config.Secrets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Opt-in: saves Pollinations' model list (never prints the key). */
@Tag("integration")
class PollinationsProbeTest {

    @Test
    void listModels() throws Exception {
        String key = Secrets.load(Path.of(".env")).require("POLLINATIONS_API_KEY");
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("https://gen.pollinations.ai/v1/models"))
                .header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString());
        System.out.println("POLL status " + r.statusCode() + " bytes " + r.body().length());
        Files.writeString(Path.of("target/pollinations-models.json"), r.body());
    }

    static com.vulnscan.config.ModelsConfig.ModelSpec spec(String model) {
        return new com.vulnscan.config.ModelsConfig.ModelSpec("openai_compatible", "https://gen.pollinations.ai/v1", model,
                "POLLINATIONS_API_KEY", 262144, null, 1500, 0.0, com.vulnscan.config.ModelsConfig.JsonMode.INSTRUCTION, java.util.Map.of(),
                new com.vulnscan.config.ModelsConfig.Price(0, 0), new com.vulnscan.config.ModelsConfig.RateLimit(60, null, null, 1000000, null, null),
                "max_tokens", 1, 0, new com.vulnscan.config.ModelsConfig.Retry(1, 1));
    }

    @Test
    void smokeStudentAndJudgeCandidates() {
        String key = Secrets.load(Path.of(".env")).require("POLLINATIONS_API_KEY");
        for (String model : java.util.List.of("qwen/qwen3.8-27b", "deepseek/deepseek-v4.1-flash", "z-ai/glm-5.3-flash")) {
            LlmClient c = new OpenAiCompatibleClient(spec(model), key);
            for (int i = 0; i < 2; i++) {
                try {
                    LlmResponse r = c.complete(new LlmRequest("Answer with a JSON object only: {\"ok\": true, \"word\": <one word>}.",
                            java.util.List.of(LlmRequest.Message.user("Give the colour of the sky.")), null, 300, 0.0, java.util.Map.of(),
                            LlmRequest.Metadata.of("smoke")));
                    System.out.printf("PSMOKE %s try %d model=%s stop=%s in=%d out=%d %dms text=%s%n", model, i, r.modelUsed(), r.stopReason(),
                            r.inputTokens(), r.outputTokens(), r.latencyMs(), r.text().replace("\n", " "));
                } catch (Exception e) {
                    System.out.println("PSMOKE " + model + " ERROR " + String.valueOf(e.getMessage()).replaceAll("sk_\\w+", "sk_…"));
                }
            }
        }
    }

    @Test
    void wholeAppStudentRequest() throws Exception {
        String key = Secrets.load(Path.of(".env")).require("POLLINATIONS_API_KEY");
        var cfg = new com.vulnscan.config.ConfigLoader(jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator(), Path.of(""))
                .load(Path.of("configs/insecureshop.yaml"));
        var f = new com.vulnscan.wiring.PipelineFactory(Path.of("").toAbsolutePath());
        var app = f.appPreparer(cfg).prepare(f.resolve(cfg.app().app().input()), null);
        var prompts = f.prompts();
        var plan = f.planner(cfg, prompts).plan("teacher", cfg.models().spec("teacher"), false, app.model(), app.code());
        var pb = new com.vulnscan.scan.PromptBuilder(prompts.get("scan", "v1"), app.model(), app.code());
        String user = pb.user(plan.units().get(0), null);
        LlmClient c = new OpenAiCompatibleClient(spec("qwen/qwen3.8-27b"), key);
        LlmResponse r = null;
        for (String effort : java.util.List.of("none", "low")) {
            try {
                r = c.complete(new LlmRequest(pb.system(), java.util.List.of(LlmRequest.Message.user(user)), null, 8000, 0.0,
                        java.util.Map.of("reasoning_effort", effort), LlmRequest.Metadata.of("smoke")));
                System.out.println("PWHOLE reasoning_effort=" + effort + " accepted");
                break;
            } catch (LlmException e) {
                System.out.println("PWHOLE reasoning_effort=" + effort + " failed: " + e.getMessage());
            }
        }
        var parsed = new com.vulnscan.scan.FindingParser().parse(r.text());
        System.out.printf("PWHOLE chars=%d in=%d out=%d stop=%s %dms findings=%d bad=%d%n", pb.system().length() + user.length(),
                r.inputTokens(), r.outputTokens(), r.stopReason(), r.latencyMs(), parsed.findings().size(), parsed.badFindings());
        parsed.findings().forEach(x -> System.out.println("PWHOLE  - " + x.title()));
    }
}
