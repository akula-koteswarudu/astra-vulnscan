package com.vulnscan.models;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.helpers.BetaMessageAccumulator;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaJsonOutputFormat;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.vulnscan.config.ModelsConfig.ModelSpec;
import java.time.Duration;
import java.util.Map;

/**
 * Adapter for the Anthropic Messages API (anthropic-java SDK, beta endpoint because server-side fallbacks are beta).
 * <ul>
 *   <li>Streaming + {@link BetaMessageAccumulator}: large max_tokens (adaptive thinking + JSON) would time out otherwise.</li>
 *   <li>Structured output: {@code output_config.format} with the request's JSON schema.</li>
 *   <li>params passthrough: {@code output_config.effort}; {@code fallbacks: default} (adds beta header
 *       server-side-fallback-2026-07-01); anything else is sent as an extra body field.</li>
 *   <li>temperature is omitted when null (Sonnet 5.5 rejects non-default sampling).</li>
 *   <li>stop_reason refusal is mapped with its stop_details category; the model that answered is recorded.</li>
 * </ul>
 * SDK retries are set from config (0): {@link RetryingClient} owns retries.
 */
public class AnthropicClient implements LlmClient {

    /** Fully qualified: the SDK's client type has the same simple name as this adapter. */
    private final com.anthropic.client.AnthropicClient sdk;
    private final ModelSpec spec;

    public AnthropicClient(ModelSpec spec, String apiKey) {
        this.spec = spec;
        this.sdk = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .maxRetries(spec.sdkMaxRetries())
                .timeout(Duration.ofMinutes(20))
                .build();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        MessageCreateParams params = buildParams(request);
        long start = System.currentTimeMillis();
        BetaMessageAccumulator acc = BetaMessageAccumulator.create();
        try (StreamResponse<BetaRawMessageStreamEvent> stream = sdk.beta().messages().createStreaming(params)) {
            stream.stream().forEach(acc::accumulate);
        } catch (AnthropicServiceException e) {
            int status = e.statusCode();
            throw new LlmException("anthropic " + status + ": " + e.apiMessage().orElse(e.getMessage()),
                    LlmException.isRetryableStatus(status), status, LlmException.parseRetryAfter(e.headers().values("retry-after")), e);
        } catch (AnthropicIoException e) {
            throw new LlmException("anthropic connection error: " + e.getMessage(), true, 0, null, e);
        }
        BetaMessage message = acc.message();
        return toResponse(message, System.currentTimeMillis() - start);
    }

    MessageCreateParams buildParams(LlmRequest request) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(spec.model())
                .maxTokens(request.maxTokens());
        if (request.system() != null) {
            b.system(request.system());
        }
        for (LlmRequest.Message m : request.messages()) {
            if ("assistant".equals(m.role())) {
                b.addAssistantMessage(m.content());
            } else {
                b.addUserMessage(m.content());
            }
        }
        if (request.temperature() != null) {
            b.putAdditionalBodyProperty("temperature", JsonValue.from(request.temperature()));
        }

        BetaOutputConfig.Builder output = null;
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            switch (e.getKey()) {
                case "output_config" -> {
                    output = BetaOutputConfig.builder();
                    if (e.getValue() instanceof Map<?, ?> oc && oc.get("effort") != null) {
                        output.effort(BetaOutputConfig.Effort.of(oc.get("effort").toString()));
                    }
                }
                case "fallbacks" -> {
                    if ("default".equals(e.getValue())) {
                        b.fallbacksDefault();
                        b.addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01);
                    } else {
                        b.putAdditionalBodyProperty("fallbacks", JsonValue.from(e.getValue()));
                        b.addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01);
                    }
                }
                default -> b.putAdditionalBodyProperty(e.getKey(), JsonValue.from(e.getValue()));
            }
        }
        if (request.responseSchema() != null) {
            if (output == null) {
                output = BetaOutputConfig.builder();
            }
            BetaJsonOutputFormat.Schema.Builder schema = BetaJsonOutputFormat.Schema.builder();
            request.responseSchema().forEach((k, v) -> schema.putAdditionalProperty(k, JsonValue.from(v)));
            output.format(BetaJsonOutputFormat.builder().schema(schema.build()).build());
        }
        if (output != null) {
            b.outputConfig(output.build());
        }
        return b.build();
    }

    static LlmResponse toResponse(BetaMessage message, long latencyMs) {
        StringBuilder text = new StringBuilder();
        for (BetaContentBlock block : message.content()) {
            block.text().ifPresent(t -> text.append(t.text()));
        }
        LlmResponse.StopReason stop = message.stopReason().map(AnthropicClient::mapStop).orElse(LlmResponse.StopReason.OTHER);
        String category = message.stopDetails()
                .flatMap(d -> d.category().map(c -> c.asString()))
                .orElse(null);
        long input = message.usage().inputTokens()
                + message.usage().cacheCreationInputTokens().orElse(0L)
                + message.usage().cacheReadInputTokens().orElse(0L);
        return new LlmResponse(text.toString(), input, message.usage().outputTokens(), stop, category,
                message.model().asString(), latencyMs, false, true, 0);
    }

    private static LlmResponse.StopReason mapStop(BetaStopReason r) {
        if (r.equals(BetaStopReason.END_TURN) || r.equals(BetaStopReason.STOP_SEQUENCE)) {
            return LlmResponse.StopReason.END;
        }
        if (r.equals(BetaStopReason.MAX_TOKENS)) {
            return LlmResponse.StopReason.MAX_TOKENS;
        }
        if (r.equals(BetaStopReason.REFUSAL)) {
            return LlmResponse.StopReason.REFUSAL;
        }
        return LlmResponse.StopReason.OTHER;
    }
}
