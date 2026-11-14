package com.vulnscan.models;

/**
 * Provider-neutral response. modelUsed may differ from the requested model when a server-side fallback ran.
 * cached = served from our response cache; billed = this call cost money/quota now (false on a cache hit).
 * Token counts are the ORIGINAL call's usage, also on a cache hit.
 */
public record LlmResponse(String text, long inputTokens, long outputTokens, StopReason stopReason, String refusalCategory,
                          String modelUsed, long latencyMs, boolean cached, boolean billed, int retries) {

    public enum StopReason { END, MAX_TOKENS, REFUSAL, OTHER }

    public LlmResponse asCacheHit() {
        return new LlmResponse(text, inputTokens, outputTokens, stopReason, refusalCategory, modelUsed, 0, true, false, 0);
    }

    public LlmResponse withRetries(int n) {
        return new LlmResponse(text, inputTokens, outputTokens, stopReason, refusalCategory, modelUsed, latencyMs, cached, billed, n);
    }
}
