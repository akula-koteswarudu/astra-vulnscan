package com.vulnscan.models;

import com.vulnscan.common.Ticker;
import com.vulnscan.common.TokenEstimator;
import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side sliding-window limiter (60 s) for requests/min and tokens/min, plus a daily counter that warns
 * near the daily limits. Token estimate before a call = input estimate (+ max_tokens when the provider counts
 * output against the same per-minute limit, like Groq's tpm); reconciled with the real usage afterwards.
 * Output tokens are limited separately when an output limit exists (Anthropic otpm).
 * A single request that can never fit the per-minute capacity fails fast instead of waiting forever.
 */
public class RateLimitedClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(RateLimitedClient.class);
    private static final long WINDOW_MS = 60_000;

    private final LlmClient inner;
    private final Integer rpm;
    private final int inputPerMinute;
    private final boolean outputSharesInput;
    private final Integer outputPerMinute;
    private final Integer tokensPerDay;
    private final Integer requestsPerDay;
    private final TokenEstimator estimator;
    private final Ticker ticker;

    private final Deque<Entry> requests = new ArrayDeque<>();
    private final Deque<Entry> inputTokens = new ArrayDeque<>();
    private final Deque<Entry> outputTokens = new ArrayDeque<>();
    private long dayTokens;
    private long dayRequests;

    private record Entry(long at, long amount) {
    }

    public RateLimitedClient(LlmClient inner, Integer rpm, int inputPerMinute, boolean outputSharesInput, Integer outputPerMinute,
                             Integer tokensPerDay, Integer requestsPerDay, TokenEstimator estimator, Ticker ticker) {
        this.inner = inner;
        this.rpm = rpm;
        this.inputPerMinute = inputPerMinute;
        this.outputSharesInput = outputSharesInput;
        this.outputPerMinute = outputPerMinute;
        this.tokensPerDay = tokensPerDay;
        this.requestsPerDay = requestsPerDay;
        this.estimator = estimator;
        this.ticker = ticker;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long inEstimate = estimator.estimate(request.promptChars()) + (outputSharesInput ? request.maxTokens() : 0);
        if (inEstimate > inputPerMinute) {
            throw LlmException.nonRetryable("request too large for the per-minute token limit: estimated " + inEstimate
                    + " tokens (input" + (outputSharesInput ? " + max_tokens" : "") + ") > " + inputPerMinute
                    + "; the planner should have chunked this unit smaller");
        }
        long outEstimate = outputPerMinute == null ? 0 : Math.min(request.maxTokens(), outputPerMinute);
        Entry inReservation;
        Entry outReservation;
        synchronized (this) {
            while (true) {
                long now = ticker.nowMillis();
                evict(now);
                long wait = waitMillis(now, inEstimate, outEstimate);
                if (wait <= 0) {
                    break;
                }
                // Sleeping inside the lock serialises callers, which is what a per-model limit needs.
                ticker.sleep(wait);
            }
            long now = ticker.nowMillis();
            requests.addLast(new Entry(now, 1));
            inReservation = new Entry(now, inEstimate);
            inputTokens.addLast(inReservation);
            outReservation = new Entry(now, outEstimate);
            if (outputPerMinute != null) {
                outputTokens.addLast(outReservation);
            }
        }
        LlmResponse r = inner.complete(request);
        synchronized (this) {
            // Replace estimates with real usage (same timestamp, so the window position is unchanged).
            long realIn = r.inputTokens() + (outputSharesInput ? r.outputTokens() : 0);
            if (inputTokens.remove(inReservation) && realIn > 0) {
                inputTokens.addLast(new Entry(inReservation.at(), realIn));
            }
            if (outputPerMinute != null && outputTokens.remove(outReservation)) {
                outputTokens.addLast(new Entry(outReservation.at(), r.outputTokens()));
            }
            dayTokens += r.inputTokens() + r.outputTokens();
            dayRequests++;
            warnNearDailyLimit();
        }
        return r;
    }

    private long waitMillis(long now, long inEstimate, long outEstimate) {
        long wait = 0;
        if (rpm != null && requests.size() >= rpm) {
            wait = Math.max(wait, requests.peekFirst().at() + WINDOW_MS - now);
        }
        wait = Math.max(wait, waitForCapacity(inputTokens, inputPerMinute, inEstimate, now));
        if (outputPerMinute != null) {
            wait = Math.max(wait, waitForCapacity(outputTokens, outputPerMinute, outEstimate, now));
        }
        return wait;
    }

    /** How long until enough old entries leave the window for {@code needed} more tokens to fit. */
    private static long waitForCapacity(Deque<Entry> window, int capacity, long needed, long now) {
        long used = window.stream().mapToLong(Entry::amount).sum();
        if (used + needed <= capacity) {
            return 0;
        }
        long freed = 0;
        for (Entry e : window.stream().sorted(java.util.Comparator.comparingLong(Entry::at)).toList()) {
            freed += e.amount();
            if (used - freed + needed <= capacity) {
                return e.at() + WINDOW_MS - now + 1;
            }
        }
        return WINDOW_MS;
    }

    private void evict(long now) {
        for (Deque<Entry> d : java.util.List.of(requests, inputTokens, outputTokens)) {
            d.removeIf(e -> e.at() + WINDOW_MS <= now);
        }
    }

    private void warnNearDailyLimit() {
        if (tokensPerDay != null && dayTokens > 0.9 * tokensPerDay) {
            log.warn("near the daily token limit: {} of {}", dayTokens, tokensPerDay);
        }
        if (requestsPerDay != null && dayRequests > 0.9 * requestsPerDay) {
            log.warn("near the daily request limit: {} of {}", dayRequests, requestsPerDay);
        }
    }
}
