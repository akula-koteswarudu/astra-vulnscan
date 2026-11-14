package com.vulnscan.models;

import com.vulnscan.config.ModelsConfig.ModelSpec;
import java.util.List;
import java.util.Map;

/**
 * Builds requests with a model's configured defaults (max output tokens, temperature, params). Params travel in the
 * request rather than being merged inside the adapter, so they are part of the cache key: changing effort in
 * models.yaml can never return a stale cached answer.
 */
public final class RequestFactory {

    private RequestFactory() {
    }

    public static LlmRequest forModel(ModelSpec spec, String system, String user, Map<String, Object> schema,
                                      LlmRequest.Metadata metadata) {
        return new LlmRequest(system, List.of(LlmRequest.Message.user(user)), schema, spec.maxOutputTokens(),
                spec.temperature(), withRunSeed(spec.paramsOrEmpty(), metadata), metadata);
    }

    /**
     * A numeric "seed" param advances with the run (repeat index 0, 1, 2 -> seed, seed+1, seed+2), so repeated runs with
     * temperature > 0 are different samples, each reproducible. Run 1 keeps the configured seed.
     */
    static Map<String, Object> withRunSeed(Map<String, Object> params, LlmRequest.Metadata metadata) {
        if (metadata == null || metadata.repeatIndex() == 0 || !(params.get("seed") instanceof Number seed)) {
            return params;
        }
        Map<String, Object> copy = new java.util.LinkedHashMap<>(params);
        copy.put("seed", seed.longValue() + metadata.repeatIndex());
        return copy;
    }
}
