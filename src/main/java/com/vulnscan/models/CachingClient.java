package com.vulnscan.models;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.vulnscan.common.Files2;
import com.vulnscan.common.Hashing;
import com.vulnscan.common.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Response cache on disk: {@code <dir>/<key[0..2]>/<key>.json}. Key = sha256 over provider, model, system, messages,
 * schema, temperature, max_tokens, params and repeat_index (so repeated runs stay independent).
 * A hit returns the stored response with cached=true, billed=false and its ORIGINAL usage, so a resumed run can still
 * report what the calls cost. Refusals and errors are never cached.
 */
public class CachingClient implements LlmClient {

    private final LlmClient inner;
    private final Path dir;
    private final String provider;
    private final String model;

    public CachingClient(LlmClient inner, Path dir, String provider, String model) {
        this.inner = inner;
        this.dir = dir;
        this.provider = provider;
        this.model = model;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String key = key(request);
        Path file = dir.resolve(key.substring(0, 2)).resolve(key + ".json");
        if (Files.isRegularFile(file)) {
            return Files2.readJson(file, LlmResponse.class).asCacheHit();
        }
        LlmResponse response = inner.complete(request);
        if (response.stopReason() != LlmResponse.StopReason.REFUSAL) {
            Files2.writeJson(file, response);
        }
        return response;
    }

    String key(LlmRequest r) {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("provider", provider);
        k.put("model", model);
        k.put("system", r.system());
        k.put("messages", r.messages());
        k.put("schema", r.responseSchema());
        k.put("temperature", r.temperature());
        k.put("max_tokens", r.maxTokens());
        k.put("params", r.params());
        k.put("repeat_index", r.metadata() == null ? 0 : r.metadata().repeatIndex());
        try {
            return Hashing.sha256(Json.json().writeValueAsString(k)); // map keys sorted by the mapper
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
