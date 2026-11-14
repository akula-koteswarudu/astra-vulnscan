package com.vulnscan.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.vulnscan.common.Json;
import com.vulnscan.config.ModelsConfig.JsonMode;
import com.vulnscan.config.ModelsConfig.ModelSpec;
import java.time.Duration;
import java.util.Map;

/**
 * Adapter for any OpenAI-compatible chat completions endpoint (Groq here; also OpenAI, Together, vLLM, Ollama)
 * via the openai-java SDK with a custom base URL.
 * <ul>
 *   <li>json_mode: instruction (the prompt asks for JSON) or json_object (response_format).</li>
 *   <li>The output cap is sent under the configured field name (max_tokens or max_completion_tokens).</li>
 *   <li>params are sent as extra body fields (e.g. reasoning_effort).</li>
 *   <li>Groq rejects an invalid JSON generation with HTTP 400 code json_validate_failed and the text in
 *       failed_generation; that is turned into a normal response so the parser's repair path runs.</li>
 * </ul>
 */
public class OpenAiCompatibleClient implements LlmClient {

    static final String GROQ_JSON_FAILED = "json_validate_failed";

    private final OpenAIClient client;
    private final ModelSpec spec;

    public OpenAiCompatibleClient(ModelSpec spec, String apiKey) {
        this.spec = spec;
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(spec.baseUrl())
                .maxRetries(spec.sdkMaxRetries())
                .timeout(Duration.ofMinutes(10))
                .build();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        ChatCompletionCreateParams params = buildParams(request);
        long start = System.currentTimeMillis();
        ChatCompletion completion;
        try {
            completion = client.chat().completions().create(params);
        } catch (OpenAIServiceException e) {
            int status = e.statusCode();
            LlmResponse fromFailedJson = failedJsonGeneration(e, System.currentTimeMillis() - start);
            if (fromFailedJson != null) {
                return fromFailedJson;
            }
            String message = errorMessage(e);
            throw new LlmException(spec.provider() + " " + status + ": " + message, isRetryable(status, message),
                    status, LlmException.parseRetryAfter(e.headers().values("retry-after")), e);
        } catch (OpenAIIoException e) {
            throw new LlmException(spec.provider() + " connection error: " + e.getMessage(), true, 0, null, e);
        }
        long latency = System.currentTimeMillis() - start;
        if (completion.choices().isEmpty()) {
            return new LlmResponse("", 0, 0, LlmResponse.StopReason.OTHER, null, completion.model(), latency, false, true, 0);
        }
        ChatCompletion.Choice choice = completion.choices().get(0);
        String text = choice.message().content().orElse("");
        LlmResponse.StopReason stop = switch (choice.finishReason().asString()) {
            case "stop" -> LlmResponse.StopReason.END;
            case "length" -> LlmResponse.StopReason.MAX_TOKENS;
            case "content_filter" -> LlmResponse.StopReason.REFUSAL;
            default -> LlmResponse.StopReason.OTHER;
        };
        long in = completion.usage().map(u -> u.promptTokens()).orElse(0L);
        long out = completion.usage().map(u -> u.completionTokens()).orElse(0L);
        return new LlmResponse(text, in, out, stop, null, completion.model(), latency, false, true, 0);
    }

    ChatCompletionCreateParams buildParams(LlmRequest request) {
        ChatCompletionCreateParams.Builder b = ChatCompletionCreateParams.builder().model(spec.model());
        if (request.system() != null) {
            b.addSystemMessage(request.system());
        }
        for (LlmRequest.Message m : request.messages()) {
            if ("assistant".equals(m.role())) {
                b.addAssistantMessage(m.content());
            } else {
                b.addUserMessage(m.content());
            }
        }
        String maxField = spec.maxTokensParam() == null ? "max_tokens" : spec.maxTokensParam();
        b.putAdditionalBodyProperty(maxField, JsonValue.from(request.maxTokens()));
        if (request.temperature() != null) {
            b.temperature(request.temperature());
        }
        if (spec.jsonMode() == JsonMode.JSON_OBJECT && request.responseSchema() != null) {
            b.responseFormat(ResponseFormatJsonObject.builder().build());
        }
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        params.forEach((k, v) -> b.putAdditionalBodyProperty(k, JsonValue.from(v)));
        return b.build();
    }

    /**
     * 429 is normally retryable, except "Request too large": that single request exceeds a per-minute limit and
     * waiting can never help (the caller must send less).
     */
    static boolean isRetryable(int status, String message) {
        if (status == 429 && message != null && message.contains("Request too large")) {
            return false;
        }
        return LlmException.isRetryableStatus(status);
    }

    /** Groq's invalid-JSON 400 -> a response carrying the failed generation (so the parser can repair it). */
    static LlmResponse failedJsonGeneration(OpenAIServiceException e, long latency) {
        if (e.statusCode() != 400) {
            return null;
        }
        JsonNode error = errorNode(e);
        if (error == null || !GROQ_JSON_FAILED.equals(error.path("code").asText())) {
            return null;
        }
        return new LlmResponse(error.path("failed_generation").asText(""), 0, 0, LlmResponse.StopReason.END,
                null, null, latency, false, true, 0);
    }

    private static JsonNode errorNode(OpenAIServiceException e) {
        try {
            JsonNode body = Json.json().readTree(Json.json().writeValueAsString(e.body()));
            return body.has("error") ? body.get("error") : body;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String errorMessage(OpenAIServiceException e) {
        JsonNode error = errorNode(e);
        return error != null && error.has("message") ? error.get("message").asText() : e.getMessage();
    }
}
