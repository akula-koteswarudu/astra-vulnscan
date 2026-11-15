package com.vulnscan.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vulnscan.config.ModelsConfig.ModelSpec;
import com.vulnscan.models.LlmClient;
import com.vulnscan.models.LlmException;
import com.vulnscan.models.LlmRequest;
import com.vulnscan.models.LlmResponse;
import com.vulnscan.models.RequestFactory;
import com.vulnscan.prompts.Prompt;
import com.vulnscan.scan.Finding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The LLM judge (an open model, never the teacher, from a different family than the student).
 * Screen: listwise, all items + a batch of findings per call. Confirm: pairwise and blind (no arm, model or id is
 * shown), with a decomposed checklist. Identical (item, finding) texts give identical prompts, so a cached verdict is
 * reused across arms. A call whose answer cannot be parsed after one repair counts as "no" and is recorded as an error.
 */
public class Judge {

    private static final Pattern NAMED_PARAMETER = Pattern.compile("'[\\w.-]+'|\"[\\w.-]+\"|`[\\w.-]+`");

    private final LlmClient client;
    private final ModelSpec spec;
    private final Prompt screenPrompt;
    private final Prompt confirmPrompt;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public Judge(LlmClient client, ModelSpec spec, Prompt screenPrompt, Prompt confirmPrompt) {
        this.client = client;
        this.spec = spec;
        this.screenPrompt = screenPrompt;
        this.confirmPrompt = confirmPrompt;
    }

    public record ScreenResult(Map<String, List<String>> itemsByFinding, List<String> errors) {
    }

    public record Verdict(String itemId, String findingId, Boolean sameType, Boolean sameRootCause, Boolean samePlace,
                          String verdict, String reason, boolean placeApplied, String error, boolean cached) {
    }

    /** One screen call for a batch of findings. Returns finding id -> plausible item ids. */
    public ScreenResult screen(List<ReferenceItem> items, List<Finding> batch, boolean withEnrichment, String purposeTag) {
        StringBuilder itemText = new StringBuilder();
        for (ReferenceItem i : items) {
            itemText.append("- ").append(i.id()).append(": ").append(describeItem(i, withEnrichment, false)).append('\n');
        }
        Map<String, String> labelToId = new LinkedHashMap<>();
        StringBuilder findingText = new StringBuilder();
        for (int k = 0; k < batch.size(); k++) {
            String label = "F" + (k + 1);
            labelToId.put(label, batch.get(k).id());
            findingText.append("- ").append(label).append(": ").append(describeFinding(batch.get(k), true)).append('\n');
        }
        String user = screenPrompt.user(Map.of("items", itemText.toString().strip(), "findings", findingText.toString().strip()));
        List<String> errors = new ArrayList<>();
        Map<String, List<String>> out = new HashMap<>();
        JsonNode root = ask(screenPrompt.system(), user, "judge_screen", purposeTag, errors);
        if (root != null && root.get("results") != null) {
            for (JsonNode r : root.get("results")) {
                String fid = labelToId.get(r.path("finding").asText());
                if (fid == null) {
                    continue;
                }
                List<String> ids = new ArrayList<>();
                r.path("items").forEach(n -> ids.add(n.asText()));
                out.put(fid, ids);
            }
        }
        return new ScreenResult(out, errors);
    }

    /** One confirm call for an (item, finding) pair. place: whether "same place?" is part of the decision. */
    public Verdict confirm(ReferenceItem item, Finding f, boolean withEnrichment, boolean place, String purposeTag) {
        String user = confirmPrompt.user(Map.of("item", describeItem(item, withEnrichment, true), "finding", describeFinding(f, false),
                "place", place));
        List<String> errors = new ArrayList<>();
        boolean[] cached = {false};
        JsonNode root = ask(confirmPrompt.system(), user, "judge_confirm", purposeTag, errors, cached);
        if (root == null) {
            return new Verdict(item.id(), f.id(), null, null, null, "no", null, place, String.join("; ", errors), cached[0]);
        }
        String verdict = root.path("verdict").asText("no").toLowerCase();
        if (!List.of("match", "partial", "no").contains(verdict)) {
            verdict = "no";
        }
        return new Verdict(item.id(), f.id(), bool(root, "same_type"), bool(root, "same_root_cause"),
                place ? bool(root, "same_place") : null, verdict, root.path("reason").asText(null), place, null, cached[0]);
    }

    /**
     * Place is part of the decision only when the item has a location (enriched mode) or its description names one of
     * the app's classes/components (appNameHints) or a quoted parameter such as 'web_url'.
     */
    public static boolean placeApplies(ReferenceItem item, boolean withEnrichment, List<String> appNameHints) {
        if (withEnrichment && item.locations() != null && !item.locations().isEmpty()) {
            return true;
        }
        return !appNameHints.isEmpty() || NAMED_PARAMETER.matcher(item.title() + " " + item.description()).find();
    }

    private JsonNode ask(String system, String user, String purpose, String tag, List<String> errors) {
        return ask(system, user, purpose, tag, errors, new boolean[1]);
    }

    private JsonNode ask(String system, String user, String purpose, String tag, List<String> errors, boolean[] cached) {
        LlmRequest req = RequestFactory.forModel(spec, system, user, null, new LlmRequest.Metadata(purpose, tag, null, null, 0));
        try {
            LlmResponse r = client.complete(req);
            cached[0] = r.cached();
            try {
                return parse(r.text());
            } catch (IllegalArgumentException first) {
                LlmRequest repair = req.withMessages(List.of(req.messages().get(0), LlmRequest.Message.assistant(r.text().isBlank() ? "(empty)" : r.text()),
                        LlmRequest.Message.user("That was not valid JSON (" + first.getMessage() + "). Reply with only the JSON object.")));
                return parse(client.complete(repair).text());
            }
        } catch (IllegalArgumentException | LlmException e) {
            errors.add(purpose + ": " + e.getMessage());
            return null;
        }
    }

    private JsonNode parse(String text) {
        String s = text == null ? "" : text.replaceAll("(?s)<think>.*?</think>", "").strip();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b < a) {
            throw new IllegalArgumentException("no JSON object");
        }
        try {
            return mapper.readTree(s.substring(a, b + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON");
        }
    }

    private static Boolean bool(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n == null || n.isNull() ? null : n.asBoolean();
    }

    /** Item text; enrichment (CWEs, locations) only in enriched mode. */
    static String describeItem(ReferenceItem i, boolean withEnrichment, boolean multiline) {
        String sep = multiline ? "\n" : " | ";
        StringBuilder sb = new StringBuilder();
        sb.append(multiline ? "Title: " : "").append(i.title()).append(multiline ? "\nDescription: " : ": ").append(i.description());
        if (withEnrichment && i.acceptedCwes() != null && !i.acceptedCwes().isEmpty()) {
            sb.append(sep).append("Accepted CWEs: ").append(String.join(", ", i.acceptedCwes()));
        }
        if (withEnrichment && i.locations() != null && !i.locations().isEmpty()) {
            List<String> places = new ArrayList<>();
            for (ReferenceItem.Place p : i.locations()) {
                List<String> parts = new ArrayList<>();
                if (p.component() != null) {
                    parts.add("component " + p.component());
                }
                if (p.clazz() != null) {
                    parts.add(p.clazz() + (p.method() != null ? "." + p.method() : ""));
                }
                if (p.clazz() == null && p.file() != null) {
                    parts.add(p.file());
                }
                places.add(String.join(", ", parts));
            }
            sb.append(sep).append("Location: ").append(String.join("; ", places));
        }
        return sb.toString();
    }

    /** Finding text without provenance (no arm, model, run or id): the judge is blind to where it came from. */
    static String describeFinding(Finding f, boolean compact) {
        String where = f.location().symbol() != null ? f.location().symbol() : f.location().file();
        if (f.location().component() != null) {
            where = "component " + f.location().component() + ", " + where;
        }
        if (f.alsoAt() != null && !f.alsoAt().isEmpty()) {
            where += " (also at: " + f.alsoAt().stream().map(l -> l.symbol() != null ? l.symbol() : String.valueOf(l.file()))
                    .distinct().collect(java.util.stream.Collectors.joining("; ")) + ")";
        }
        int evidenceMax = compact ? 200 : 600;
        int rationaleMax = compact ? 200 : 500;
        String evidence = f.evidence().strip().replaceAll("\\s+", " ");
        String rationale = f.rationale() == null ? "" : f.rationale().strip().replaceAll("\\s+", " ");
        if (compact) {
            return f.title() + " | " + f.cwe() + " | at " + where + " | evidence: " + cut(evidence, evidenceMax) + " | why: " + cut(rationale, rationaleMax);
        }
        return "Title: " + f.title() + "\nClass: " + f.vulnClass() + "\nCWE: " + f.cwe() + "\nLocation: " + where
                + "\nEvidence: " + cut(evidence, evidenceMax) + "\nRationale: " + cut(rationale, rationaleMax);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
