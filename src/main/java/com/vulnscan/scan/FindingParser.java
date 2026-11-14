package com.vulnscan.scan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses a scan response {"findings": [...]}. Each finding is validated on its own: bad ones are counted and dropped,
 * the rest are kept. Tolerates code fences and reasoning tags around the JSON; anything else that is not JSON is a
 * whole-response failure (the scanner then makes one repair call).
 */
public class FindingParser {

    static final Set<String> SEVERITIES = Set.of("critical", "high", "medium", "low", "info");
    static final Set<String> KINDS = Set.of("code", "manifest", "resource", "dependency");
    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");

    private final ObjectMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    public record Parsed(List<RawFinding> findings, int badFindings, List<String> problems) {
    }

    /** Thrown when the response as a whole is not the expected JSON. */
    public static class InvalidResponseException extends Exception {
        public InvalidResponseException(String message) {
            super(message);
        }
    }

    public Parsed parse(String text) throws InvalidResponseException {
        JsonNode root = readJson(text);
        JsonNode array = root.isArray() ? root : root.get("findings");
        if (array == null || !array.isArray()) {
            throw new InvalidResponseException("expected an object with a \"findings\" array");
        }
        List<RawFinding> ok = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int bad = 0;
        for (int i = 0; i < array.size(); i++) {
            try {
                RawFinding f = mapper.treeToValue(array.get(i), RawFinding.class);
                String problem = validate(f);
                if (problem == null) {
                    ok.add(f);
                } else {
                    bad++;
                    problems.add("finding " + i + ": " + problem);
                }
            } catch (Exception e) {
                bad++;
                problems.add("finding " + i + ": " + e.getMessage());
            }
        }
        return new Parsed(ok, bad, problems);
    }

    JsonNode readJson(String text) throws InvalidResponseException {
        String s = THINK.matcher(text == null ? "" : text).replaceAll("").strip();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```\\s*$", "");
        }
        int obj = s.indexOf('{');
        int arr = s.indexOf('[');
        int start = obj < 0 ? arr : (arr < 0 ? obj : Math.min(obj, arr));
        int end = Math.max(s.lastIndexOf('}'), s.lastIndexOf(']'));
        if (start < 0 || end < start) {
            throw new InvalidResponseException("no JSON object in the response");
        }
        try {
            return mapper.readTree(s.substring(start, end + 1));
        } catch (Exception e) {
            throw new InvalidResponseException("invalid JSON: " + e.getMessage().split("\n")[0]);
        }
    }

    static String validate(RawFinding f) {
        if (blank(f.title())) {
            return "missing title";
        }
        if (f.locations() == null || f.locations().isEmpty() || f.locations().stream().allMatch(l -> l == null || blank(l.file()) && blank(l.component()))) {
            return "needs at least one location with a file or component";
        }
        if (blank(f.evidence())) {
            return "missing evidence";
        }
        if (f.severity() != null && !SEVERITIES.contains(f.severity().strip().toLowerCase(Locale.ROOT))) {
            return "unknown severity '" + f.severity() + "'";
        }
        if (f.kind() != null && !KINDS.contains(f.kind().strip().toLowerCase(Locale.ROOT))) {
            return "unknown kind '" + f.kind() + "'";
        }
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
