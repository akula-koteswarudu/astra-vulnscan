package com.vulnscan.models;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.vulnscan.config.ConfigLoader;
import com.vulnscan.config.LoadedConfig;
import com.vulnscan.config.ModelsConfig.ModelSpec;
import com.vulnscan.config.Secrets;
import jakarta.validation.Validation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opt-in real API smoke calls (tiny prompts). Prints responses and the providers' rate-limit headers
 * (never the keys). Run: ./mvnw test -Dgroups=integration -Dtest.excludedGroups=none -Dtest=ProviderSmokeTest
 */
@Tag("integration")
class ProviderSmokeTest {

    static final Map<String, Object> SCHEMA = Map.of("type", "object",
            "properties", Map.of("ok", Map.of("type", "boolean"), "word", Map.of("type", "string")),
            "required", List.of("ok", "word"), "additionalProperties", false);

    @Test
    void eachProviderAnswers() {
        LoadedConfig cfg = new ConfigLoader(Validation.buildDefaultValidatorFactory().getValidator(), Path.of(""))
                .load(Path.of("configs/insecureshop.yaml"));
        Secrets secrets = Secrets.load(Path.of(".env"));
        ProviderRegistry registry = new ProviderRegistry();
        for (String role : List.of("teacher", "student", "judge")) {
            ModelSpec spec = cfg.models().spec(role);
            LlmClient client = registry.create(spec, secrets.require(spec.apiKeyEnv()));
            LlmRequest r = new LlmRequest("Answer with a JSON object only: {\"ok\": true, \"word\": <one English word>}.",
                    List.of(LlmRequest.Message.user("Give me the word for the colour of the sky.")), SCHEMA,
                    role.equals("teacher") ? 2000 : spec.maxOutputTokens(), spec.temperature(), spec.paramsOrEmpty(),
                    LlmRequest.Metadata.of("smoke"));
            LlmResponse resp = client.complete(r);
            System.out.printf("SMOKE %s model=%s stop=%s in=%d out=%d latency=%dms text=%s%n", role, resp.modelUsed(),
                    resp.stopReason(), resp.inputTokens(), resp.outputTokens(), resp.latencyMs(),
                    resp.text().replace("\n", "\\n"));
        }
    }

    @Test
    void printRateLimitHeaders() {
        Secrets secrets = Secrets.load(Path.of(".env"));
        var anthropic = AnthropicOkHttpClient.builder().apiKey(secrets.require("ANTHROPIC_API_KEY")).maxRetries(0).build();
        var raw = anthropic.messages().withRawResponse().create(com.anthropic.models.messages.MessageCreateParams.builder()
                .model("claude-sonnet-5-5").maxTokens(200).addUserMessage("Say OK.")
                .outputConfig(com.anthropic.models.messages.OutputConfig.builder()
                        .effort(com.anthropic.models.messages.OutputConfig.Effort.LOW).build()).build());
        raw.headers().names().stream().filter(n -> n.toLowerCase().contains("ratelimit")).sorted()
                .forEach(n -> System.out.println("HEADER anthropic " + n + ": " + raw.headers().values(n)));
        var groq = OpenAIOkHttpClient.builder().apiKey(secrets.require("GROQ_API_KEY")).baseUrl("https://api.groq.com/openai/v1")
                .maxRetries(0).build();
        for (String model : List.of("qwen/qwen3.8-27b", "openai/gpt-oss-120b")) {
            var r = groq.chat().completions().withRawResponse().create(ChatCompletionCreateParams.builder().model(model)
                    .addUserMessage("Say OK.").putAdditionalBodyProperty("max_tokens", com.openai.core.JsonValue.from(50)).build());
            r.headers().names().stream().filter(n -> n.toLowerCase().contains("ratelimit")).sorted()
                    .forEach(n -> System.out.println("HEADER " + model + " " + n + ": " + r.headers().values(n)));
        }
    }

    @Test
    void probeGroqOutputLimits() {
        Secrets secrets = Secrets.load(Path.of(".env"));
        var groq = OpenAIOkHttpClient.builder().apiKey(secrets.require("GROQ_API_KEY")).baseUrl("https://api.groq.com/openai/v1")
                .maxRetries(0).build();
        for (String model : List.of("qwen/qwen3.8-27b", "openai/gpt-oss-120b")) {
            for (int max : List.of(900, 1200, 4000)) {
                try {
                    groq.chat().completions().create(ChatCompletionCreateParams.builder().model(model).addUserMessage("Say OK.")
                            .putAdditionalBodyProperty("max_tokens", com.openai.core.JsonValue.from(max)).build());
                    System.out.println("PROBE " + model + " max_tokens=" + max + ": accepted");
                } catch (com.openai.errors.OpenAIServiceException e) {
                    String msg = String.valueOf(e.getMessage());
                    int i = msg.indexOf("on ");
                    System.out.println("PROBE " + model + " max_tokens=" + max + ": " + e.statusCode() + " "
                            + msg.substring(Math.max(0, i), Math.min(msg.length(), i + 90)).replaceAll("org_\\w+", "org_…"));
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }
}
