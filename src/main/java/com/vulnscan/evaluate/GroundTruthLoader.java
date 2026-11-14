package com.vulnscan.evaluate;

import com.vulnscan.common.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The only class that reads ground-truth files (plan rule 4: ground truth is evaluation-only).
 * Validates ids, required README fields and the enrichment vocabulary.
 */
public class GroundTruthLoader {

    static final Set<String> KINDS = Set.of("code", "manifest", "resource", "dependency");
    static final Set<String> VISIBILITY = Set.of("full", "partial", "inventory_only");
    private static final Pattern CWE = Pattern.compile("CWE-\\d+");

    public GroundTruth load(Path file) {
        GroundTruth gt;
        try {
            gt = Json.yaml().readValue(file.toFile(), GroundTruth.class);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read ground truth " + file + ": " + e.getMessage(), e);
        }
        List<String> errors = validate(gt);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("invalid ground truth " + file + ":\n  - " + String.join("\n  - ", errors));
        }
        return gt;
    }

    static List<String> validate(GroundTruth gt) {
        List<String> errors = new ArrayList<>();
        if (gt.items() == null || gt.items().isEmpty()) {
            errors.add("no items");
            return errors;
        }
        Set<String> ids = new HashSet<>();
        for (GroundTruth.Item item : gt.items()) {
            String id = item.id();
            if (id == null || id.isBlank() || !ids.add(id)) {
                errors.add("missing or duplicate id: " + id);
            }
            if (item.title() == null || item.title().isBlank() || item.description() == null || item.description().isBlank()) {
                errors.add(id + ": title and description are required");
            }
            GroundTruth.Enrichment e = item.enrichment();
            if (e == null) {
                continue;
            }
            if (e.kind() != null && !KINDS.contains(e.kind())) {
                errors.add(id + ": kind must be one of " + KINDS);
            }
            if (e.staticVisibility() != null && !VISIBILITY.contains(e.staticVisibility())) {
                errors.add(id + ": static_visibility must be one of " + VISIBILITY);
            }
            if (e.acceptedCwes() != null) {
                e.acceptedCwes().stream().filter(c -> !CWE.matcher(c).matches())
                        .forEach(c -> errors.add(id + ": CWE '" + c + "' is not of the form CWE-<n>"));
            }
            if (e.locations() != null) {
                for (GroundTruth.Location l : e.locations()) {
                    if (l.component() == null && l.clazz() == null && l.file() == null) {
                        errors.add(id + ": a location needs at least one of component, class, file");
                    }
                }
            }
        }
        return errors;
    }
}
