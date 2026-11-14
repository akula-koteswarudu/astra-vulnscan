package com.vulnscan.models;

import com.vulnscan.config.ModelsConfig.ModelSpec;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;

/**
 * provider name -> adapter constructor. Adding a provider = one adapter class + one line here
 * (and the name in ConfigLoader.KNOWN_PROVIDERS so configs are validated early).
 */
public class ProviderRegistry {

    private final Map<String, BiFunction<ModelSpec, String, LlmClient>> adapters = new TreeMap<>();

    public ProviderRegistry() {
        adapters.put("anthropic", AnthropicClient::new);
        adapters.put("openai_compatible", OpenAiCompatibleClient::new);
    }

    /** For tests: a registry with the given adapters (e.g. fakes). */
    public ProviderRegistry(Map<String, BiFunction<ModelSpec, String, LlmClient>> custom) {
        adapters.putAll(custom);
    }

    public LlmClient create(ModelSpec spec, String apiKey) {
        BiFunction<ModelSpec, String, LlmClient> factory = adapters.get(spec.provider());
        if (factory == null) {
            throw new IllegalArgumentException("no adapter for provider '" + spec.provider() + "' (known: " + adapters.keySet() + ")");
        }
        return factory.apply(spec, apiKey);
    }

    public java.util.Set<String> providers() {
        return adapters.keySet();
    }
}
