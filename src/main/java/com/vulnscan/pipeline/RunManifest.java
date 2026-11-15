package com.vulnscan.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * manifest.json: resolved config (secrets are never in config: only env var names) and its hash, profile, prompt
 * versions and sha256, models requested, jadx version, git commit, ingest hash, stage timings and re-evaluations.
 * The model actually used per finding is in its provenance; tokens and cost are in each stage's usage.json.
 * Rewritten after every stage.
 */
public class RunManifest {

    private final Map<String, Object> data = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public static RunManifest from(Map<String, Object> existing) {
        RunManifest m = new RunManifest();
        m.data.putAll(existing);
        return m;
    }

    public synchronized RunManifest put(String key, Object value) {
        data.put(key, value);
        return this;
    }

    @SuppressWarnings("unchecked")
    public synchronized RunManifest putIn(String section, String key, Object value) {
        ((Map<String, Object>) data.computeIfAbsent(section, k -> new LinkedHashMap<String, Object>())).put(key, value);
        return this;
    }

    public synchronized Map<String, Object> snapshot() {
        return new LinkedHashMap<>(data);
    }
}
