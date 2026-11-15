package com.vulnscan.scan;

import com.vulnscan.ingest.AppModel;
import com.vulnscan.ingest.CodeBase;
import com.vulnscan.ingest.CodeView;
import com.vulnscan.planning.ScanUnit;
import com.vulnscan.prompts.Prompt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the scan prompt for a unit. Order (stable prefix first, for provider prompt caching): system rules + output
 * schema -> guidance block -> unit context -> code. Only the guidance block differs between student arms.
 */
public class PromptBuilder {

    /** JSON schema of the scan answer (sent as structured output where the provider supports it). */
    public static final Map<String, Object> FINDINGS_SCHEMA = findingsSchema();

    private final Prompt prompt;
    private final AppModel app;
    private final CodeBase code;

    public PromptBuilder(Prompt prompt, AppModel app, CodeBase code) {
        this.prompt = prompt;
        this.app = app;
        this.code = code;
    }

    public String system() {
        return prompt.system();
    }

    public String version() {
        return prompt.version();
    }

    public String user(ScanUnit unit, String guidance) {
        return user(unit, guidance, 0, false);
    }

    /**
     * perturb && repeatIndex &gt; 0: the code files and the lessons appear in a shuffled order (seed = repeatIndex), with
     * the same content. Run 1 (repeatIndex 0) always uses the original order.
     */
    public String user(ScanUnit unit, String guidance, int repeatIndex, boolean perturb) {
        boolean shuffle = perturb && repeatIndex > 0;
        Map<String, Object> ctx = new HashMap<>();
        String g = guidance == null || guidance.isBlank() ? null : guidance.strip();
        if (g != null && shuffle) {
            g = shuffleLessons(g, repeatIndex);
        }
        ctx.put("guidance", g);
        ctx.put("package_name", app.packageName());
        ctx.put("min_sdk", app.manifest().minSdk());
        ctx.put("target_sdk", app.manifest().targetSdk());
        ctx.put("manifest_slice", unit.manifestSlice());
        ctx.put("resources", unit.resources());
        ctx.put("dependencies", unit.dependencies());
        List<Map<String, Object>> files = new ArrayList<>();
        List<String> paths = new ArrayList<>(unit.files());
        if (shuffle) {
            java.util.Collections.shuffle(paths, new java.util.Random(repeatIndex));
        }
        for (String path : paths) {
            files.add(Map.of("path", path, "text", CodeView.render(code.text(path))));
        }
        ctx.put("files", files);
        ctx.put("has_files", !files.isEmpty());
        return prompt.user(ctx);
    }

    /** Lessons are rendered as blocks starting with "- L"; shuffle the blocks, keep each block intact. */
    static String shuffleLessons(String guidance, int seed) {
        List<String> blocks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : guidance.split("\n", -1)) {
            if (line.startsWith("- L") && cur.length() > 0) {
                blocks.add(cur.toString().stripTrailing());
                cur.setLength(0);
            }
            cur.append(line).append('\n');
        }
        if (cur.length() > 0) {
            blocks.add(cur.toString().stripTrailing());
        }
        java.util.Collections.shuffle(blocks, new java.util.Random(seed));
        return String.join("\n\n", blocks);
    }

    private static Map<String, Object> findingsSchema() {
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> nullableStr = Map.of("type", List.of("string", "null"));
        Map<String, Object> location = Map.of("type", "object",
                "properties", Map.of("file", str, "component", nullableStr, "symbol", nullableStr),
                "required", List.of("file", "component", "symbol"), "additionalProperties", false);
        Map<String, Object> props = new HashMap<>();
        props.put("title", str);
        props.put("vuln_class", str);
        props.put("cwe", str);
        props.put("owasp_category", Map.of("type", "string", "enum", List.of("MASVS-STORAGE", "MASVS-CRYPTO", "MASVS-AUTH",
                "MASVS-NETWORK", "MASVS-PLATFORM", "MASVS-CODE", "MASVS-RESILIENCE", "MASVS-PRIVACY")));
        props.put("severity", Map.of("type", "string", "enum", List.of("critical", "high", "medium", "low", "info")));
        props.put("confidence", Map.of("type", "number"));
        props.put("locations", Map.of("type", "array", "items", location));
        props.put("evidence", str);
        props.put("rationale", str);
        props.put("kind", Map.of("type", "string", "enum", List.of("code", "manifest", "resource", "dependency")));
        Map<String, Object> finding = Map.of("type", "object", "properties", props,
                "required", List.of("title", "vuln_class", "cwe", "owasp_category", "severity", "confidence", "locations",
                        "evidence", "rationale", "kind"),
                "additionalProperties", false);
        return Map.of("type", "object", "properties", Map.of("findings", Map.of("type", "array", "items", finding)),
                "required", List.of("findings"), "additionalProperties", false);
    }
}
