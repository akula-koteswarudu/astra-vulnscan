package com.vulnscan.models;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.core.JsonValue;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.models.ErrorObject;
import com.vulnscan.config.ModelsConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Request building only: no network. */
class AdaptersTest {

    static ModelsConfig.ModelSpec spec(String provider, Map<String, Object> params, ModelsConfig.JsonMode mode) {
        return new ModelsConfig.ModelSpec(provider, "https://example.invalid/v1", "model-x", "KEY", 100000, null, 1000, null,
                mode, params, new ModelsConfig.Price(0, 0), new ModelsConfig.RateLimit(10, 8000, null, null, null, null),
                "max_tokens", 1, 0, new ModelsConfig.Retry(1, 1));
    }

    static LlmRequest request(Double temperature, Map<String, Object> schema) {
        return new LlmRequest("sys", List.of(LlmRequest.Message.user("hi")), schema, 500, temperature, null, LlmRequest.Metadata.of("test"));
    }

    @Test
    void anthropicOmitsTemperatureWhenNullAndMapsParams() {
        Map<String, Object> params = Map.of("output_config", Map.of("effort", "high"), "fallbacks", "default");
        AnthropicClient c = new AnthropicClient(spec("anthropic", params, null), "test-key");
        LlmRequest r = new LlmRequest("sys", List.of(LlmRequest.Message.user("hi")), Map.of("type", "object"), 500, null,
                params, LlmRequest.Metadata.of("test"));
        var p = c.buildParams(r);
        assertThat(p._additionalBodyProperties()).doesNotContainKey("temperature");
        assertThat(p.outputConfig()).isPresent();
        assertThat(p.outputConfig().get().effort().get().asString()).isEqualTo("high");
        assertThat(p.outputConfig().get().format()).isPresent();
        assertThat(p.fallbacks()).isPresent();
        assertThat(p.betas().get()).extracting(b -> b.asString()).contains("server-side-fallback-2026-07-01");

        var withTemp = c.buildParams(new LlmRequest("sys", List.of(LlmRequest.Message.user("hi")), null, 500, 0.0, Map.of(),
                LlmRequest.Metadata.of("test")));
        assertThat(withTemp._additionalBodyProperties()).containsKey("temperature");
        assertThat(withTemp.outputConfig()).isEmpty();
    }

    @Test
    void openAiCompatibleSendsConfiguredMaxTokensFieldAndParams() {
        OpenAiCompatibleClient c = new OpenAiCompatibleClient(spec("openai_compatible", Map.of("reasoning_effort", "low"),
                ModelsConfig.JsonMode.JSON_OBJECT), "test-key");
        var p = c.buildParams(new LlmRequest("sys", List.of(LlmRequest.Message.user("hi")), Map.of("type", "object"), 500, 0.0,
                Map.of("reasoning_effort", "low"), LlmRequest.Metadata.of("test")));
        assertThat(p._additionalBodyProperties()).containsKeys("max_tokens", "reasoning_effort");
        assertThat(p.temperature()).contains(0.0);
        assertThat(p.responseFormat()).isPresent();
        var noTemp = c.buildParams(request(null, null));
        assertThat(noTemp.temperature()).isEmpty();
        assertThat(noTemp.responseFormat()).isEmpty();
    }

    @Test
    void groqInvalidJson400BecomesAResponse() {
        ErrorObject error = ErrorObject.builder().code("json_validate_failed").message("Failed to generate JSON")
                .param("").type("invalid_request_error")
                .putAdditionalProperty("failed_generation", JsonValue.from("{\"findings\": [ {broken"))
                .build();
        BadRequestException e = BadRequestException.builder().headers(Headers.builder().build()).error(error).build();
        LlmResponse r = OpenAiCompatibleClient.failedJsonGeneration(e, 5);
        assertThat(r).isNotNull();
        assertThat(r.text()).isEqualTo("{\"findings\": [ {broken");
        assertThat(r.stopReason()).isEqualTo(LlmResponse.StopReason.END);

        ErrorObject other = ErrorObject.builder().code("model_not_found").message("x").param("").type("x").build();
        assertThat(OpenAiCompatibleClient.failedJsonGeneration(
                BadRequestException.builder().headers(Headers.builder().build()).error(other).build(), 5)).isNull();
    }
}
