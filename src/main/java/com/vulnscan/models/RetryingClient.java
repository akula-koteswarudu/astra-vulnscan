package com.vulnscan.models;

import com.vulnscan.common.Ticker;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retries retryable failures (429, 5xx, timeouts, connection errors) with exponential backoff and jitter,
 * honouring retry-after when the provider sends it. Non-retryable errors (other 4xx) are thrown immediately.
 * Kept instead of SDK retries (set to 0) for the same behaviour and retry counts across providers.
 */
public class RetryingClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(RetryingClient.class);

    private final LlmClient inner;
    private final int maxAttempts;
    private final double baseDelaySeconds;
    private final Ticker ticker;
    private final Random random;

    public RetryingClient(LlmClient inner, int maxAttempts, double baseDelaySeconds, Ticker ticker, Random random) {
        this.inner = inner;
        this.maxAttempts = maxAttempts;
        this.baseDelaySeconds = baseDelaySeconds;
        this.ticker = ticker;
        this.random = random;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return inner.complete(request).withRetries(attempt - 1);
            } catch (LlmException e) {
                if (!e.retryable() || attempt >= maxAttempts) {
                    throw e;
                }
                long delayMs = delayMillis(attempt, e.retryAfterSeconds());
                log.warn("retryable error (attempt {}/{}), waiting {} ms: {}", attempt, maxAttempts, delayMs, e.getMessage());
                ticker.sleep(delayMs);
            }
        }
    }

    /** base * 2^(attempt-1) * jitter in [0.5, 1.5); retry-after (if larger) wins. */
    long delayMillis(int attempt, Double retryAfterSeconds) {
        double backoff = baseDelaySeconds * Math.pow(2, attempt - 1) * (0.5 + random.nextDouble());
        double seconds = retryAfterSeconds != null ? Math.max(retryAfterSeconds, backoff * 0.5) : backoff;
        return Math.round(seconds * 1000);
    }
}
