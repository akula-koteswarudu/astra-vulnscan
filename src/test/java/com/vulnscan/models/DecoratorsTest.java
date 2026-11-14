package com.vulnscan.models;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vulnscan.common.TokenEstimator;
import com.vulnscan.config.ModelsConfig.Price;
import com.vulnscan.testing.FakeLlmClient;
import com.vulnscan.testing.FakeTicker;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DecoratorsTest {

    @TempDir
    Path dir;

    static LlmRequest request(String text, int repeat) {
        return new LlmRequest("sys", List.of(LlmRequest.Message.user(text)), null, 100, null, Map.of(),
                new LlmRequest.Metadata("scan", "student_baseline", "run1", "u1", repeat));
    }

    @Test
    void retriesThenSucceedsAndCountsRetries() {
        AtomicInteger n = new AtomicInteger();
        FakeLlmClient inner = new FakeLlmClient(r -> {
            if (n.incrementAndGet() < 3) {
                throw new LlmException("429", true, 429, null, null);
            }
            return FakeLlmClient.ok("ok");
        });
        FakeTicker ticker = new FakeTicker();
        LlmResponse r = new RetryingClient(inner, 4, 2, ticker, new Random(1)).complete(request("x", 0));
        assertThat(r.retries()).isEqualTo(2);
        assertThat(ticker.sleeps).hasSize(2);
        assertThat(ticker.sleeps.get(1)).isGreaterThan(ticker.sleeps.get(0) / 3); // grows roughly exponentially
    }

    @Test
    void retryAfterIsHonoured() {
        AtomicInteger n = new AtomicInteger();
        FakeLlmClient inner = new FakeLlmClient(r -> {
            if (n.incrementAndGet() == 1) {
                throw new LlmException("429", true, 429, 30.0, null);
            }
            return FakeLlmClient.ok("ok");
        });
        FakeTicker ticker = new FakeTicker();
        new RetryingClient(inner, 4, 1, ticker, new Random(1)).complete(request("x", 0));
        assertThat(ticker.sleeps.get(0)).isGreaterThanOrEqualTo(30_000);
    }

    @Test
    void nonRetryableRaisesImmediately() {
        FakeLlmClient inner = new FakeLlmClient(r -> {
            throw new LlmException("400 bad", false, 400, null, null);
        });
        FakeTicker ticker = new FakeTicker();
        assertThatThrownBy(() -> new RetryingClient(inner, 4, 1, ticker, new Random(1)).complete(request("x", 0)))
                .hasMessageContaining("400");
        assertThat(inner.calls()).isEqualTo(1);
        assertThat(ticker.sleeps).isEmpty();
    }

    @Test
    void givesUpAfterMaxAttempts() {
        FakeLlmClient inner = new FakeLlmClient(r -> {
            throw new LlmException("503", true, 503, null, null);
        });
        assertThatThrownBy(() -> new RetryingClient(inner, 3, 1, new FakeTicker(), new Random(1)).complete(request("x", 0)))
                .isInstanceOf(LlmException.class);
        assertThat(inner.calls()).isEqualTo(3);
    }

    @Test
    void cacheHitSkipsInnerAndReportsCachedNotBilled() {
        FakeLlmClient inner = FakeLlmClient.constant("answer");
        CachingClient cache = new CachingClient(inner, dir, "p", "m");
        LlmResponse first = cache.complete(request("x", 0));
        LlmResponse second = cache.complete(request("x", 0));
        assertThat(inner.calls()).isEqualTo(1);
        assertThat(first.cached()).isFalse();
        assertThat(first.billed()).isTrue();
        assertThat(second.cached()).isTrue();
        assertThat(second.billed()).isFalse();
        assertThat(second.text()).isEqualTo("answer");
        assertThat(second.inputTokens()).isEqualTo(first.inputTokens()); // original usage kept
    }

    @Test
    void repeatIndexChangesTheKeyButMetadataDoesNot() {
        CachingClient cache = new CachingClient(FakeLlmClient.constant("a"), dir, "p", "m");
        assertThat(cache.key(request("x", 0))).isNotEqualTo(cache.key(request("x", 1)));
        LlmRequest otherArm = request("x", 0).withMetadata(new LlmRequest.Metadata("scan", "other_arm", "r2", "u9", 0));
        assertThat(cache.key(request("x", 0))).isEqualTo(cache.key(otherArm));
        assertThat(new CachingClient(FakeLlmClient.constant("a"), dir, "p", "other-model").key(request("x", 0)))
                .isNotEqualTo(cache.key(request("x", 0)));
    }

    @Test
    void refusalsAreNotCached() {
        FakeLlmClient inner = new FakeLlmClient(r -> FakeLlmClient.withStop("", LlmResponse.StopReason.REFUSAL));
        CachingClient cache = new CachingClient(inner, dir, "p", "m");
        cache.complete(request("x", 0));
        cache.complete(request("x", 0));
        assertThat(inner.calls()).isEqualTo(2);
    }

    @Test
    void usageSeparatesBilledAndCached() {
        UsageTracker tracker = new UsageTracker();
        LlmClient stack = new CachingClient(new UsageTrackingClient(FakeLlmClient.constant("a"), tracker, "teacher",
                new Price(2.0, 10.0)), dir, "p", "m");
        // Usage sits inside the cache in production; to see a cached line, track outside too.
        LlmClient outer = new UsageTrackingClient(stack, tracker, "teacher-outer", new Price(2.0, 10.0));
        outer.complete(request("x", 0));
        outer.complete(request("x", 0));
        UsageTracker.Line inner = tracker.snapshot().stream().filter(l -> l.model.equals("teacher")).findFirst().orElseThrow();
        UsageTracker.Line seen = tracker.snapshot().stream().filter(l -> l.model.equals("teacher-outer")).findFirst().orElseThrow();
        assertThat(inner.billedCalls).isEqualTo(1);
        assertThat(inner.cost).isEqualTo((100 * 2.0 + 50 * 10.0) / 1e6);
        assertThat(seen.billedCalls).isEqualTo(1);
        assertThat(seen.cachedCalls).isEqualTo(1);
        assertThat(seen.cachedCost).isEqualTo(seen.cost);
        assertThat(inner.arm).isEqualTo("student_baseline");
    }

    @Test
    void usageCountsFailures() {
        UsageTracker tracker = new UsageTracker();
        LlmClient c = new UsageTrackingClient(new FakeLlmClient(r -> {
            throw new LlmException("x", false, 400, null, null);
        }), tracker, "m", new Price(0, 0));
        assertThatThrownBy(() -> c.complete(request("x", 0))).isInstanceOf(LlmException.class);
        assertThat(tracker.snapshot().get(0).failures).isEqualTo(1);
    }

    @Test
    void limiterDelaysWhenTokenWindowIsFull() {
        FakeTicker ticker = new FakeTicker();
        // 4000 chars -> 1000 tokens + 100 max_tokens = 1100 per call; capacity 2000/min -> second call must wait.
        RateLimitedClient limited = new RateLimitedClient(new FakeLlmClient(r ->
                new LlmResponse("ok", 1000, 100, LlmResponse.StopReason.END, null, "m", 1, false, true, 0)),
                null, null, null, 2000, null, null, new TokenEstimator(4, 0.0), ticker);
        String text = "x".repeat(4000 - 3);
        limited.complete(request(text, 0));
        assertThat(ticker.sleeps).isEmpty();
        limited.complete(request(text, 0));
        assertThat(ticker.sleeps).hasSize(1);
        assertThat(ticker.sleeps.get(0)).isBetween(59_000L, 60_001L);
    }

    @Test
    void limiterRespectsRequestsPerMinute() {
        FakeTicker ticker = new FakeTicker();
        RateLimitedClient limited = new RateLimitedClient(FakeLlmClient.constant("ok"), 2, 1_000_000, null, null, null, null,
                new TokenEstimator(4, 0.0), ticker);
        limited.complete(request("a", 0));
        limited.complete(request("a", 0));
        assertThat(ticker.sleeps).isEmpty();
        limited.complete(request("a", 0));
        assertThat(ticker.sleeps).hasSize(1);
    }

    @Test
    void limiterFailsFastOnOversizeRequest() {
        FakeLlmClient inner = FakeLlmClient.constant("ok");
        RateLimitedClient limited = new RateLimitedClient(inner, null, null, null, 1000, null, null, new TokenEstimator(4, 0.15), new FakeTicker());
        assertThatThrownBy(() -> limited.complete(request("x".repeat(4000), 0)))
                .isInstanceOf(LlmException.class).hasMessageContaining("too large");
        assertThat(inner.calls()).isZero();
    }

    @Test
    void inputAndTotalLimitsAreBothEnforced() {
        FakeTicker ticker = new FakeTicker();
        // itpm 1500: two 1000-token prompts cannot share a minute even though tpm (10000) has room.
        RateLimitedClient limited = new RateLimitedClient(new FakeLlmClient(r ->
                new LlmResponse("ok", 1000, 10, LlmResponse.StopReason.END, null, "m", 1, false, true, 0)),
                null, 1500, null, 10_000, null, null, new TokenEstimator(4, 0.0), ticker);
        String text = "x".repeat(4000 - 3);
        limited.complete(request(text, 0));
        limited.complete(request(text, 0));
        assertThat(ticker.sleeps).hasSize(1);
        assertThatThrownBy(() -> limited.complete(request("x".repeat(8000), 0))).hasMessageContaining("(input)");
    }
}
