package com.vulnscan.scan;

import com.vulnscan.planning.ScanUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * Scans all units of one arm run: one virtual thread per unit, at most {@code concurrency} in flight per model
 * (semaphore). Results are sorted by unit id before saving so files are deterministic; findings are deduplicated
 * across units. A unit that fails does not stop the others.
 */
public class ArmScanner {

    private final Deduplicator dedup;

    public ArmScanner(Deduplicator dedup) {
        this.dedup = dedup;
    }

    public record ArmResult(List<ScanResult> results, List<Finding> findings) {
    }

    public ArmResult scanAll(Scanner scanner, List<ScanUnit> units, String guidance, ScanRun run, Consumer<ScanResult> onUnitDone) {
        Semaphore permits = new Semaphore(Math.max(1, scanner.concurrency()));
        List<Future<ScanResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ScanUnit unit : units) {
                futures.add(pool.submit(() -> {
                    permits.acquire();
                    try {
                        ScanResult r = scanner.scan(unit, guidance, run);
                        onUnitDone.accept(r);
                        return r;
                    } finally {
                        permits.release();
                    }
                }));
            }
            List<ScanResult> results = new ArrayList<>();
            for (Future<ScanResult> f : futures) {
                results.add(f.get());
            }
            results.sort(Comparator.comparing(ScanResult::unitId));
            List<Finding> all = new ArrayList<>();
            results.forEach(r -> all.addAll(r.findings()));
            return new ArmResult(results, dedup.dedup(all));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while scanning", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("scan crashed: " + e.getCause().getMessage(), e.getCause());
        }
    }
}
