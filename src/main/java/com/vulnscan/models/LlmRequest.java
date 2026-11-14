package com.vulnscan.models;

import java.util.List;
import java.util.Map;

/**
 * Provider-neutral request.
 * responseSchema: JSON schema for structured output (null = free text); adapters use it where the provider supports it.
 * temperature: null = omit (some models reject non-default sampling). params: provider-specific passthrough.
 * metadata is for tracking only and is NOT part of the cache key, except repeatIndex.
 */
public record LlmRequest(String system, List<Message> messages, Map<String, Object> responseSchema, int maxTokens,
                         Double temperature, Map<String, Object> params, Metadata metadata) {

    public record Message(String role, String content) {
        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }

    /** purpose: scan | repair | distill | judge_screen | judge_confirm | smoke. */
    public record Metadata(String purpose, String arm, String runId, String unitId, int repeatIndex) {
        public static Metadata of(String purpose) {
            return new Metadata(purpose, null, null, null, 0);
        }
    }

    public LlmRequest withMaxTokens(int newMaxTokens) {
        return new LlmRequest(system, messages, responseSchema, newMaxTokens, temperature, params, metadata);
    }

    public LlmRequest withMessages(List<Message> newMessages) {
        return new LlmRequest(system, newMessages, responseSchema, maxTokens, temperature, params, metadata);
    }

    public LlmRequest withMetadata(Metadata newMetadata) {
        return new LlmRequest(system, messages, responseSchema, maxTokens, temperature, params, newMetadata);
    }

    /** Characters of all prompt text (for token estimates). */
    public int promptChars() {
        int n = system == null ? 0 : system.length();
        for (Message m : messages) {
            n += m.content().length();
        }
        return n;
    }
}
