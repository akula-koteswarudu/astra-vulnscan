package com.vulnscan.models;

/** Port for any chat model. Adapters talk to a provider; decorators add caching, usage, retries, rate limits. */
public interface LlmClient {
    LlmResponse complete(LlmRequest request);
}
