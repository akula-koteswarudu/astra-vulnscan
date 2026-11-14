package com.vulnscan.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Thread-safe usage totals per (model role, purpose, arm). Billed and cached calls are counted separately:
 * cost counts billed calls only; cachedCost is what cache hits originally cost.
 */
public class UsageTracker {

    private final Map<String, Line> lines = new TreeMap<>();

    public synchronized void record(String model, String purpose, String arm, LlmResponse r, double inputPrice, double outputPrice) {
        String key = model + "|" + purpose + "|" + (arm == null ? "-" : arm);
        Line l = lines.computeIfAbsent(key, k -> new Line(model, purpose, arm == null ? "-" : arm));
        double cost = (r.inputTokens() * inputPrice + r.outputTokens() * outputPrice) / 1_000_000.0;
        if (r.cached()) {
            l.cachedCalls++;
            l.cachedInputTokens += r.inputTokens();
            l.cachedOutputTokens += r.outputTokens();
            l.cachedCost += cost;
        } else {
            l.billedCalls++;
            l.inputTokens += r.inputTokens();
            l.outputTokens += r.outputTokens();
            l.cost += cost;
        }
        l.retries += r.retries();
        if (r.stopReason() == LlmResponse.StopReason.REFUSAL) {
            l.refusals++;
        }
    }

    public synchronized void recordFailure(String model, String purpose, String arm) {
        String key = model + "|" + purpose + "|" + (arm == null ? "-" : arm);
        lines.computeIfAbsent(key, k -> new Line(model, purpose, arm == null ? "-" : arm)).failures++;
    }

    public synchronized List<Line> snapshot() {
        List<Line> out = new ArrayList<>();
        lines.values().forEach(l -> out.add(l.copy()));
        return out;
    }

    public synchronized double billedCost(String model) {
        return lines.values().stream().filter(l -> l.model.equals(model)).mapToDouble(l -> l.cost).sum();
    }

    /** One usage row. Public fields keep the JSON output simple; instances handed out are copies. */
    public static final class Line {
        public String model;
        public String purpose;
        public String arm;
        public int billedCalls;
        public int cachedCalls;
        public long inputTokens;
        public long outputTokens;
        public long cachedInputTokens;
        public long cachedOutputTokens;
        public double cost;
        public double cachedCost;
        public int retries;
        public int refusals;
        public int failures;

        public Line() {
        }

        Line(String model, String purpose, String arm) {
            this.model = model;
            this.purpose = purpose;
            this.arm = arm;
        }

        Line copy() {
            Line c = new Line(model, purpose, arm);
            c.billedCalls = billedCalls;
            c.cachedCalls = cachedCalls;
            c.inputTokens = inputTokens;
            c.outputTokens = outputTokens;
            c.cachedInputTokens = cachedInputTokens;
            c.cachedOutputTokens = cachedOutputTokens;
            c.cost = cost;
            c.cachedCost = cachedCost;
            c.retries = retries;
            c.refusals = refusals;
            c.failures = failures;
            return c;
        }
    }
}
