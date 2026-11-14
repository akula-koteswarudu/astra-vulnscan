package com.vulnscan.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Map;

/** configs/models.yaml: model definitions plus the role -> model mapping. Adding a model is a config entry. */
public record ModelsConfig(@NotEmpty Map<String, @Valid ModelSpec> models, @NotNull @Valid Roles roles) {

    public record Roles(@NotBlank String teacher, @NotBlank String student, @NotBlank String judge) {
    }

    public ModelSpec spec(String name) {
        ModelSpec spec = models.get(name);
        if (spec == null) {
            throw new IllegalArgumentException("unknown model '" + name + "' (defined: " + models.keySet() + ")");
        }
        return spec;
    }

    /**
     * One model. provider selects the adapter from the registry. temperature null = omit from the request.
     * params is passed through to the provider unchanged (e.g. output_config.effort, fallbacks, reasoning_effort).
     */
    public record ModelSpec(
            @NotBlank String provider,
            String baseUrl,
            @NotBlank String model,
            @NotBlank String apiKeyEnv,
            @Positive int contextTokens,
            @Positive Integer planningBudgetTokens,
            @Positive int maxOutputTokens,
            Double temperature,
            JsonMode jsonMode,
            Map<String, Object> params,
            @NotNull @Valid Price pricePerMtok,
            @NotNull @Valid RateLimit rateLimit,
            String maxTokensParam,
            @Min(1) int concurrency,
            @Min(0) int sdkMaxRetries,
            @NotNull @Valid Retry retry) {

        public Map<String, Object> paramsOrEmpty() {
            return params == null ? Map.of() : params;
        }
    }

    public enum JsonMode {
        @JsonProperty("instruction") INSTRUCTION,
        @JsonProperty("json_object") JSON_OBJECT,
        @JsonProperty("json_schema") JSON_SCHEMA
    }

    public record Price(@DecimalMin("0.0") double input, @DecimalMin("0.0") double output) {
    }

    /**
     * Per-minute limits. Groq: rpm, tpm (input + output counted together), tpd.
     * Anthropic: rpm, itpm and otpm (input and output counted separately).
     */
    public record RateLimit(@Positive Integer rpm, @Positive Integer tpm, @Positive Integer tpd,
                            @Positive Integer itpm, @Positive Integer otpm, @Positive Integer rpd) {

        /** Per-minute INPUT capacity used by the planner: itpm when set (Anthropic), else tpm (Groq). */
        public Integer perMinuteInputLimit() {
            return itpm != null ? itpm : tpm;
        }

        /** True when output tokens count against the same per-minute limit as input (Groq's tpm). */
        public boolean outputSharesInputLimit() {
            return itpm == null && tpm != null;
        }
    }

    public record Retry(@Min(1) int maxAttempts, @DecimalMin("0.0") double baseDelayS) {
    }
}
