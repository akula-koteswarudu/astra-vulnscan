package com.vulnscan.matching;

import com.vulnscan.scan.Finding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * prefilter -> judge screen -> judge confirm (plan 6.8). Assignment is done separately ({@link #assign}) so the same
 * decisions serve the verified-only and the all-findings views, strict and lenient.
 * <ul>
 *   <li>Prefilter: pairs below prefilter_min_score are dropped ONLY for items with structured fields (enriched mode);
 *       in README-only mode nothing is dropped and the structured score only orders candidates.</li>
 *   <li>Screen: listwise batches of screen_batch_size findings (deterministic order by finding id).</li>
 *   <li>Confirm: one blind pairwise call per (item, screened candidate).</li>
 * </ul>
 */
public class MatchingPipeline {

    private final StructuredScorer scorer;
    private final Judge judge;
    private final double prefilterMin;
    private final int batchSize;
    private final int concurrency;
    private int screenOrders = 1;

    public MatchingPipeline(StructuredScorer scorer, Judge judge, double prefilterMin, int batchSize) {
        this(scorer, judge, prefilterMin, batchSize, 1);
    }

    /** How many orders the quick look reads the findings in (see AppConfig.Evaluate). */
    public MatchingPipeline withScreenOrders(int n) {
        this.screenOrders = Math.max(1, n);
        return this;
    }

    /** concurrency: confirm calls in flight at once (each pair is still its own, independent request). */
    public MatchingPipeline(StructuredScorer scorer, Judge judge, double prefilterMin, int batchSize, int concurrency) {
        this.scorer = scorer;
        this.judge = judge;
        this.prefilterMin = prefilterMin;
        this.batchSize = batchSize;
        this.concurrency = Math.max(1, concurrency);
    }

    /** All decisions for one (reference, finding set). */
    /** screenExtraPairs: (item, finding) pairs suggested only by the extra quick-look orders (the measured order effect). */
    public record Decisions(Map<String, Map<String, Double>> structured, Map<String, List<String>> screened,
                            List<Judge.Verdict> verdicts, int prefilterDropped, List<String> errors, int screenExtraPairs) {

        public String verdict(String itemId, String findingId) {
            return verdicts.stream().filter(v -> v.itemId().equals(itemId) && v.findingId().equals(findingId))
                    .map(Judge.Verdict::verdict).findFirst().orElse("no");
        }
    }

    public Decisions decide(List<ReferenceItem> items, List<Finding> findings, boolean withEnrichment, String tag) {
        // 1. structured scores and prefilter
        Map<String, Map<String, Double>> structured = new LinkedHashMap<>();
        Set<String> allowed = new HashSet<>();
        int dropped = 0;
        for (ReferenceItem item : items) {
            Map<String, Double> row = new LinkedHashMap<>();
            for (Finding f : findings) {
                StructuredScorer.Score s = scorer.score(item, f);
                row.put(f.id(), s.total());
                boolean canDrop = withEnrichment && item.hasStructuredFields();
                if (canDrop && s.total() < prefilterMin) {
                    dropped++;
                } else {
                    allowed.add(item.id() + "|" + f.id());
                }
            }
            structured.put(item.id(), row);
        }

        // 2. screen (listwise)
        List<String> errors = new ArrayList<>();
        // Order 0: content order (the same findings give the same prompt in every run folder). Orders 1..n-1: fixed
        // shuffles. The quick look only proposes candidates, so suggestions are combined as a union.
        List<Finding> contentOrder = findings.stream().sorted(CONTENT_ORDER).toList();
        Map<String, List<String>> screenedByItem = new LinkedHashMap<>();
        items.forEach(i -> screenedByItem.put(i.id(), new ArrayList<>()));
        java.util.Set<String> fromFirstOrder = new java.util.HashSet<>();
        java.util.Set<String> all = new java.util.HashSet<>();
        for (int order = 0; order < screenOrders; order++) {
            List<Finding> ordered = new ArrayList<>(contentOrder);
            if (order > 0) {
                java.util.Collections.shuffle(ordered, new java.util.Random(order));
            }
            for (int start = 0; start < ordered.size(); start += batchSize) {
                List<Finding> batch = ordered.subList(start, Math.min(ordered.size(), start + batchSize));
                Judge.ScreenResult r = judge.screen(items, batch, withEnrichment, tag);
                errors.addAll(r.errors());
                for (Map.Entry<String, List<String>> e : r.itemsByFinding().entrySet()) {
                    for (String iid : e.getValue()) {
                        String pair = iid + "|" + e.getKey();
                        if (screenedByItem.containsKey(iid) && allowed.contains(pair)) {
                            if (order == 0) {
                                fromFirstOrder.add(pair);
                            }
                            if (all.add(pair)) {
                                screenedByItem.get(iid).add(e.getKey());
                            }
                        }
                    }
                }
            }
        }
        int extraPairs = (int) all.stream().filter(p -> !fromFirstOrder.contains(p)).count();

        // 3. confirm (pairwise), candidates ordered by structured score then id
        Map<String, Finding> byId = new HashMap<>();
        findings.forEach(f -> byId.put(f.id(), f));
        // Pairs in a fixed order (item order, then candidates by structured score and id); verdicts are collected in that
        // same order whatever order the parallel calls finish in, so the saved decisions are deterministic.
        List<java.util.concurrent.Callable<Judge.Verdict>> calls = new ArrayList<>();
        java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(concurrency);
        for (ReferenceItem item : items) {
            boolean place = Judge.placeApplies(item, withEnrichment, scorer.hints(item));
            List<String> candidates = screenedByItem.get(item.id()).stream().distinct()
                    .sorted(Comparator.comparing((String fid) -> -structured.get(item.id()).get(fid)).thenComparing(fid -> fid))
                    .toList();
            screenedByItem.put(item.id(), candidates);
            for (String fid : candidates) {
                calls.add(() -> {
                    permits.acquire();
                    try {
                        return judge.confirm(item, byId.get(fid), withEnrichment, place, tag);
                    } finally {
                        permits.release();
                    }
                });
            }
        }
        List<Judge.Verdict> verdicts = new ArrayList<>();
        try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (java.util.concurrent.Future<Judge.Verdict> f : pool.invokeAll(calls)) {
                Judge.Verdict v = f.get();
                verdicts.add(v);
                if (v.error() != null) {
                    errors.add(v.error());
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while judging", ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            throw new IllegalStateException("judge call crashed: " + ex.getCause().getMessage(), ex.getCause());
        }
        return new Decisions(structured, screenedByItem, verdicts, dropped, errors, extraPairs);
    }

    /** One-to-one assignment over the given findings; lenient also accepts "partial". item id -> finding id. */
    public static Map<String, String> assign(List<ReferenceItem> items, List<Finding> findings, Decisions d, boolean lenient) {
        double[][] w = new double[items.size()][findings.size()];
        for (int i = 0; i < items.size(); i++) {
            for (int j = 0; j < findings.size(); j++) {
                String v = d.verdict(items.get(i).id(), findings.get(j).id());
                boolean ok = v.equals("match") || (lenient && v.equals("partial"));
                w[i][j] = ok ? 1000 + d.structured().get(items.get(i).id()).getOrDefault(findings.get(j).id(), 0.0) : 0;
            }
        }
        int[] a = HungarianAssignment.maximize(w);
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < a.length; i++) {
            if (a[i] >= 0) {
                out.put(items.get(i).id(), findings.get(a[i]).id());
            }
        }
        return out;
    }

    /** Findings ordered by what they say (not by id, which depends on the run): file, symbol, title, evidence, CWE. */
    static final Comparator<Finding> CONTENT_ORDER = Comparator.comparing((Finding f) -> String.valueOf(f.location().file()))
            .thenComparing(f -> String.valueOf(f.location().symbol())).thenComparing(Finding::title)
            .thenComparing(Finding::evidence).thenComparing(f -> String.valueOf(f.cwe()));

    /**
     * Items the judge said a (given) finding matches, but that the one-to-one assignment left without credit because
     * that finding was credited to another item. Shown as "◐" in the per-item table.
     */
    public static List<String> uncredited(List<ReferenceItem> items, List<Finding> findings, Decisions d, Map<String, String> assignment) {
        List<String> out = new ArrayList<>();
        for (ReferenceItem i : items) {
            if (!assignment.containsKey(i.id()) && findings.stream().anyMatch(f -> d.verdict(i.id(), f.id()).equals("match"))) {
                out.add(i.id());
            }
        }
        return out;
    }

    /** Findings that match ANY item (many-to-one allowed; used for precision). */
    public static Set<String> matchedAny(List<ReferenceItem> items, List<Finding> findings, Decisions d, boolean lenient) {
        Set<String> out = new HashSet<>();
        for (Finding f : findings) {
            for (ReferenceItem i : items) {
                String v = d.verdict(i.id(), f.id());
                if (v.equals("match") || (lenient && v.equals("partial"))) {
                    out.add(f.id());
                }
            }
        }
        return out;
    }
}
