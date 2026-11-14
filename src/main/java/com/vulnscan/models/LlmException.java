package com.vulnscan.models;

/**
 * A provider call failed. retryable: 429, 5xx, timeouts, connection errors. retryAfterSeconds from the
 * provider's retry-after header when present (null otherwise). Messages never contain API keys.
 */
public class LlmException extends RuntimeException {

    private final boolean retryable;
    private final int statusCode;
    private final Double retryAfterSeconds;

    public LlmException(String message, boolean retryable, int statusCode, Double retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.statusCode = statusCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static LlmException nonRetryable(String message) {
        return new LlmException(message, false, 0, null, null);
    }

    public boolean retryable() {
        return retryable;
    }

    public int statusCode() {
        return statusCode;
    }

    public Double retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** 429, 408, 409 and 5xx are retryable; other 4xx are not. Status 0 = no HTTP response (I/O error). */
    public static boolean isRetryableStatus(int status) {
        return status == 0 || status == 408 || status == 409 || status == 429 || status >= 500;
    }

    static Double parseRetryAfter(java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(values.get(0).strip());
        } catch (NumberFormatException e) {
            return null; // HTTP-date form: fall back to our own backoff
        }
    }
}
