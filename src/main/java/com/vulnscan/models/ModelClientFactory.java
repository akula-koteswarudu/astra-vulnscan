package com.vulnscan.models;

import com.vulnscan.common.Ticker;
import com.vulnscan.common.TokenEstimator;
import com.vulnscan.config.ModelsConfig;
import com.vulnscan.config.ModelsConfig.ModelSpec;
import com.vulnscan.config.Secrets;
import java.nio.file.Path;
import java.util.Random;

/**
 * Builds the decorated client for a model: Caching(UsageTracking(Retrying(RateLimited(Adapter)))).
 * Order matters: a cache hit skips everything (no quota, no retry); usage sees retries and the final result;
 * the limiter sits right next to the provider so every real attempt (including retries) is paced.
 */
public class ModelClientFactory {

    private final ModelsConfig models;
    private final ProviderRegistry registry;
    private final Secrets secrets;
    private final UsageTracker usage;
    private final TokenEstimator estimator;
    private final Path cacheDir;
    private final boolean cacheEnabled;
    private final Ticker ticker;

    public ModelClientFactory(ModelsConfig models, ProviderRegistry registry, Secrets secrets, UsageTracker usage,
                              TokenEstimator estimator, Path cacheDir, boolean cacheEnabled, Ticker ticker) {
        this.models = models;
        this.registry = registry;
        this.secrets = secrets;
        this.usage = usage;
        this.estimator = estimator;
        this.cacheDir = cacheDir;
        this.cacheEnabled = cacheEnabled;
        this.ticker = ticker;
    }

    public LlmClient forModel(String name) {
        ModelSpec spec = models.spec(name);
        return decorate(registry.create(spec, secrets.require(spec.apiKeyEnv())), name, spec);
    }

    /** Also used by tests to wrap a fake adapter in the real decorator stack. */
    public LlmClient decorate(LlmClient adapter, String name, ModelSpec spec) {
        ModelsConfig.RateLimit rl = spec.rateLimit();
        LlmClient c = new RateLimitedClient(adapter, rl.rpm(), rl.perMinuteInputLimit(), rl.outputSharesInputLimit(), rl.otpm(),
                rl.tpd(), rl.rpd(), estimator, ticker);
        c = new RetryingClient(c, spec.retry().maxAttempts(), spec.retry().baseDelayS(), ticker, new Random());
        c = new UsageTrackingClient(c, usage, name, spec.pricePerMtok());
        if (cacheEnabled) {
            c = new CachingClient(c, cacheDir.resolve("llm"), spec.provider(), spec.model());
        }
        return c;
    }

    public ModelSpec spec(String name) {
        return models.spec(name);
    }
}
