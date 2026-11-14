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
                spec.temperature(), spec.paramsOrEmpty(), metadata);
    }
}
