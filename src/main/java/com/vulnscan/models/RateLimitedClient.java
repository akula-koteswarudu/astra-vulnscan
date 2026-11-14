package com.vulnscan.models;

import com.vulnscan.common.Ticker;
import com.vulnscan.common.TokenEstimator;
import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side sliding-window limiter (60 s) for requests/min and three token windows, each used when its limit is set:
 * input tokens (itpm), output tokens (otpm) and total tokens (tpm, input + max_tokens reserved up front, as Groq
 * counts it). Estimates are reconciled with the real usage after the call. A daily counter warns near daily limits.
 * A single request that can never fit a per-minute capacity fails fast instead of waiting forever.
 */
public class RateLimitedClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(RateLimitedClient.class);
    private static final long WINDOW_MS = 60_000;

    private final LlmClient inner;
    private final Integer rpm;
    private final Integer inputPerMinute;
    private final Integer outputPerMinute;
    private final Integer totalPerMinute;
    private final Integer tokensPerDay;
    private final Integer requestsPerDay;
    private final TokenEstimator estimator;
    private final Ticker ticker;

    private final Deque<Entry> requests = new ArrayDeque<>();
    private final Deque<Entry> inputTokens = new ArrayDeque<>();
    private final Deque<Entry> outputTokens = new ArrayDeque<>();
    private final Deque<Entry> totalTokens = new ArrayDeque<>();
    private long dayTokens;
    private long dayRequests;

    private record Entry(long at, long amount) {
    }

    public RateLimitedClient(LlmClient inner, Integer rpm, Integer inputPerMinute, Integer outputPerMinute, Integer totalPerMinute,
                             Integer tokensPerDay, Integer requestsPerDay, TokenEstimator estimator, Ticker ticker) {
        this.inner = inner;
        this.rpm = rpm;
        this.inputPerMinute = inputPerMinute;
        this.outputPerMinute = outputPerMinute;
        this.totalPerMinute = totalPerMinute;
        this.tokensPerDay = tokensPerDay;
        this.requestsPerDay = requestsPerDay;
        this.estimator = estimator;
        this.ticker = ticker;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long inEstimate = estimator.estimate(request.promptChars());
        long totalEstimate = inEstimate + request.maxTokens();
        if (inputPerMinute != null && inEstimate > inputPerMinute) {
            throw tooLarge("input", inEstimate, inputPerMinute);
        }
        if (totalPerMinute != null && totalEstimate > totalPerMinute) {
            throw tooLarge("input + max_tokens", totalEstimate, totalPerMinute);
        }
        if (outputPerMinute != null && request.maxTokens() > outputPerMinute) {
            // Providers check max_tokens against the output limit up front (Groq: "expected output tokens").
            throw tooLarge("max_tokens", request.maxTokens(), outputPerMinute);
        }
        long outEstimate = outputPerMinute == null ? 0 : request.maxTokens();
        Entry in;
        Entry out;
        Entry total;
        synchronized (this) {
            while (true) {
                long now = ticker.nowMillis();
                evict(now);
                long wait = waitMillis(now, inEstimate, outEstimate, totalEstimate);
                if (wait <= 0) {
                    break;
                }
                // Sleeping inside the lock serialises callers, which is what a per-model limit needs.
                ticker.sleep(wait);
            }
            long now = ticker.nowMillis();
            requests.addLast(new Entry(now, 1));
            in = add(inputTokens, inputPerMinute, now, inEstimate);
            out = add(outputTokens, outputPerMinute, now, outEstimate);
            total = add(totalTokens, totalPerMinute, now, totalEstimate);
        }
        LlmResponse r = inner.complete(request);
        synchronized (this) {
            // Replace estimates with real usage (same timestamp, so the window position is unchanged).
            reconcile(inputTokens, in, r.inputTokens());
            reconcile(outputTokens, out, r.outputTokens());
            reconcile(totalTokens, total, r.inputTokens() + r.outputTokens());
            dayTokens += r.inputTokens() + r.outputTokens();
            dayRequests++;
            warnNearDailyLimit();
        }
        return r;
    }

    private static LlmException tooLarge(String what, long estimate, int limit) {
        return LlmException.nonRetryable("request too large for the per-minute token limit: estimated " + estimate
                + " tokens (" + what + ") > " + limit + "; the planner should have chunked this unit smaller");
    }

    private static Entry add(Deque<Entry> window, Integer limit, long now, long amount) {
        if (limit == null) {
            return null;
        }
        Entry e = new Entry(now, amount);
        window.addLast(e);
        return e;
    }

    private static void reconcile(Deque<Entry> window, Entry reservation, long real) {
        if (reservation != null && window.remove(reservation) && real > 0) {
            window.addLast(new Entry(reservation.at(), real));
        }
    }

    private long waitMillis(long now, long inEstimate, long outEstimate, long totalEstimate) {
        long wait = 0;
        if (rpm != null && requests.size() >= rpm) {
            wait = Math.max(wait, requests.peekFirst().at() + WINDOW_MS - now);
        }
        if (inputPerMinute != null) {
            wait = Math.max(wait, waitForCapacity(inputTokens, inputPerMinute, inEstimate, now));
        }
        if (outputPerMinute != null) {
            wait = Math.max(wait, waitForCapacity(outputTokens, outputPerMinute, outEstimate, now));
        }
        if (totalPerMinute != null) {
            wait = Math.max(wait, waitForCapacity(totalTokens, totalPerMinute, totalEstimate, now));
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
        for (Deque<Entry> d : java.util.List.of(requests, inputTokens, outputTokens, totalTokens)) {
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
