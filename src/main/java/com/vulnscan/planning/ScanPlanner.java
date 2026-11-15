package com.vulnscan.planning;

import com.vulnscan.common.Hashing;
import com.vulnscan.common.TokenEstimator;
import com.vulnscan.config.AppConfig;
import com.vulnscan.config.ModelsConfig.ModelSpec;
import com.vulnscan.config.ModelsConfig.RateLimit;
import com.vulnscan.ingest.AppModel;
import com.vulnscan.ingest.AppModel.Component;
import com.vulnscan.ingest.AppModel.SourceFile;
import com.vulnscan.ingest.CodeBase;
import com.vulnscan.ingest.CodeView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Plans scan units per model (plan 6.4).
 * <ul>
 *   <li>Budget: see {@link UnitPlan.Budget}; below planning.min_code_budget_tokens the run stops before any scan.</li>
 *   <li>auto: one "whole" unit when all first-party code + context fits, else chunks. whole/chunked can be forced.</li>
 *   <li>Chunks: one unit per entry point (exported component or deep-link target; every component if none is exported)
 *       plus its one-hop neighbours while they fit (files may appear in several units); files no entry point reaches are
 *       grouped by package; a file larger than the budget is skipped and reported.</li>
 *   <li>In chunked mode the app-level context (full manifest, resources, dependency inventory) is scanned once in its
 *       own "app_context" unit; code units carry only their manifest slice. This keeps the small per-minute budget
 *       of the student for code instead of repeating the same context in every unit.</li>
 * </ul>
 */
public class ScanPlanner {

    /** Share of the per-minute input limit one request may use (a single request above the limit is rejected). */
    static final double PER_MINUTE_SHARE = 0.8;
    /** Fixed text around each file (FILE markers + path). */
    static final int FILE_MARKER_CHARS = 60;

    private final AppConfig.Planning config;
    private final TokenEstimator estimator;
    private final Prioritizer prioritizer;
    private final DependencyGraphBuilder graphBuilder;
    private final UnitContextBuilder context;
    private final int systemPromptTokens;

    public ScanPlanner(AppConfig.Planning config, TokenEstimator estimator, Prioritizer prioritizer,
                       DependencyGraphBuilder graphBuilder, UnitContextBuilder context, int systemPromptTokens) {
        this.config = config;
        this.estimator = estimator;
        this.prioritizer = prioritizer;
        this.graphBuilder = graphBuilder;
        this.context = context;
        this.systemPromptTokens = systemPromptTokens;
    }

    public UnitPlan.Budget budget(String modelName, ModelSpec spec, boolean reserveGuidance) {
        if (systemPromptTokens > config.promptOverheadTokens()) {
            throw new PlanningException("planning.prompt_overhead_tokens (" + config.promptOverheadTokens()
                    + ") is smaller than the scan system prompt (~" + systemPromptTokens + " tokens); raise it");
        }
        RateLimit rl = spec.rateLimit();
        int base = spec.planningBudgetTokens() != null ? spec.planningBudgetTokens() : spec.contextTokens();
        int cap = base;
        if (rl.itpm() != null) {
            cap = Math.min(cap, (int) (PER_MINUTE_SHARE * rl.itpm()));
        }
        if (rl.tpm() != null) {
            cap = Math.min(cap, (int) (PER_MINUTE_SHARE * rl.tpm()) - spec.maxOutputTokens());
        }
        int reserved = reserveGuidance ? config.reservedGuidanceTokens() : 0;
        int effective = cap - config.promptOverheadTokens() - reserved;
        UnitPlan.Budget b = new UnitPlan.Budget(base, rl.itpm(), rl.tpm(), spec.maxOutputTokens(), cap,
                config.promptOverheadTokens(), reserved, effective);
        if (effective < config.minCodeBudgetTokens()) {
            throw new PlanningException("model '" + modelName + "' (" + spec.model() + ") leaves only " + effective
                    + " tokens per request for code (minimum planning.min_code_budget_tokens = " + config.minCodeBudgetTokens()
                    + "): per-request cap = min(" + base
                    + (rl.itpm() != null ? ", " + PER_MINUTE_SHARE + " x itpm " + rl.itpm() : "")
                    + (rl.tpm() != null ? ", " + PER_MINUTE_SHARE + " x tpm " + rl.tpm() + " - max_output_tokens " + spec.maxOutputTokens()
                            + " (output counts against tpm)" : "")
                    + ") = " + cap + ", minus prompt overhead " + config.promptOverheadTokens()
                    + (reserved > 0 ? " and reserved guidance " + reserved : "")
                    + ". Lower max_output_tokens / reserved_guidance_tokens, or use a model or tier with a higher limit.");
        }
        return b;
    }

    public UnitPlan plan(String modelName, ModelSpec spec, boolean reserveGuidance, AppModel app, CodeBase code) {
        return plan(modelName, spec, reserveGuidance, app, code, config.mode());
    }

    /** The teacher's and the student's plans, in that order. */
    public record PlanPair(UnitPlan teacher, UnitPlan student) {
    }

    /**
     * Plans both scanning models. With planning.shared_units (default) both get the units planned for the model with
     * the smaller per-request budget, so "found by the teacher, missed by the student" always compares the same code
     * seen together; each plan keeps its own model name and records the budget the units were planned with.
     * Without it, each model is planned on its own budget (the larger model may then see more neighbours per unit).
     */
    public PlanPair planPair(String teacher, ModelSpec teacherSpec, String student, ModelSpec studentSpec, AppModel app, CodeBase code) {
        if (!config.sharedUnits()) {
            return new PlanPair(plan(teacher, teacherSpec, false, app, code), plan(student, studentSpec, true, app, code));
        }
        boolean studentIsSmaller = budget(student, studentSpec, true).effective() <= budget(teacher, teacherSpec, false).effective();
        UnitPlan shared = studentIsSmaller ? plan(student, studentSpec, true, app, code) : plan(teacher, teacherSpec, false, app, code);
        UnitPlan teacherPlan = studentIsSmaller ? renamed(shared, teacher) : shared;
        UnitPlan studentPlan = studentIsSmaller ? shared : renamed(shared, student);
        return new PlanPair(teacherPlan, studentPlan);
    }

    private static UnitPlan renamed(UnitPlan p, String model) {
        return new UnitPlan(p.schemaVersion(), model, p.mode(), p.budget(), p.units(), p.skipped());
    }

    /**
     * Re-plans a unit whose answer was truncated even with a doubled output budget (plan 6.5): a whole-app unit
     * becomes graph chunks; a multi-file chunk becomes one unit per file; an app-context unit is split into its parts.
     * Returns an empty list when the unit cannot be split further.
     */
    public List<ScanUnit> split(ScanUnit unit, String modelName, ModelSpec spec, boolean reserveGuidance, AppModel app, CodeBase code) {
        switch (unit.kind()) {
            case "whole" -> {
                return plan(modelName, spec, reserveGuidance, app, code, AppConfig.PlanningMode.CHUNKED).units();
            }
            case "app_context" -> {
                if (unit.resources() == null && unit.dependencies() == null && unit.manifestSlice() != null) {
                    return splitManifest(unit, app);
                }
                List<ScanUnit> parts = new ArrayList<>();
                if (unit.manifestSlice() != null && (unit.resources() != null || unit.dependencies() != null)) {
                    parts.add(unit("app_context", unit.root() + "/manifest", List.of(), unit.manifestSlice(), null, null,
                            estimator.estimate(unit.manifestSlice()), unit.priority(), Map.of()));
                }
                if (unit.resources() != null && (unit.manifestSlice() != null || unit.dependencies() != null)) {
                    parts.add(unit("app_context", unit.root() + "/resources", List.of(), null, unit.resources(), null,
                            estimator.estimate(unit.resources()), unit.priority(), Map.of()));
                }
                if (unit.dependencies() != null && (unit.manifestSlice() != null || unit.resources() != null)) {
                    parts.add(unit("app_context", unit.root() + "/dependencies", List.of(), null, null, unit.dependencies(),
                            estimator.estimate(unit.dependencies()), unit.priority(), Map.of()));
                }
                return parts;
            }
            default -> {
                if (unit.files().size() < 2) {
                    return List.of();
                }
                Map<String, SourceFile> byPath = new HashMap<>();
                app.firstPartySources().forEach(f -> byPath.put(f.path(), f));
                Map<String, Integer> tokens = new HashMap<>();
                Map<String, Integer> prio = new HashMap<>();
                for (String path : unit.files()) {
                    tokens.put(path, estimator.estimate(CodeView.render(code.text(path)).length() + FILE_MARKER_CHARS + path.length()));
                    prio.put(path, prioritizer.score(byPath.get(path), code.text(path), app.manifest()));
                }
                return unit.files().stream()
                        .map(path -> codeUnit(app, unit.kind(), unit.root() + "/" + path.substring(path.lastIndexOf('/') + 1),
                                List.of(path), tokens, prio, byPath))
                        .toList();
            }
        }
    }

    /** A manifest-only unit: two halves of the components (each with the app flags and permissions). */
    private List<ScanUnit> splitManifest(ScanUnit unit, AppModel app) {
        List<String> names = app.manifest().components().stream()
                .filter(c -> unit.manifestSlice().contains(c.rawXml())).map(Component::name).toList();
        if (names.size() < 2) {
            return List.of();
        }
        int half = names.size() / 2;
        List<ScanUnit> out = new ArrayList<>();
        for (List<String> part : List.of(names.subList(0, half), names.subList(half, names.size()))) {
            String slice = context.manifestSlice(app, part);
            out.add(unit("app_context", unit.root() + "/" + (out.isEmpty() ? "a" : "b"), List.of(), slice, null, null,
                    estimator.estimate(slice), unit.priority(), Map.of()));
        }
        return out;
    }

    UnitPlan plan(String modelName, ModelSpec spec, boolean reserveGuidance, AppModel app, CodeBase code,
                  AppConfig.PlanningMode mode) {
        UnitPlan.Budget budget = budget(modelName, spec, reserveGuidance);
        int limit = budget.effective();
        List<SourceFile> files = app.firstPartySources();
        Map<String, SourceFile> byPath = new HashMap<>();
        Map<String, Integer> tokens = new HashMap<>();
        Map<String, Integer> priority = new HashMap<>();
        for (SourceFile f : files) {
            String text = code.text(f.path());
            byPath.put(f.path(), f);
            tokens.put(f.path(), estimator.estimate(CodeView.render(text).length() + FILE_MARKER_CHARS + f.path().length()));
            priority.put(f.path(), prioritizer.score(f, text, app.manifest()));
        }

        String fullManifest = context.fullManifestSlice(app);
        String resources = context.resources(app);
        String deps = context.dependencies(app);
        int contextTokens = estimator.estimate(fullManifest) + estimator.estimate(resources) + estimator.estimate(deps);
        int wholeTokens = tokens.values().stream().mapToInt(Integer::intValue).sum() + contextTokens;

        boolean whole = switch (mode) {
            case WHOLE -> {
                if (wholeTokens > limit) {
                    throw new PlanningException("planning.mode whole: the app needs ~" + wholeTokens + " tokens but model '"
                            + modelName + "' allows " + limit + " per request; use auto or chunked");
                }
                yield true;
            }
            case CHUNKED -> false;
            case AUTO -> wholeTokens <= limit;
        };

        List<ScanUnit> units = new ArrayList<>();
        List<UnitPlan.Skipped> skipped = new ArrayList<>();
        if (whole) {
            List<String> paths = files.stream().map(SourceFile::path).sorted().toList();
            int prio = paths.stream().mapToInt(priority::get).sum();
            units.add(unit("whole", "app", paths, fullManifest, resources, deps, wholeTokens, prio, byPath));
        } else {
            units.addAll(appContextUnits(fullManifest, resources, deps, limit, skipped));
            units.addAll(codeUnits(app, files, code, tokens, priority, byPath, limit, skipped));
        }

        units.sort(Comparator.comparingInt(ScanUnit::priority).reversed().thenComparing(ScanUnit::root));
        if (config.maxUnits() != null && units.size() > config.maxUnits()) {
            for (ScanUnit u : units.subList(config.maxUnits(), units.size())) {
                skipped.add(new UnitPlan.Skipped("unit " + u.root(), u.estTokens(), "planning.max_units = " + config.maxUnits()));
            }
            units = new ArrayList<>(units.subList(0, config.maxUnits()));
        }
        return new UnitPlan(1, modelName, whole ? "whole" : "chunked", budget, units, skipped);
    }

    private List<ScanUnit> appContextUnits(String manifest, String resources, String deps, int limit, List<UnitPlan.Skipped> skipped) {
        // Pack the three context parts greedily into as few units as fit.
        List<ScanUnit> out = new ArrayList<>();
        String[] names = {"manifest", "resources", "dependencies"};
        String[] parts = {manifest, resources, deps};
        String[] current = new String[3];
        int used = 0;
        int index = 0;
        for (int i = 0; i < 3; i++) {
            if (parts[i] == null) {
                continue;
            }
            int t = estimator.estimate(parts[i]);
            if (t > limit) {
                skipped.add(new UnitPlan.Skipped("app context: " + names[i], t, "larger than the per-request budget " + limit));
                continue;
            }
            if (used + t > limit) {
                out.add(unit("app_context", "app-context-" + (++index), List.of(), current[0], current[1], current[2], used, 100, Map.of()));
                current = new String[3];
                used = 0;
            }
            current[i] = parts[i];
            used += t;
        }
        if (used > 0) {
            out.add(unit("app_context", index == 0 ? "app-context" : "app-context-" + (++index), List.of(), current[0], current[1],
                    current[2], used, 100, Map.of()));
        }
        return out;
    }

    private List<ScanUnit> codeUnits(AppModel app, List<SourceFile> files, CodeBase code, Map<String, Integer> tokens,
                                     Map<String, Integer> priority, Map<String, SourceFile> byPath, int limit,
                                     List<UnitPlan.Skipped> skipped) {
        Map<String, Component> components = new HashMap<>();
        app.manifest().components().forEach(c -> components.put(c.name(), c));
        Map<String, Set<String>> out = graphBuilder.build(files, code);
        Map<String, Set<String>> in = new TreeMap<>();
        out.forEach((from, tos) -> tos.forEach(to -> in.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(from)));

        List<SourceFile> roots = files.stream().filter(f -> f.component() != null && isEntryPoint(components.get(f.component()))).toList();
        if (roots.isEmpty()) {
            roots = files.stream().filter(f -> f.component() != null).toList();
        }
        roots = roots.stream().sorted(Comparator.comparing((SourceFile f) -> -priority.get(f.path())).thenComparing(SourceFile::path)).toList();

        List<ScanUnit> units = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();
        Set<String> oversized = new LinkedHashSet<>();
        for (SourceFile f : files) {
            if (tokens.get(f.path()) + sliceTokens(app, List.of(f.path()), byPath) > limit) {
                oversized.add(f.path());
                skipped.add(new UnitPlan.Skipped("file " + f.path(), tokens.get(f.path()), "single file larger than the per-request budget " + limit));
            }
        }
        for (SourceFile root : roots) {
            if (oversized.contains(root.path())) {
                continue;
            }
            List<String> member = new ArrayList<>(List.of(root.path()));
            List<String> neighbours = new ArrayList<>();
            out.getOrDefault(root.path(), Set.of()).stream().sorted(byPriority(priority)).forEach(neighbours::add);
            in.getOrDefault(root.path(), Set.of()).stream().filter(p -> !neighbours.contains(p)).sorted(byPriority(priority)).forEach(neighbours::add);
            for (String n : neighbours) {
                if (oversized.contains(n)) {
                    continue;
                }
                List<String> candidate = new ArrayList<>(member);
                candidate.add(n);
                if (codeTokens(candidate, tokens) + sliceTokens(app, candidate, byPath) <= limit) {
                    member = candidate;
                }
            }
            covered.addAll(member);
            units.add(codeUnit(app, "component", root.className(), member, tokens, priority, byPath));
        }

        // Files no entry point reached: grouped by package, packed in priority order.
        Map<String, List<String>> byPackage = new TreeMap<>();
        for (SourceFile f : files) {
            if (!covered.contains(f.path()) && !oversized.contains(f.path())) {
                String cls = f.className();
                byPackage.computeIfAbsent(cls.contains(".") ? cls.substring(0, cls.lastIndexOf('.')) : "", k -> new ArrayList<>()).add(f.path());
            }
        }
        for (Map.Entry<String, List<String>> e : byPackage.entrySet()) {
            List<String> pending = e.getValue().stream().sorted(byPriority(priority)).toList();
            List<String> current = new ArrayList<>();
            int part = 0;
            for (String p : pending) {
                List<String> candidate = new ArrayList<>(current);
                candidate.add(p);
                if (!current.isEmpty() && codeTokens(candidate, tokens) + sliceTokens(app, candidate, byPath) > limit) {
                    units.add(codeUnit(app, "package", e.getKey() + "#" + (++part), current, tokens, priority, byPath));
                    current = new ArrayList<>(List.of(p));
                } else {
                    current = candidate;
                }
            }
            if (!current.isEmpty()) {
                units.add(codeUnit(app, "package", e.getKey() + "#" + (++part), current, tokens, priority, byPath));
            }
        }
        return units;
    }

    private static boolean isEntryPoint(Component c) {
        return c != null && (c.effectiveExported() || c.isDeepLink());
    }

    private static Comparator<String> byPriority(Map<String, Integer> priority) {
        return Comparator.comparing((String p) -> -priority.getOrDefault(p, 0)).thenComparing(p -> p);
    }

    private static int codeTokens(List<String> paths, Map<String, Integer> tokens) {
        return paths.stream().mapToInt(tokens::get).sum();
    }

    private int sliceTokens(AppModel app, List<String> paths, Map<String, SourceFile> byPath) {
        return estimator.estimate(context.manifestSlice(app, componentsOf(paths, byPath)));
    }

    private static List<String> componentsOf(List<String> paths, Map<String, SourceFile> byPath) {
        return paths.stream().map(byPath::get).filter(f -> f.component() != null).map(SourceFile::component).toList();
    }

    private ScanUnit codeUnit(AppModel app, String kind, String root, List<String> member, Map<String, Integer> tokens,
                              Map<String, Integer> priority, Map<String, SourceFile> byPath) {
        List<String> sorted = member.stream().sorted().toList();
        String slice = context.manifestSlice(app, componentsOf(sorted, byPath));
        int est = codeTokens(sorted, tokens) + estimator.estimate(slice);
        int prio = sorted.stream().mapToInt(priority::get).sum();
        return unit(kind, root, sorted, slice, null, null, est, prio, byPath);
    }

    private static ScanUnit unit(String kind, String root, List<String> files, String slice, String resources, String deps,
                                 int est, int prio, Map<String, SourceFile> byPath) {
        StringBuilder id = new StringBuilder(kind).append('\n').append(root).append('\n');
        files.forEach(p -> id.append(byPath.get(p).sha256()).append('\n'));
        id.append(slice).append('\n').append(resources).append('\n').append(deps);
        return new ScanUnit(Hashing.sha256(id.toString()).substring(0, 16), kind, root, files, slice, resources, deps, est, prio);
    }
}
