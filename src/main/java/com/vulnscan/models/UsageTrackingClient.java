package com.vulnscan.models;

import com.vulnscan.config.ModelsConfig.Price;

/** Records tokens, cost, retries, refusals and failures of every call into a shared {@link UsageTracker}. */
public class UsageTrackingClient implements LlmClient {

    private final LlmClient inner;
    private final UsageTracker tracker;
    private final String modelName;
    private final Price price;

    public UsageTrackingClient(LlmClient inner, UsageTracker tracker, String modelName, Price price) {
        this.inner = inner;
        this.tracker = tracker;
        this.modelName = modelName;
        this.price = price;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String purpose = request.metadata() == null ? "unknown" : request.metadata().purpose();
        String arm = request.metadata() == null ? null : request.metadata().arm();
        try {
            LlmResponse r = inner.complete(request);
            tracker.record(modelName, purpose, arm, r, price.input(), price.output());
            return r;
        } catch (RuntimeException e) {
            tracker.recordFailure(modelName, purpose, arm);
            throw e;
        }
    }
}
